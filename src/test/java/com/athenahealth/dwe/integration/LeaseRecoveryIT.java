package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.domain.AttemptStatus;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Checks recovery with real transactions, including delayed heartbeat commits and stale reports. */
@TestPropertySource(properties = {"engine.lease-duration=30s", "engine.operation-timeout=5s"})
class LeaseRecoveryIT extends IntegrationTestSupport {
    /** Abandonment consumes allowance, preserves history, and fences the old owner after replacement. */
    @Test
    void expiredAttemptRetriesAfterBackoffWithANewerToken() {
        var id = submitOne();
        store.advance();
        var old = store.claimNext("old").orElseThrow();
        expire(id);
        Instant before = databaseNow();
        assertThat(store.recoverExpired()).isEqualTo(1);
        var retrying = queries.step(id, "a");
        assertThat(retrying.status()).isEqualTo(StepStatus.RETRYING);
        assertThat(retrying.eligibleAt()).isAfterOrEqualTo(before.plusMillis(1000));
        assertThat(retrying.error().code()).isEqualTo("LEASE_EXPIRED");
        assertThat(store.claimNext("too-early")).isEmpty();
        assertAbandoned(id, 1);
        Instant eligible = retrying.eligibleAt();
        assertThat(store.recoverExpired()).isZero();
        assertThat(queries.step(id, "a").eligibleAt()).isEqualTo(eligible);

        // Advance only eligibility, avoiding a real backoff sleep while keeping the lease checks real.
        jdbc.update("UPDATE step_execution SET eligible_at = clock_timestamp() WHERE workflow_id = ?", id);
        var replacement = store.claimNext("replacement").orElseThrow();
        assertThat(replacement.attemptNo()).isEqualTo(2);
        assertThat(store.renew(old)).isFalse();
        var rejected = store.complete(old, json.createObjectNode().put("stale", true));
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(store.fail(old, "LATE", "Old result", true).accepted()).isFalse();
        assertThat(store.complete(replacement, json.createObjectNode().put("value", "new")).accepted()).isTrue();
        store.advance();
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(queries.step(id, "a").output().get("value").stringValue()).isEqualTo("new");
        assertAbandoned(id, 1);
    }

    /** Repeated worker loss is bounded by the same attempt budget as reported failures. */
    @Test
    void exhaustedAbandonedAttemptsFailWorkflowAndWaitingSteps() {
        var id = submit("""
                [{"id":"a","actionType":"ECHO","retry":{"maxAttempts":2}},
                 {"id":"b","actionType":"ECHO","dependsOn":["a"]}]
                """);
        store.advance();
        store.claimNext("first").orElseThrow();
        expire(id);
        assertThat(store.recoverExpired()).isEqualTo(1);
        jdbc.update("UPDATE step_execution SET eligible_at = clock_timestamp() WHERE workflow_id = ? AND step_id = 'a'", id);
        store.claimNext("second").orElseThrow();
        expire(id);
        assertThat(store.recoverExpired()).isEqualTo(1);
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(queries.execution(id).error().code()).isEqualTo("LEASE_EXPIRED");
        assertThat(queries.step(id, "a").status()).isEqualTo(StepStatus.FAILED);
        assertThat(queries.step(id, "a").attemptsInCurrentCycle()).isEqualTo(2);
        assertThat(queries.step(id, "b").error().code()).isEqualTo("WORKFLOW_FAILED");
        assertThat(queries.step(id, "b").lastAttemptNo()).isZero();
        assertThat(store.claimNext("third")).isEmpty();
        assertAbandoned(id, 2);
    }

    /** Failed workflows still need cleanup, but recovery must not overwrite their original failure. */
    @Test
    void expiredSiblingOnFailedWorkflowIsSettledWithoutRetry() {
        var id = submit("[{\"id\":\"a\",\"actionType\":\"ECHO\"},{\"id\":\"b\",\"actionType\":\"ECHO\"}]");
        store.advance();
        var first = store.claimNext("first").orElseThrow();
        var second = store.claimNext("second").orElseThrow();
        store.fail(first, "ORIGINAL", "Permanent business failure", false);
        assertThat(queries.execution(id).runningStepCount()).isEqualTo(1);
        expire(id);
        assertThat(store.recoverExpired()).isEqualTo(1);
        assertThat(queries.execution(id).runningStepCount()).isZero();
        assertThat(queries.execution(id).error().code()).isEqualTo("ORIGINAL");
        assertThat(queries.step(id, second.stepId()).status()).isEqualTo(StepStatus.FAILED);
        assertThat(queries.step(id, second.stepId()).eligibleAt()).isNull();
        assertThat(store.claimNext("replacement")).isEmpty();
        assertAbandoned(id, 1);
    }

    /** Multiple coordinators can discover the same expiry without duplicating settlement or backoff. */
    @Test
    void competingRecoveryPassesSettleOneAttemptOnlyOnce() throws Exception {
        var id = submitOne();
        store.advance();
        store.claimNext("owner").orElseThrow();
        expire(id);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return store.recoverExpired(); });
            var second = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return store.recoverExpired(); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertAbandoned(id, 1);
        Instant eligible = queries.step(id, "a").eligibleAt();
        assertThat(store.recoverExpired()).isZero();
        assertThat(queries.step(id, "a").eligibleAt()).isEqualTo(eligible);
    }

    /** A valid renewal can commit after discovery; recovery must re-read the lease after its lock wait. */
    @Test
    void renewalCommittedAfterExpiryDiscoveryPreventsAbandonment() throws Exception {
        var id = submitOne();
        store.advance();
        store.claimNext("owner").orElseThrow();
        shortenLease(id);
        try (var pool = Executors.newFixedThreadPool(1); var gate = new StepGate(id)) {
            // Perform the valid heartbeat update before expiry, but deliberately delay its commit.
            try (var renewal = gate.connection.prepareStatement("""
                    UPDATE step_execution SET lease_expires_at = clock_timestamp() + interval '30 seconds'
                    WHERE workflow_id = ? AND lease_expires_at > clock_timestamp()
                    """)) {
                renewal.setObject(1, id);
                assertThat(renewal.executeUpdate()).isEqualTo(1);
            }
            awaitExpiry(id); // Other sessions still see the old, now-expired committed version.
            var recovery = pool.submit(store::recoverExpired);
            awaitBlockedBy(gate.pid);
            gate.commit();
            assertThat(recovery.get(10, TimeUnit.SECONDS)).isZero();
        }
        assertThat(queries.step(id, "a").status()).isEqualTo(StepStatus.RUNNING);
        assertThat(jdbc.queryForObject("SELECT status FROM step_attempt WHERE workflow_id = ?", String.class, id))
                .isEqualTo("RUNNING");
    }

    /** Recovery winning the workflow guard must make an already-waiting late report harmless. */
    @Test
    void outcomeWaitingBehindRecoveryCannotReplaceAbandonment() throws Exception {
        var id = submitOne();
        store.advance();
        var claim = store.claimNext("owner").orElseThrow();
        expire(id);
        try (var pool = Executors.newFixedThreadPool(2); var gate = new StepGate(id)) {
            var recovery = pool.submit(store::recoverExpired);
            int recoveryPid = awaitBlockedBy(gate.pid);
            var outcome = pool.submit(() -> store.complete(claim, json.createObjectNode()));
            awaitBlockedBy(recoveryPid);
            gate.release();
            assertThat(recovery.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(outcome.get(10, TimeUnit.SECONDS).accepted()).isFalse();
        }
        assertAbandoned(id, 1);
    }

    /** Database time must be checked after a lock wait, for both renewal and outcome reporting. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void ownershipOperationCannotUseTimeFromBeforeItsLockWait(boolean renewal) throws Exception {
        var id = submitOne();
        store.advance();
        StepClaim claim = store.claimNext("owner").orElseThrow();
        shortenLease(id);
        try (var pool = Executors.newFixedThreadPool(1); var gate = new StepGate(id)) {
            var operation = pool.submit(() -> renewal ? store.renew(claim)
                    : store.complete(claim, json.createObjectNode()).accepted());
            awaitBlockedBy(gate.pid);
            awaitExpiry(id);
            gate.release();
            assertThat(operation.get(10, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(store.recoverExpired()).isEqualTo(1);
        assertAbandoned(id, 1);
    }

    /** A committed completion is terminal and is never transformed into an abandonment. */
    @Test
    void completedAttemptIsNotRecovered() {
        var id = submitOne();
        store.advance();
        var claim = store.claimNext("owner").orElseThrow();
        store.complete(claim, json.createObjectNode().put("done", true));
        assertThat(store.recoverExpired()).isZero();
        assertThat(queries.step(id, "a").status()).isEqualTo(StepStatus.COMPLETED);
    }

    private void expire(UUID id) {
        jdbc.update("""
                UPDATE step_execution SET lease_expires_at = clock_timestamp() - interval '1 second'
                WHERE workflow_id = ? AND status = 'RUNNING'
                """, id);
    }

    private void shortenLease(UUID id) {
        jdbc.update("UPDATE step_execution SET lease_expires_at = clock_timestamp() + interval '1 second' WHERE workflow_id = ?", id);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
    }

    private void awaitExpiry(UUID id) {
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10)).until(() ->
                Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_expires_at <= clock_timestamp() FROM step_execution WHERE workflow_id = ?",
                        Boolean.class, id)));
    }

    /** ABANDONED is not a reported handler failure, so failure_retryable stays null. */
    private void assertAbandoned(UUID id, int count) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM step_attempt WHERE workflow_id = ? AND status = 'ABANDONED'
                  AND finished_at IS NOT NULL AND error_code = 'LEASE_EXPIRED' AND failure_retryable IS NULL
                """, Integer.class, id)).isEqualTo(count);
    }

    private int awaitBlockedBy(int pid) {
        return await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10)).until(() ->
                jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND ? = ANY(pg_blocking_pids(pid))",
                        Integer.class, pid), pids -> !pids.isEmpty()).getFirst();
    }

    /** A test-only row lock makes transaction ordering observable without modifying production hooks. */
    private final class StepGate implements AutoCloseable {
        private final Connection connection;
        private final int pid;
        private boolean released;

        private StepGate(UUID id) throws SQLException {
            connection = jdbc.getDataSource().getConnection();
            try {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM step_execution WHERE workflow_id = ? FOR UPDATE")) {
                    statement.setObject(1, id);
                    try (var result = statement.executeQuery()) {
                        if (!result.next()) throw new SQLException("Missing test step");
                        pid = result.getInt(1);
                    }
                }
            } catch (SQLException | RuntimeException e) {
                connection.close();
                throw e;
            }
        }

        private void commit() throws SQLException { connection.commit(); released = true; }
        private void release() throws SQLException { if (!released) { connection.rollback(); released = true; } }

        @Override
        public void close() throws SQLException {
            try { release(); }
            finally { connection.close(); }
        }
    }
}
