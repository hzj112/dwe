package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Uses real PostgreSQL lock waits to exercise both orderings of competing lifecycle transactions. */
@TestPropertySource(properties = {"engine.lease-duration=30s", "engine.operation-timeout=5s"})
class WorkflowConcurrencyIT extends IntegrationTestSupport {
    /** A failure that acquired the workflow guard first excludes claims during and after its commit. */
    @Test
    void failureHoldingWorkflowGuardPreventsAnotherClaim() throws Exception {
        var id = submitIndependentSteps();
        var first = store.claimNext("first").orElseThrow();
        assertThat(first.stepId()).isEqualTo("a");
        try (var pool = Executors.newFixedThreadPool(1); var blocker = new LockedStep(id, "a")) {
            var failure = pool.submit(() -> store.fail(first, "PERMANENT", "Stop workflow", false));
            // Failure owns the workflow guard but waits for the step lock held by this test.
            awaitBlockedBy(blocker.pid());
            assertThat(store.claimNext("second")).isEmpty();
            blocker.release();
            assertThat(failure.get(10, TimeUnit.SECONDS).accepted()).isTrue();
        }
        assertThat(store.claimNext("after-failure")).isEmpty();
        assertThat(queries.step(id, "b").status()).isEqualTo(StepStatus.FAILED);
        assertThat(queries.step(id, "b").lastAttemptNo()).isZero();
        assertFailedWorkflow(id);
    }

    /** The opposite ordering preserves the earlier claim and lets it renew and report after failure. */
    @Test
    void claimHoldingWorkflowGuardMayFinishAfterAnotherStepFails() throws Exception {
        var id = submitIndependentSteps();
        var first = store.claimNext("first").orElseThrow();
        StepClaim second;
        try (var pool = Executors.newFixedThreadPool(2); var blocker = new LockedStep(id, "b")) {
            var claiming = pool.submit(() -> store.claimNext("second"));
            int claimingPid = awaitBlockedBy(blocker.pid());
            var failure = pool.submit(() -> store.fail(first, "PERMANENT", "Stop workflow", false));
            // The claim already owns the workflow guard, so failure must wait for its transaction.
            awaitBlockedBy(claimingPid);
            blocker.release();
            second = claiming.get(10, TimeUnit.SECONDS).orElseThrow();
            assertThat(failure.get(10, TimeUnit.SECONDS).accepted()).isTrue();
        }
        assertThat(second.stepId()).isEqualTo("b");
        assertThat(queries.execution(id).runningStepCount()).isEqualTo(1);
        assertThat(queries.execution(id).retryAllowed()).isFalse();
        assertThat(store.renew(second)).isTrue();
        assertThat(store.complete(second, json.createObjectNode().put("late", true)).accepted()).isTrue();
        assertThat(queries.step(id, "b").output().get("late").booleanValue()).isTrue();
        assertThat(store.claimNext("after-failure")).isEmpty();
        assertFailedWorkflow(id);
    }

    /** Forces each report to win the guard once; an independent success must never hide workflow failure. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void independentCompletionAndFailurePreserveTerminalFailureInEitherOrder(boolean failureFirst) throws Exception {
        var id = submitIndependentSteps();
        var failing = store.claimNext("first").orElseThrow();
        var completing = store.claimNext("second").orElseThrow();
        String firstStep = failureFirst ? "a" : "b";
        try (var pool = Executors.newFixedThreadPool(2); var blocker = new LockedStep(id, firstStep)) {
            var first = pool.submit(() -> failureFirst
                    ? store.fail(failing, "PERMANENT", "Stop workflow", false)
                    : store.complete(completing, json.createObjectNode().put("value", "done")));
            int firstPid = awaitBlockedBy(blocker.pid());
            var second = pool.submit(() -> failureFirst
                    ? store.complete(completing, json.createObjectNode().put("value", "done"))
                    : store.fail(failing, "PERMANENT", "Stop workflow", false));
            awaitBlockedBy(firstPid);
            blocker.release();
            assertThat(first.get(10, TimeUnit.SECONDS).accepted()).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS).accepted()).isTrue();
        }
        assertThat(queries.step(id, "b").status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(queries.step(id, "b").output().get("value").stringValue()).isEqualTo("done");
        assertThat(queries.execution(id).runningStepCount()).isZero();
        assertFailedWorkflow(id);
    }

    /** Both dependency-waiting and backoff-waiting steps stop without synthetic execution history. */
    @Test
    void workflowFailureAlsoFailsRetryingStepsWithoutInventingAttempts() {
        var id = submitIndependentSteps();
        var first = store.claimNext("first").orElseThrow();
        var retrying = store.claimNext("second").orElseThrow();
        assertThat(store.fail(retrying, "TRANSIENT", "Retry later", true).accepted()).isTrue();
        assertThat(queries.step(id, "b").status()).isEqualTo(StepStatus.RETRYING);
        assertThat(store.fail(first, "PERMANENT", "Stop workflow", false).accepted()).isTrue();
        assertThat(queries.step(id, "b").status()).isEqualTo(StepStatus.FAILED);
        assertThat(queries.step(id, "b").eligibleAt()).isNull();
        assertThat(queries.step(id, "b").error().code()).isEqualTo("WORKFLOW_FAILED");
        assertThat(queries.step(id, "b").attemptsInCurrentCycle()).isEqualTo(1);
        assertFailedWorkflow(id);
    }

    /** Duplicate reports may disagree, but both callers must observe the outcome that committed first. */
    @Test
    void concurrentConflictingReportsReturnOneImmutableStoredOutcome() throws Exception {
        var id = submitOne();
        store.advance();
        var claim = store.claimNext("owner").orElseThrow();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var success = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return store.complete(claim, json.createObjectNode().put("value", "done"));
            });
            var failure = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return store.fail(claim, "PERMANENT", "Stop workflow", false);
            });
            start.countDown();
            var successResult = success.get(10, TimeUnit.SECONDS);
            var failureResult = failure.get(10, TimeUnit.SECONDS);
            assertThat(successResult.accepted()).isTrue();
            assertThat(failureResult).isEqualTo(successResult);
            String stored = jdbc.queryForObject("SELECT status FROM step_attempt WHERE workflow_id = ?", String.class, id);
            assertThat(stored).isEqualTo(successResult.status().name());
            store.advance();
            assertThat(queries.execution(id).status().name()).isEqualTo(stored);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM step_attempt WHERE workflow_id = ?", Integer.class, id))
                    .isEqualTo(1);
        }
    }

    /** Uses a join to detect accidental downstream scheduling after either root fails. */
    private UUID submitIndependentSteps() {
        var id = submit("""
                [{"id":"a","actionType":"ECHO"},{"id":"b","actionType":"ECHO"},
                 {"id":"join","actionType":"ECHO","dependsOn":["a","b"]}]
                """);
        store.advance();
        // Make root ordering explicit so tests do not depend on PostgreSQL's row visitation order.
        jdbc.update("""
                UPDATE step_execution SET eligible_at = CASE step_id
                    WHEN 'a' THEN clock_timestamp() - interval '2 seconds'
                    ELSE clock_timestamp() - interval '1 second' END
                WHERE workflow_id = ? AND step_id IN ('a', 'b')
                """, id);
        return id;
    }

    /** Repeated coordinator passes must neither reopen the workflow nor create an attempt for its join. */
    private void assertFailedWorkflow(UUID id) {
        store.advance();
        store.advance();
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(queries.execution(id).error().code()).isEqualTo("PERMANENT");
        assertThat(queries.step(id, "join").status()).isEqualTo(StepStatus.FAILED);
        assertThat(queries.step(id, "join").lastAttemptNo()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_cycle WHERE workflow_id = ?", String.class, id))
                .isEqualTo("FAILED");
    }

    /** Observes an actual server-side lock wait instead of sleeping and assuming which transaction won. */
    private int awaitBlockedBy(int blockerPid) {
        return await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10)).until(() ->
                jdbc.queryForList("""
                        SELECT pid FROM pg_stat_activity WHERE datname = current_database()
                          AND ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock'
                        """, Integer.class, blockerPid), pids -> !pids.isEmpty()).getFirst();
    }

    /** Holds only a step row as a test gate; production operations still acquire workflow before step. */
    private final class LockedStep implements AutoCloseable {
        private final Connection connection;
        private final int pid;
        private boolean released;

        private LockedStep(UUID id, String stepId) throws SQLException {
            connection = jdbc.getDataSource().getConnection();
            try {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("""
                        SELECT pg_backend_pid() FROM step_execution WHERE workflow_id = ? AND step_id = ? FOR UPDATE
                        """)) {
                    statement.setObject(1, id);
                    statement.setString(2, stepId);
                    try (var result = statement.executeQuery()) {
                        if (!result.next()) throw new SQLException("Test step not found");
                        pid = result.getInt(1);
                    }
                }
            } catch (SQLException | RuntimeException e) {
                connection.close();
                throw e;
            }
        }

        private int pid() { return pid; }

        private void release() throws SQLException {
            if (!released) {
                connection.rollback();
                released = true;
            }
        }

        @Override
        public void close() throws SQLException {
            try { release(); }
            finally { connection.close(); }
        }
    }
}
