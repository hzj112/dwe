package com.athenahealth.dwe.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.domain.AttemptStatus;
import com.athenahealth.dwe.domain.RetryPolicy;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.domain.StepDefinition;
import com.athenahealth.dwe.service.WorkflowValidationService.ValidatedSubmission;
import com.athenahealth.dwe.persistence.WorkflowQueries.ResourceNotFoundException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes workflow state through short database transactions; business handlers never run inside them.
 * Lifecycle changes lock the workflow row before step rows so competing updates use the same order.
 * Lease renewals lock only the claimed step, avoiding the broader workflow lock.
 */
@Repository
public class WorkflowStore {
    private static final String LEASE_EXPIRED = "LEASE_EXPIRED";
    private static final String LEASE_EXPIRED_MESSAGE = "Worker lease expired before an outcome was recorded";
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final EngineProperties properties;
    private final TransactionTemplate transaction;

    public WorkflowStore(JdbcTemplate jdbc, JsonMapper json, EngineProperties properties,
                         PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout((int) Math.max(1, properties.operationTimeout().toSeconds()
                + (properties.operationTimeout().getNano() == 0 ? 0 : 1)));
    }

    /** Whether a report is recognized, plus its stored attempt outcome; duplicate reports return that outcome. */
    public record ReportResult(boolean accepted, AttemptStatus status) { }
    /** Explains a state conflict without treating a rejected retry command as a database failure. */
    public static final class RetryConflictException extends RuntimeException {
        private final String code;
        public RetryConflictException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
    private record Candidate(UUID executionId, String stepId) { }
    private record ExpiredCandidate(UUID executionId, String stepId, long attemptNo, int cycleNo, String workerId) { }
    private record WorkflowRow(String status, int cycleNo, JsonNode inputs) {
        boolean active() { return status.equals("PENDING") || status.equals("RUNNING"); }
    }
    private record StepRow(String status, long attemptNo, String workerId,
                           String actionType, JsonNode parameters, RetryPolicy retry) { }

    /** Saves the original definition, first cycle, executable steps, and dependency edges atomically. */
    public UUID submit(ValidatedSubmission submission) {
        UUID id = UUID.randomUUID();
        return inTransaction(() -> {
            jdbc.update("""
                    INSERT INTO workflow_execution(id, definition_json, inputs_json, status, current_cycle_no)
                    VALUES (?, ?::jsonb, ?::jsonb, 'PENDING', 1)
                    """, id, json.writeValueAsString(submission.definitionSnapshot()), json.writeValueAsString(submission.inputs()));
            jdbc.update("INSERT INTO workflow_cycle(workflow_id, cycle_no, status) VALUES (?, 1, 'PENDING')", id);
            for (StepDefinition step : submission.definition().steps()) {
                jdbc.update("""
                        INSERT INTO step_execution(workflow_id, step_id, action_type, parameters_json,
                            max_attempts, backoff_initial_ms, backoff_max_ms, status)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, 'PENDING')
                        """, id, step.id(), step.actionType(), json.writeValueAsString(step.parameters()),
                        step.retry().maxAttempts(), step.retry().backoffInitialMs(), step.retry().backoffMaxMs());
            }
            for (StepDefinition step : submission.definition().steps()) {
                for (String dependency : step.dependsOn()) {
                    jdbc.update("INSERT INTO step_dependency(workflow_id, step_id, depends_on_step_id) VALUES (?, ?, ?)",
                            id, step.id(), dependency);
                }
            }
            return id;
        });
    }

    /**
     * Reopens a failed execution in a new cycle, retaining completed outputs and all historical records.
     * The workflow guard serializes retry with claims, recovery, and other retry requests.
     */
    public int retry(UUID executionId) {
        return inTransaction(() -> {
            WorkflowRow previous = workflow(executionId, false)
                    .orElseThrow(() -> new ResourceNotFoundException("Execution not found"));
            if (!previous.status().equals("FAILED")) {
                throw new RetryConflictException("WORKFLOW_NOT_FAILED", "Only a failed execution can be retried");
            }
            boolean unfinished = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM step_attempt WHERE workflow_id = ? AND status = 'RUNNING')
                        OR EXISTS (SELECT 1 FROM step_execution WHERE workflow_id = ? AND status = 'RUNNING')
                    """, Boolean.class, executionId, executionId));
            // An expired lease is still unfinished until recovery records its terminal abandonment.
            if (unfinished) throw new RetryConflictException("RUNNING_ATTEMPTS_REMAIN", "Running attempts must settle before retry");
            int nextCycle = Math.incrementExact(previous.cycleNo());
            jdbc.update("INSERT INTO workflow_cycle(workflow_id, cycle_no, status) VALUES (?, ?, 'PENDING')",
                    executionId, nextCycle);
            jdbc.update("""
                    UPDATE step_execution SET status = 'PENDING', eligible_at = NULL, error_code = NULL,
                        error_message = NULL, updated_at = clock_timestamp()
                    WHERE workflow_id = ? AND status = 'FAILED'
                    """, executionId);
            // Keep policies, definition, inputs, first-start time, and lifetime fencing counters unchanged.
            jdbc.update("""
                    UPDATE workflow_execution SET status = 'PENDING', current_cycle_no = ?, finished_at = NULL,
                        updated_at = clock_timestamp() WHERE id = ?
                    """, nextCycle, executionId);
            return nextCycle;
        });
    }

    /** Makes pending steps eligible when all prerequisites complete; completes fully finished workflows. */
    public void advance() {
        List<UUID> ids = jdbc.query("SELECT id FROM workflow_execution WHERE status IN ('PENDING','RUNNING') ORDER BY id",
                (rs, row) -> rs.getObject(1, UUID.class));
        for (UUID id : ids) {
            inTransaction(() -> {
                Optional<WorkflowRow> workflow = workflow(id, true);
                if (workflow.isEmpty() || !workflow.get().active()) return null;
                jdbc.update("""
                        UPDATE step_execution s SET eligible_at = clock_timestamp(), updated_at = clock_timestamp()
                        WHERE workflow_id = ? AND status = 'PENDING' AND eligible_at IS NULL
                          AND NOT EXISTS (
                            SELECT 1 FROM step_dependency d JOIN step_execution p
                              ON p.workflow_id = d.workflow_id AND p.step_id = d.depends_on_step_id
                            WHERE d.workflow_id = s.workflow_id AND d.step_id = s.step_id AND p.status <> 'COMPLETED')
                        """, id);
                boolean unfinished = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM step_execution WHERE workflow_id = ? AND status <> 'COMPLETED')",
                        Boolean.class, id));
                if (!unfinished) {
                    jdbc.update("""
                            UPDATE workflow_execution SET status = 'COMPLETED', finished_at = clock_timestamp(),
                                updated_at = clock_timestamp() WHERE id = ?
                            """, id);
                    jdbc.update("""
                            UPDATE workflow_cycle SET status = 'COMPLETED', finished_at = clock_timestamp()
                            WHERE workflow_id = ? AND cycle_no = ?
                            """, id, workflow.get().cycleNo());
                }
                return null;
            });
        }
    }

    /** Finds candidate work, then attempts to acquire one step with durable ownership in a transaction. */
    public Optional<StepClaim> claimNext(String workerId) {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("Worker ID is required");
        long requestStarted = System.nanoTime();
        List<Candidate> candidates = jdbc.query("""
                SELECT s.workflow_id, s.step_id FROM step_execution s JOIN workflow_execution w ON w.id = s.workflow_id
                WHERE s.status IN ('PENDING','RETRYING') AND s.eligible_at <= clock_timestamp()
                  AND w.status IN ('PENDING','RUNNING')
                ORDER BY s.eligible_at, s.workflow_id, s.step_id LIMIT 32
                """, (rs, row) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2)));
        for (Candidate candidate : candidates) {
            var proposed = new AtomicReference<StepClaim>();
            try {
                Optional<StepClaim> result = inTransaction(() -> claim(candidate, workerId, requestStarted, proposed));
                if (result.isPresent()) return result;
            } catch (DataAccessException | TransactionException e) {
                // A commit response can be lost. Execute only a claim that can be confirmed in durable state.
                if (proposed.get() != null) {
                    StepClaim claim = proposed.get();
                    if (owns(claim)) return Optional.of(claim);
                }
                throw e;
            }
        }
        return Optional.empty();
    }

    /** Rechecks eligibility under locks; the earlier candidate query alone does not grant ownership. */
    private Optional<StepClaim> claim(Candidate candidate, String workerId, long requestStarted,
                                     AtomicReference<StepClaim> proposed) {
        Optional<WorkflowRow> selected = workflow(candidate.executionId(), true);
        if (selected.isEmpty() || !selected.get().active()) return Optional.empty();
        WorkflowRow workflow = selected.get();
        StepRow step = lockStep(candidate.executionId(), candidate.stepId());
        if (!step.status().equals("PENDING") && !step.status().equals("RETRYING")) return Optional.empty();
        boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT eligible_at <= clock_timestamp() AND NOT EXISTS (
                    SELECT 1 FROM step_dependency d JOIN step_execution p
                    ON p.workflow_id = d.workflow_id AND p.step_id = d.depends_on_step_id
                    WHERE d.workflow_id = s.workflow_id AND d.step_id = s.step_id AND p.status <> 'COMPLETED')
                FROM step_execution s WHERE workflow_id = ? AND step_id = ?
                """, Boolean.class, candidate.executionId(), candidate.stepId()));
        if (!eligible || attemptCount(candidate.executionId(), candidate.stepId(), workflow.cycleNo()) >= step.retry().maxAttempts()) {
            return Optional.empty();
        }
        // Every new attempt gets a larger token, allowing later writes to reject stale attempts.
        long token = Math.incrementExact(step.attemptNo());
        jdbc.update("""
                UPDATE step_execution SET status = 'RUNNING', last_attempt_no = ?, worker_id = ?,
                    lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    eligible_at = NULL, updated_at = clock_timestamp()
                WHERE workflow_id = ? AND step_id = ?
                """, token, workerId, properties.leaseDuration().toMillis(), candidate.executionId(), candidate.stepId());
        jdbc.update("""
                INSERT INTO step_attempt(workflow_id, step_id, attempt_no, cycle_no, worker_id, status)
                VALUES (?, ?, ?, ?, ?, 'RUNNING')
                """, candidate.executionId(), candidate.stepId(), token, workflow.cycleNo(), workerId);
        jdbc.update("""
                UPDATE workflow_execution SET status = 'RUNNING',
                    first_started_at = COALESCE(first_started_at, clock_timestamp()), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'PENDING'
                """, candidate.executionId());
        jdbc.update("""
                UPDATE workflow_cycle SET status = 'RUNNING', started_at = clock_timestamp()
                WHERE workflow_id = ? AND cycle_no = ? AND status = 'PENDING'
                """, candidate.executionId(), workflow.cycleNo());
        var outputs = new LinkedHashMap<String, JsonNode>();
        jdbc.query("""
                SELECT p.step_id, p.output_json::text FROM step_dependency d JOIN step_execution p
                  ON p.workflow_id = d.workflow_id AND p.step_id = d.depends_on_step_id
                WHERE d.workflow_id = ? AND d.step_id = ? ORDER BY p.step_id
                """, rs -> { outputs.put(rs.getString(1), json.readTree(rs.getString(2))); }, candidate.executionId(), candidate.stepId());
        StepClaim claim = new StepClaim(candidate.executionId(), candidate.stepId(), step.actionType(),
                workflow.cycleNo(), token, workerId, workflow.inputs(), step.parameters(), outputs, requestStarted);
        proposed.set(claim);
        return Optional.of(claim);
    }

    /** Extends an unexpired lease only if the worker and attempt still match the current running step. */
    public boolean renew(StepClaim claim) {
        return Boolean.TRUE.equals(inTransaction(() -> {
            lockStep(claim.executionId(), claim.stepId());
            // The live clock check follows the step lock, including any wait behind another updater.
            return jdbc.update("""
                    UPDATE step_execution SET lease_expires_at = clock_timestamp() + (? * interval '1 millisecond')
                    WHERE workflow_id = ? AND step_id = ? AND status = 'RUNNING' AND worker_id = ?
                      AND last_attempt_no = ? AND lease_expires_at > clock_timestamp()
                    """, properties.leaseDuration().toMillis(), claim.executionId(), claim.stepId(), claim.workerId(), claim.attemptNo()) == 1;
        }));
    }

    /** Checks durable ownership after an uncertain claim response; this read does not extend the lease. */
    public boolean owns(StepClaim claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM step_execution s JOIN workflow_execution w ON w.id = s.workflow_id
                    WHERE s.workflow_id = ? AND s.step_id = ? AND s.status = 'RUNNING' AND s.worker_id = ?
                      AND s.last_attempt_no = ? AND s.lease_expires_at > clock_timestamp() AND w.current_cycle_no = ?)
                """, Boolean.class, claim.executionId(), claim.stepId(), claim.workerId(), claim.attemptNo(), claim.cycleNo()));
    }

    /**
     * Discovers a bounded batch of expired claims without assuming their owners have stopped running.
     * Each candidate is rechecked in its own guarded transaction; failed workflows are included.
     */
    public int recoverExpired() {
        List<ExpiredCandidate> candidates = jdbc.query("""
                SELECT s.workflow_id, s.step_id, s.last_attempt_no, a.cycle_no, s.worker_id
                FROM step_execution s JOIN step_attempt a ON a.workflow_id = s.workflow_id
                  AND a.step_id = s.step_id AND a.attempt_no = s.last_attempt_no
                WHERE s.status = 'RUNNING' AND a.status = 'RUNNING' AND s.lease_expires_at <= clock_timestamp()
                ORDER BY s.lease_expires_at, s.workflow_id, s.step_id LIMIT 100
                """, (rs, row) -> new ExpiredCandidate(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getLong(3), rs.getInt(4), rs.getString(5)));
        int recovered = 0;
        for (ExpiredCandidate candidate : candidates) {
            if (Boolean.TRUE.equals(inTransaction(() -> recover(candidate)))) recovered++;
        }
        return recovered;
    }

    /** Settles one exact attempt; a newer claim or a committed heartbeat invalidates stale discovery. */
    private boolean recover(ExpiredCandidate candidate) {
        Optional<WorkflowRow> selected = workflow(candidate.executionId(), true);
        if (selected.isEmpty() || selected.get().cycleNo() != candidate.cycleNo()) return false;
        WorkflowRow workflow = selected.get();
        StepRow step = lockStep(candidate.executionId(), candidate.stepId());
        if (!step.status().equals("RUNNING") || step.attemptNo() != candidate.attemptNo()
                || !candidate.workerId().equals(step.workerId())) return false;
        int attempts = attemptCount(candidate.executionId(), candidate.stepId(), candidate.cycleNo());
        boolean retry = workflow.active() && attempts < step.retry().maxAttempts();
        long delay = retry ? step.retry().delayAfter(attempts) : 0;
        // Evaluate database time after the step lock: a heartbeat may have committed while we waited.
        int updated = jdbc.update("""
                UPDATE step_execution SET status = ?, worker_id = NULL, lease_expires_at = NULL,
                    eligible_at = CASE WHEN ? THEN clock_timestamp() + (? * interval '1 millisecond') ELSE NULL END,
                    error_code = ?, error_message = ?, updated_at = clock_timestamp()
                WHERE workflow_id = ? AND step_id = ? AND status = 'RUNNING' AND last_attempt_no = ?
                  AND worker_id = ? AND lease_expires_at <= clock_timestamp()
                """, retry ? "RETRYING" : "FAILED", retry, delay, LEASE_EXPIRED, LEASE_EXPIRED_MESSAGE,
                candidate.executionId(), candidate.stepId(), candidate.attemptNo(), candidate.workerId());
        if (updated != 1) return false;
        int closed = jdbc.update("""
                UPDATE step_attempt SET status = 'ABANDONED', finished_at = clock_timestamp(),
                    error_code = ?, error_message = ?
                WHERE workflow_id = ? AND step_id = ? AND attempt_no = ? AND cycle_no = ?
                  AND worker_id = ? AND status = 'RUNNING'
                """, LEASE_EXPIRED, LEASE_EXPIRED_MESSAGE, candidate.executionId(), candidate.stepId(),
                candidate.attemptNo(), candidate.cycleNo(), candidate.workerId());
        // Roll back the step change if history no longer matches; never leave partially settled ownership.
        if (closed != 1) throw new IllegalStateException("Expired step has no matching running attempt");
        if (!retry && workflow.active()) {
            failWorkflow(candidate.executionId(), candidate.cycleNo(), candidate.stepId(), LEASE_EXPIRED, LEASE_EXPIRED_MESSAGE);
        }
        return true;
    }

    /** Records successful output if the attempt still owns the step, or returns its prior stored outcome. */
    public ReportResult complete(StepClaim claim, JsonNode output) {
        if (output == null || !output.isObject()) {
            throw new IllegalArgumentException("Successful handler output must be a JSON object");
        }
        return finish(claim, output, null, null, false);
    }

    /** Reads an immutable terminal outcome after a lost report response, without renewing or changing ownership. */
    public Optional<ReportResult> recordedOutcome(StepClaim claim) {
        return jdbc.query("""
                SELECT status FROM step_attempt WHERE workflow_id = ? AND step_id = ? AND attempt_no = ?
                  AND cycle_no = ? AND worker_id = ? AND status <> 'RUNNING'
                """, (rs, row) -> {
            AttemptStatus status = AttemptStatus.valueOf(rs.getString(1));
            return new ReportResult(status != AttemptStatus.ABANDONED, status);
        }, claim.executionId(), claim.stepId(), claim.attemptNo(), claim.cycleNo(), claim.workerId()).stream().findFirst();
    }

    /** Records failure and either schedules a retry or permanently fails the step and workflow. */
    public ReportResult fail(StepClaim claim, String code, String message, boolean retryable) {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("A failed attempt needs an error code and message");
        }
        return finish(claim, null, code, message, retryable);
    }

    /** Reconciles repeated reports and updates the step, attempt history, and any workflow failure atomically. */
    private ReportResult finish(StepClaim claim, JsonNode output, String code, String message, boolean retryable) {
        return inTransaction(() -> {
            Optional<WorkflowRow> selected = workflow(claim.executionId(), false);
            if (selected.isEmpty()) return new ReportResult(false, null);
            WorkflowRow workflow = selected.get();
            StepRow step = lockStep(claim.executionId(), claim.stepId());
            List<AttemptStatus> attempts = jdbc.query("""
                    SELECT status FROM step_attempt WHERE workflow_id = ? AND step_id = ? AND attempt_no = ?
                        AND cycle_no = ? AND worker_id = ?
                    """, (rs, row) -> AttemptStatus.valueOf(rs.getString(1)), claim.executionId(), claim.stepId(),
                    claim.attemptNo(), claim.cycleNo(), claim.workerId());
            if (attempts.isEmpty()) return new ReportResult(false, null);
            AttemptStatus prior = attempts.getFirst();
            // A repeated report cannot replace a terminal outcome, even if its supplied result differs.
            if (prior != AttemptStatus.RUNNING) {
                return new ReportResult(prior != AttemptStatus.ABANDONED, prior);
            }
            if (workflow.cycleNo() != claim.cycleNo() || !step.status().equals("RUNNING")
                    || step.attemptNo() != claim.attemptNo() || !claim.workerId().equals(step.workerId())) {
                return new ReportResult(false, null);
            }
            boolean success = output != null;
            boolean retry = !success && retryable && workflow.active()
                    && attemptCount(claim.executionId(), claim.stepId(), claim.cycleNo()) < step.retry().maxAttempts();
            long delay = retry ? step.retry().delayAfter(attemptCount(claim.executionId(), claim.stepId(), claim.cycleNo())) : 0;
            String status = success ? "COMPLETED" : retry ? "RETRYING" : "FAILED";
            int updated = jdbc.update("""
                    UPDATE step_execution SET status = ?, output_json = ?::jsonb, error_code = ?, error_message = ?,
                        worker_id = NULL, lease_expires_at = NULL, updated_at = clock_timestamp(),
                        eligible_at = CASE WHEN ? THEN clock_timestamp() + (? * interval '1 millisecond') ELSE NULL END
                    WHERE workflow_id = ? AND step_id = ? AND lease_expires_at > clock_timestamp()
                        AND worker_id = ? AND last_attempt_no = ? AND status = 'RUNNING'
                    """, status, success ? json.writeValueAsString(output) : null, code, message, retry, delay,
                    claim.executionId(), claim.stepId(), claim.workerId(), claim.attemptNo());
            if (updated != 1) return new ReportResult(false, null);
            AttemptStatus outcome = success ? AttemptStatus.COMPLETED : AttemptStatus.FAILED;
            jdbc.update("""
                    UPDATE step_attempt SET status = ?, finished_at = clock_timestamp(), result_json = ?::jsonb,
                        error_code = ?, error_message = ?, failure_retryable = ?
                    WHERE workflow_id = ? AND step_id = ? AND attempt_no = ? AND status = 'RUNNING'
                    """, outcome.name(), success ? json.writeValueAsString(output) : null, code, message,
                    success ? null : retryable, claim.executionId(), claim.stepId(), claim.attemptNo());
            if (!success && !retry && workflow.active()) {
                failWorkflow(claim.executionId(), claim.cycleNo(), claim.stepId(), code, message);
            }
            return new ReportResult(true, outcome);
        });
    }

    /** Fails waiting steps; already-running attempts may finish without reopening the failed workflow. */
    private void failWorkflow(UUID executionId, int cycleNo, String failedStepId, String code, String message) {
        jdbc.update("""
                UPDATE workflow_execution SET status = 'FAILED', finished_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ?
                """, executionId);
        jdbc.update("""
                UPDATE workflow_cycle SET status = 'FAILED', finished_at = clock_timestamp(), failed_step_id = ?,
                    error_code = ?, error_message = ? WHERE workflow_id = ? AND cycle_no = ?
                """, failedStepId, code, message, executionId, cycleNo);
        jdbc.update("""
                UPDATE step_execution SET status = 'FAILED', eligible_at = NULL, error_code = 'WORKFLOW_FAILED',
                    error_message = 'Execution stopped because another step failed', updated_at = clock_timestamp()
                WHERE workflow_id = ? AND status IN ('PENDING','RETRYING')
                """, executionId);
    }

    /** Locks the workflow until transaction end; schedulers may skip busy workflows instead of waiting. */
    private Optional<WorkflowRow> workflow(UUID id, boolean skipLocked) {
        List<WorkflowRow> rows = jdbc.query("""
                SELECT status, current_cycle_no, inputs_json::text FROM workflow_execution WHERE id = ? FOR UPDATE
                """ + (skipLocked ? " SKIP LOCKED" : ""),
                (rs, row) -> new WorkflowRow(rs.getString(1), rs.getInt(2), json.readTree(rs.getString(3))), id);
        return rows.stream().findFirst();
    }

    private StepRow lockStep(UUID id, String stepId) {
        return jdbc.queryForObject("""
                SELECT status, last_attempt_no, worker_id,
                    action_type, parameters_json::text, max_attempts, backoff_initial_ms, backoff_max_ms
                FROM step_execution WHERE workflow_id = ? AND step_id = ? FOR UPDATE
                """, this::stepRow, id, stepId);
    }

    private StepRow stepRow(ResultSet rs, int row) throws SQLException {
        return new StepRow(rs.getString("status"), rs.getLong("last_attempt_no"), rs.getString("worker_id"),
                rs.getString("action_type"), json.readTree(rs.getString("parameters_json")),
                new RetryPolicy(rs.getInt("max_attempts"), rs.getLong("backoff_initial_ms"), rs.getLong("backoff_max_ms")));
    }

    /** Counts attempts in this cycle to enforce its retry budget, independently of the lifetime token. */
    private int attemptCount(UUID id, String stepId, int cycleNo) {
        return jdbc.queryForObject("SELECT count(*) FROM step_attempt WHERE workflow_id = ? AND step_id = ? AND cycle_no = ?",
                Integer.class, id, stepId, cycleNo);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }
}
