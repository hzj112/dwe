package com.athenahealth.dwe.integration;

import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.athenahealth.dwe.observability.DatabaseSampler;
import com.athenahealth.dwe.observability.EngineTelemetry;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ObservabilityIT extends IntegrationTestSupport {
    @Autowired DataSource source;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void baseDeploymentKeepsMetricsEndpointUnexposed() throws Exception {
        assertThat(request("GET", "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/prometheus", null).statusCode()).isEqualTo(404);
    }

    @Test
    void rollbackAndDuplicateReportsDoNotInflateCommittedTransitionCounters() {
        double before = count("workflow_submitted", "PENDING");
        new TransactionTemplate(transactions).execute(status -> {
            submitOne();
            status.setRollbackOnly();
            return null;
        });
        assertThat(count("workflow_submitted", "PENDING")).isEqualTo(before);
        var id = submitOne();
        assertThat(count("workflow_submitted", "PENDING")).isEqualTo(before + 1);
        store.advance();
        var claim = store.claimNext("observed-worker").orElseThrow();
        double finished = count("attempt_finished", "COMPLETED");
        assertThat(store.complete(claim, json.createObjectNode()).accepted()).isTrue();
        store.complete(claim, json.createObjectNode().put("different", true));
        assertThat(count("attempt_finished", "COMPLETED")).isEqualTo(finished + 1);
        assertThat(queries.execution(id).steps()).allMatch(step -> step.status().name().equals("COMPLETED"));
    }

    @Test
    void rejectedStaleReportDoesNotCountACompletion() {
        var id = submitOne();
        store.advance();
        var claim = store.claimNext("old").orElseThrow();
        jdbc.update("UPDATE step_execution SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE workflow_id = ?", id);
        double before = count("attempt_finished", "COMPLETED");
        assertThat(store.complete(claim, json.createObjectNode()).accepted()).isFalse();
        assertThat(count("attempt_finished", "COMPLETED")).isEqualTo(before);
    }

    @Test
    void samplerDistinguishesUnpromotedReadyBlockedAndFutureBackoff() throws Exception {
        var id = submit("""
                [{"id":"a","actionType":"ECHO"},{"id":"b","actionType":"ECHO","dependsOn":["a"]}]
                """);
        var registry = new SimpleMeterRegistry();
        var sampler = sampler(registry);
        try {
            assertThat(Double.isNaN(sampler.snapshot().ready())).isTrue();
            sampler.sample();
            assertThat(sampler.snapshot().awaitingPromotion()).isEqualTo(1);
            assertThat(sampler.snapshot().ready()).isZero();
            store.advance();
            sampler.sample();
            assertThat(sampler.snapshot().ready()).isEqualTo(1);
            var claim = store.claimNext("sampled").orElseThrow();
            store.fail(claim, "TEMPORARY", "Retry later", true);
            jdbc.update("UPDATE step_execution SET eligible_at = clock_timestamp() + interval '400 milliseconds' WHERE workflow_id = ? AND step_id = 'a'", id);
            sampler.sample();
            assertThat(sampler.snapshot().ready()).isZero();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                sampler.sample();
                assertThat(sampler.snapshot().ready()).isEqualTo(1);
            });
            assertThat(sampler.snapshot().awaitingPromotion()).isZero();
        } finally { sampler.stop(); }
    }

    @Test
    void failedSampleRetainsLastSnapshotAndScrapingDoesNotQueryDatabase() throws Exception {
        submitOne();
        store.advance();
        var registry = new SimpleMeterRegistry();
        var sampler = sampler(registry);
        try {
            sampler.sample();
            var previous = sampler.snapshot();
            assertThat(previous.ready()).isEqualTo(1);
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("LOCK TABLE step_execution IN ACCESS EXCLUSIVE MODE");
                sampler.sample();
                assertThat(sampler.latestSuccess()).isFalse();
                assertThat(sampler.snapshot()).isEqualTo(previous);
                // A gauge read succeeds even while every SELECT on the durable table would block.
                assertThat(registry.get("dwe.steps.ready").gauge().value()).isEqualTo(1);
                assertThat(registry.get("dwe.database.sample.success").gauge().value()).isZero();
                connection.rollback();
            }
            sampler.sample();
            assertThat(sampler.latestSuccess()).isTrue();
        } finally { sampler.stop(); }
    }

    @Test
    void expiredClaimsOnFailedWorkflowsRemainVisible() throws Exception {
        var id = submitOne();
        store.advance();
        store.claimNext("lost-worker").orElseThrow();
        jdbc.update("UPDATE step_execution SET lease_expires_at = clock_timestamp() - interval '2 seconds' WHERE workflow_id = ?", id);
        jdbc.update("UPDATE workflow_execution SET status='FAILED', finished_at=clock_timestamp() WHERE id=?", id);
        var sampler = sampler(new SimpleMeterRegistry());
        try {
            sampler.sample();
            assertThat(sampler.snapshot().expired()).isEqualTo(1);
            assertThat(sampler.snapshot().oldestExpiredSeconds()).isGreaterThanOrEqualTo(2);
        } finally { sampler.stop(); }
    }

    private DatabaseSampler sampler(SimpleMeterRegistry registry) throws Exception {
        return new DatabaseSampler(source, registry, new EngineTelemetry(registry), Duration.ofSeconds(5), Duration.ofMillis(100));
    }

    private double count(String event, String outcome) {
        var meter = meters.find("dwe.events").tags("event", event, "outcome", outcome).counter();
        return meter == null ? 0 : meter.count();
    }
}
