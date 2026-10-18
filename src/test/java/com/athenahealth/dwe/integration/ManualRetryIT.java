package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.domain.AttemptStatus;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import com.athenahealth.dwe.support.WorkerHarness;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Exercises the retry HTTP contract and atomic cycle transitions against PostgreSQL and real workers. */
class ManualRetryIT extends IntegrationTestSupport {
    /** Completed predecessors and prior history survive; only unfinished work executes in the new cycle. */
    @Test
    void manualRetryPreservesCompletedWorkAndResumesFailedChain() throws Exception {
        var response = request("POST", "/api/v1/executions", """
                {"definition":{"steps":[{"id":"start","actionType":"ECHO","parameters":{"saved":"value"}},
                  {"id":"work","actionType":"FAIL_UNTIL_ATTEMPT","parameters":{"failAttempts":3},
                   "dependsOn":["start"],"retry":{"maxAttempts":3,"backoffInitialMs":50,"backoffMaxMs":100}},
                  {"id":"finish","actionType":"ECHO","dependsOn":["work"]}]},"inputs":{"reference":"original"}}
                """);
        assertThat(response.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(body(response).get("executionId").stringValue());
        try (var worker = new WorkerHarness(store, registry, properties)) { awaitStatus(id, WorkflowStatus.FAILED); }
        var previous = queries.execution(id);
        var output = queries.step(id, "start").output();
        var policy = queries.step(id, "work").retry();
        List<String> history = history(id);
        String cycle = cycle(id, 1);
        assertThat(previous.retryAllowed()).isTrue();

        var retry = request("POST", route(id), null);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(body(retry).get("executionId").stringValue()).isEqualTo(id.toString());
        assertThat(body(retry).get("status").stringValue()).isEqualTo("PENDING");
        assertThat(body(retry).get("currentCycleNo").intValue()).isEqualTo(2);
        var pending = queries.execution(id);
        assertThat(pending.finishedAt()).isNull();
        assertThat(pending.firstStartedAt()).isEqualTo(previous.firstStartedAt());
        assertThat(pending.createdAt()).isEqualTo(previous.createdAt());
        assertThat(pending.definition()).isEqualTo(previous.definition());
        assertThat(pending.inputs()).isEqualTo(previous.inputs());
        assertThat(pending.error()).isNull();
        assertThat(queries.step(id, "start").output()).isEqualTo(output);
        assertThat(queries.step(id, "start").status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(queries.step(id, "start").attemptsInCurrentCycle()).isZero();
        assertThat(queries.step(id, "work").status()).isEqualTo(StepStatus.PENDING);
        assertThat(queries.step(id, "work").lastAttemptNo()).isEqualTo(3);
        assertThat(queries.step(id, "work").attemptsInCurrentCycle()).isZero();
        assertThat(queries.step(id, "work").eligibleAt()).isNull();
        assertThat(queries.step(id, "work").error()).isNull();
        assertThat(queries.step(id, "work").retry()).isEqualTo(policy);

        try (var worker = new WorkerHarness(store, registry, properties)) { awaitStatus(id, WorkflowStatus.COMPLETED); }
        assertThat(queries.step(id, "work").lastAttemptNo()).isEqualTo(4);
        assertThat(queries.step(id, "work").attemptsInCurrentCycle()).isEqualTo(1);
        assertThat(queries.step(id, "work").output().get("idempotencyKey").stringValue()).isEqualTo(id + ":work");
        assertThat(queries.step(id, "work").output().get("cycleNo").intValue()).isEqualTo(2);
        assertThat(queries.step(id, "start").lastAttemptNo()).isEqualTo(1);
        assertThat(history(id)).isEqualTo(history);
        assertThat(cycle(id, 1)).isEqualTo(cycle);
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_cycle WHERE workflow_id = ? AND cycle_no = 2", String.class, id))
                .isEqualTo("COMPLETED");
    }

    /** Fresh cycle allowances and backoff count from one; lifetime fencing counters keep increasing. */
    @Test
    void newCycleResetsAllowanceAndBackoffButNotLifetimeTokens() throws Exception {
        var id = submit("[{\"id\":\"a\",\"actionType\":\"ECHO\",\"retry\":{\"maxAttempts\":2,\"backoffInitialMs\":100,\"backoffMaxMs\":400}}]");
        store.advance();
        store.fail(store.claimNext("first").orElseThrow(), "TRANSIENT", "Failure 1", true);
        var last = claimWhenEligible("second");
        store.fail(last, "TRANSIENT", "Failure 2", true);
        assertThat(request("POST", route(id), null).statusCode()).isEqualTo(200);
        store.advance();
        var next = store.claimNext("third").orElseThrow();
        assertThat(next.attemptNo()).isEqualTo(3);
        assertThat(next.cycleNo()).isEqualTo(2);
        var before = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        store.fail(next, "TRANSIENT", "New cycle failure 1", true);
        var after = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        assertThat(queries.step(id, "a").eligibleAt()).isBetween(before.plusMillis(100), after.plusMillis(100));
        assertThat(queries.step(id, "a").attemptsInCurrentCycle()).isEqualTo(1);
        var finalAttempt = claimWhenEligible("fourth");
        assertThat(finalAttempt.attemptNo()).isEqualTo(4);
        store.fail(finalAttempt, "TRANSIENT", "New cycle failure 2", true);
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(queries.step(id, "a").attemptsInCurrentCycle()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "COMPLETED"})
    void rejectsExecutionsThatAreNotFailed(String state) throws Exception {
        var id = submitOne();
        if (!state.equals("PENDING")) {
            store.advance();
            var claim = store.claimNext("owner").orElseThrow();
            if (state.equals("COMPLETED")) { store.complete(claim, json.createObjectNode()); store.advance(); }
        }
        var rejected = request("POST", route(id), null);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(body(rejected).get("error").get("code").stringValue()).isEqualTo("WORKFLOW_NOT_FAILED");
        assertThat(queries.execution(id).currentCycleNo()).isEqualTo(1);
    }

    /** Expiry alone is not settlement; recovery must close expired attempts before retry is accepted. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runningSiblingBlocksRetryUntilCompletionOrRecovery(boolean expired) throws Exception {
        var id = submit("[{\"id\":\"a\",\"actionType\":\"ECHO\"},{\"id\":\"b\",\"actionType\":\"ECHO\"}]");
        store.advance();
        var failing = store.claimNext("first").orElseThrow();
        var sibling = store.claimNext("second").orElseThrow();
        store.fail(failing, "PERMANENT", "Stop workflow", false);
        if (expired) jdbc.update("UPDATE step_execution SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE workflow_id = ? AND status = 'RUNNING'", id);
        var rejected = request("POST", route(id), null);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(body(rejected).get("error").get("code").stringValue()).isEqualTo("RUNNING_ATTEMPTS_REMAIN");
        if (expired) assertThat(store.recoverExpired()).isEqualTo(1);
        else store.complete(sibling, json.createObjectNode().put("retained", true));
        assertThat(request("POST", route(id), null).statusCode()).isEqualTo(200);
        assertThat(queries.step(id, sibling.stepId()).status()).isEqualTo(expired ? StepStatus.PENDING : StepStatus.COMPLETED);
    }

    /** Workers are absent here, keeping the new cycle pending while two HTTP callers compete. */
    @Test
    void simultaneousRetryRequestsCreateOnlyOneNewCycle() throws Exception {
        var id = failedExecution();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return request("POST", route(id), null); });
            var second = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return request("POST", route(id), null); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).statusCode(), second.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(queries.execution(id).currentCycleNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_cycle WHERE workflow_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "garbage", " "})
    void rejectsAnyNonemptyRetryBodyWithoutChangingState(String payload) throws Exception {
        var id = failedExecution();
        var rejected = request("POST", route(id), payload);
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(body(rejected).get("error").get("code").stringValue()).isEqualTo("INVALID_REQUEST");
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(queries.execution(id).currentCycleNo()).isEqualTo(1);
    }

    @Test
    void unknownExecutionAndMalformedIdHaveStableErrors() throws Exception {
        assertThat(request("POST", route(UUID.randomUUID()), null).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/api/v1/executions/not-a-uuid/retry", null).statusCode()).isEqualTo(400);
    }

    /** A database failure after cycle insertion and step reset must roll back the whole retry command. */
    @Test
    void partialRetryFailureRollsBackCycleAndStepChanges() throws Exception {
        var id = failedExecution();
        var before = history(id);
        jdbc.execute("""
                CREATE FUNCTION reject_retry_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.current_cycle_no <> OLD.current_cycle_no THEN RAISE EXCEPTION 'Injected retry write failure'; END IF;
                  RETURN NEW;
                END $$
                """);
        try {
            jdbc.execute("CREATE TRIGGER reject_retry_for_test BEFORE UPDATE ON workflow_execution FOR EACH ROW EXECUTE FUNCTION reject_retry_for_test()");
            assertThat(request("POST", route(id), null).statusCode()).isEqualTo(503);
            assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(queries.step(id, "a").status()).isEqualTo(StepStatus.FAILED);
            assertThat(queries.execution(id).currentCycleNo()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_cycle WHERE workflow_id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(history(id)).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_retry_for_test ON workflow_execution");
            jdbc.execute("DROP FUNCTION reject_retry_for_test()");
        }
    }

    /** Replaying an old terminal report acknowledges history without affecting a newer running claim. */
    @Test
    void priorCycleReportsCannotOverwriteNewCycleExecution() throws Exception {
        var id = submitOne();
        store.advance();
        var old = store.claimNext("old").orElseThrow();
        store.fail(old, "PERMANENT", "Stop", false);
        request("POST", route(id), null);
        store.advance();
        var current = store.claimNext("current").orElseThrow();
        assertThat(current.attemptNo()).isEqualTo(2);
        var prior = store.complete(old, json.createObjectNode().put("stale", true));
        assertThat(prior.status()).isEqualTo(AttemptStatus.FAILED);
        assertThat(store.renew(old)).isFalse();
        assertThat(store.owns(current)).isTrue();
        store.complete(current, json.createObjectNode().put("fresh", true));
        store.advance();
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(queries.step(id, "a").output().get("fresh").booleanValue()).isTrue();
    }

    private UUID failedExecution() {
        var id = submitOne();
        store.advance();
        store.fail(store.claimNext("owner").orElseThrow(), "PERMANENT", "Stop", false);
        return id;
    }

    private StepClaim claimWhenEligible(String worker) {
        return await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                .until(() -> store.claimNext(worker), java.util.Optional::isPresent).orElseThrow();
    }

    private String route(UUID id) { return "/api/v1/executions/" + id + "/retry"; }

    /** Serialized rows let the test compare all original timestamps, classifications, and results unchanged. */
    private List<String> history(UUID id) {
        return jdbc.queryForList("SELECT row_to_json(a)::text FROM step_attempt a WHERE workflow_id = ? AND cycle_no = 1 ORDER BY step_id, attempt_no", String.class, id);
    }

    private String cycle(UUID id, int cycle) {
        return jdbc.queryForObject("SELECT row_to_json(c)::text FROM workflow_cycle c WHERE workflow_id = ? AND cycle_no = ?", String.class, id, cycle);
    }

    private void awaitStatus(UUID id, WorkflowStatus status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            store.advance();
            assertThat(queries.execution(id).status()).isEqualTo(status);
        });
    }
}
