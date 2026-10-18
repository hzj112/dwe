package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.demo.EchoHandler;
import com.athenahealth.dwe.persistence.WorkflowStore;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import com.athenahealth.dwe.support.WorkerHarness;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Verifies handler-driven retry outcomes separately from database timing and restart persistence. */
class AutomaticRetryIT extends IntegrationTestSupport {
    @Autowired private PlatformTransactionManager transactions;

    /** The registered demo fails twice, then succeeds; downstream work sees only its successful output. */
    @Test
    void realWorkerRetriesToSuccessAndRetainsFailedAttemptHistory() {
        var id = submit("""
                [{"id":"work","actionType":"FAIL_UNTIL_ATTEMPT","parameters":{"failAttempts":2},
                  "retry":{"maxAttempts":3,"backoffInitialMs":50,"backoffMaxMs":100}},
                 {"id":"finish","actionType":"ECHO","dependsOn":["work"]}]
                """);
        try (var worker = new WorkerHarness(store, registry, properties)) { awaitStatus(id, WorkflowStatus.COMPLETED); }
        assertThat(queries.step(id, "work").lastAttemptNo()).isEqualTo(3);
        assertThat(queries.step(id, "work").error()).isNull();
        assertThat(queries.step(id, "finish").output().get("dependencies").get("work").get("attemptNo").longValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM step_attempt WHERE workflow_id = ? AND status = 'FAILED'
                  AND error_code = 'DEMO_FAILURE' AND failure_retryable = true AND finished_at IS NOT NULL
                """, Integer.class, id)).isEqualTo(2);
    }

    /** Reads persisted eligibility before each next claim, proving doubling, capping, and real waiting. */
    @Test
    void databaseBackoffDoublesCapsAndIsNotResetByAdvancement() {
        var id = submit("""
                [{"id":"a","actionType":"ECHO","retry":{"maxAttempts":5,"backoffInitialMs":80,"backoffMaxMs":160}}]
                """);
        store.advance();
        var claim = store.claimNext("owner").orElseThrow();
        for (long delay : new long[] {80, 160, 160, 160}) {
            Instant before = databaseNow();
            store.fail(claim, "TRANSIENT", "Try again", true);
            Instant after = databaseNow();
            Instant eligible = queries.step(id, "a").eligibleAt();
            assertThat(eligible).isBetween(before.plusMillis(delay), after.plusMillis(delay));
            assertThat(store.claimNext("too-early")).isEmpty();
            store.advance();
            assertThat(queries.step(id, "a").eligibleAt()).isEqualTo(eligible);
            claim = await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                    .until(() -> store.claimNext("owner"), java.util.Optional::isPresent).orElseThrow();
            var started = jdbc.queryForObject("""
                    SELECT started_at FROM step_attempt WHERE workflow_id = ? AND attempt_no = ?
                    """, Timestamp.class, id, claim.attemptNo()).toInstant();
            assertThat(started).isAfterOrEqualTo(eligible);
        }
        store.complete(claim, json.createObjectNode());
        store.advance();
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    /** Recreating persistence components cannot reset the persisted delay or per-cycle allowance. */
    @Test
    void newStoreRetainsBackoffAndExhaustsTheOriginalAllowance() {
        var id = submit("""
                [{"id":"a","actionType":"ECHO","retry":{"maxAttempts":2,"backoffInitialMs":1000,"backoffMaxMs":1000}}]
                """);
        store.advance();
        store.fail(store.claimNext("first").orElseThrow(), "TRANSIENT", "Try again", true);
        var eligible = queries.step(id, "a").eligibleAt();
        var restarted = new WorkflowStore(jdbc, json, properties, transactions);
        restarted.advance();
        assertThat(queries.step(id, "a").eligibleAt()).isEqualTo(eligible);
        assertThat(restarted.claimNext("too-early")).isEmpty();
        StepClaim last = await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> restarted.claimNext("second"), java.util.Optional::isPresent).orElseThrow();
        restarted.fail(last, "TRANSIENT", "Allowance exhausted", true);
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(queries.step(id, "a").attemptsInCurrentCycle()).isEqualTo(2);
        assertThat(restarted.claimNext("third")).isEmpty();
    }

    /** Permanent classification ends execution immediately, even with four unused attempts. */
    @Test
    void permanentHandlerFailureSkipsAutomaticRetry() {
        var id = submit("""
                [{"id":"work","actionType":"FAIL_UNTIL_ATTEMPT","parameters":{"failAttempts":10,"retryable":false},
                  "retry":{"maxAttempts":5}}, {"id":"finish","actionType":"ECHO","dependsOn":["work"]}]
                """);
        try (var worker = new WorkerHarness(store, registry, properties)) { awaitStatus(id, WorkflowStatus.FAILED); }
        assertThat(queries.step(id, "work").lastAttemptNo()).isEqualTo(1);
        assertThat(queries.step(id, "finish").lastAttemptNo()).isZero();
        assertThat(jdbc.queryForObject("SELECT failure_retryable FROM step_attempt WHERE workflow_id = ?", Boolean.class, id)).isFalse();
    }

    /** Ordinary exceptions are retryable, but remain bounded when the handler never recovers. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedHandlerExceptionsRespectRetryAllowance(boolean alwaysFails) {
        var calls = new AtomicInteger();
        var handlers = new HandlerRegistry(List.of(new EchoHandler() {
            @Override
            public JsonNode execute(StepContext context) throws InterruptedException {
                if (calls.incrementAndGet() == 1 || alwaysFails) throw new IllegalStateException("Unexpected business error");
                return super.execute(context);
            }
        }));
        var id = submit("[{\"id\":\"a\",\"actionType\":\"ECHO\",\"retry\":{\"maxAttempts\":2,\"backoffInitialMs\":50}}]");
        try (var worker = new WorkerHarness(store, handlers, properties)) {
            awaitStatus(id, alwaysFails ? WorkflowStatus.FAILED : WorkflowStatus.COMPLETED);
        }
        assertThat(calls).hasValue(2);
        assertThat(jdbc.queryForObject("""
                SELECT error_code FROM step_attempt WHERE workflow_id = ? AND attempt_no = 1
                """, String.class, id)).isEqualTo("HANDLER_ERROR");
        assertThat(jdbc.queryForObject("""
                SELECT failure_retryable FROM step_attempt WHERE workflow_id = ? AND attempt_no = 1
                """, Boolean.class, id)).isTrue();
    }

    /** A late retryable failure on an already-failed workflow must not schedule more business work. */
    @Test
    void retryableSiblingFailureAfterWorkflowFailureDoesNotRetry() {
        var id = submit("[{\"id\":\"a\",\"actionType\":\"ECHO\"},{\"id\":\"b\",\"actionType\":\"ECHO\"}]");
        store.advance();
        var first = store.claimNext("first").orElseThrow();
        var second = store.claimNext("second").orElseThrow();
        store.fail(first, "PERMANENT", "Stop", false);
        store.fail(second, "TRANSIENT", "Late failure", true);
        assertThat(queries.step(id, second.stepId()).status()).isEqualTo(StepStatus.FAILED);
        assertThat(queries.step(id, second.stepId()).eligibleAt()).isNull();
        assertThat(queries.execution(id).error().code()).isEqualTo("PERMANENT");
        assertThat(store.claimNext("third")).isEmpty();
    }

    private void awaitStatus(UUID id, WorkflowStatus status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            store.advance();
            assertThat(queries.execution(id).status()).isEqualTo(status);
        });
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }
}
