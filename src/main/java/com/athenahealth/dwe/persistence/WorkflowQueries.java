package com.athenahealth.dwe.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.athenahealth.dwe.api.ExecutionDtos.*;
import com.athenahealth.dwe.domain.*;
import com.athenahealth.dwe.persistence.PageCursor.InvalidQueryException;
import com.athenahealth.dwe.persistence.PageCursor.Position;

import tools.jackson.databind.json.JsonMapper;

/** Reads persisted execution state and assembles API response objects without changing execution state. */
@Repository
public class WorkflowQueries {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final PageCursor cursors;

    public WorkflowQueries(JdbcTemplate jdbc, JsonMapper json, PageCursor cursors) {
        this.jdbc = jdbc;
        this.json = json;
        this.cursors = cursors;
    }

    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) { super(message); }
    }

    /** Lists lightweight summaries; count/eligibility fields are read in the same SQL snapshot as each row. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ExecutionSummary> executions(String status, int limit, String cursor) {
        validateLimit(limit);
        if (status != null) {
            try { WorkflowStatus.valueOf(status); }
            catch (IllegalArgumentException e) { throw new InvalidQueryException("status must be PENDING, RUNNING, COMPLETED, or FAILED"); }
        }
        String resource = "executions";
        String filter = status == null ? "" : status;
        Position after = cursors.decode(cursor, resource, filter);
        var args = new ArrayList<Object>();
        var where = new StringBuilder(" WHERE 1=1");
        if (status != null) { where.append(" AND w.status = ?"); args.add(status); }
        if (after != null) {
            UUID lastId;
            try { lastId = UUID.fromString(after.key()); }
            catch (RuntimeException e) { throw new InvalidQueryException("Invalid execution cursor position"); }
            if (after.time() == null || after.number() != 0) throw new InvalidQueryException("Invalid execution cursor position");
            where.append(" AND (w.created_at, w.id) < (?, ?)");
            args.add(Timestamp.from(after.time())); args.add(lastId);
        }
        args.add(limit + 1);
        var rows = jdbc.query("""
                SELECT w.id, w.status, w.current_cycle_no, w.created_at, w.first_started_at, w.finished_at,
                    (SELECT count(*) FROM step_execution s
                    WHERE s.workflow_id = w.id AND s.status = 'RUNNING') AS running_count,
                    EXISTS (SELECT 1 FROM step_attempt a WHERE a.workflow_id = w.id AND a.status = 'RUNNING') AS has_running_attempt
                FROM workflow_execution w
                """ + where + " ORDER BY w.created_at DESC, w.id DESC LIMIT ?", (rs, row) -> {
            WorkflowStatus state = WorkflowStatus.valueOf(rs.getString("status"));
            int running = rs.getInt("running_count");
            return new ExecutionSummary(rs.getObject("id", UUID.class), state, rs.getInt("current_cycle_no"),
                    instant(rs, "created_at"), instant(rs, "first_started_at"), instant(rs, "finished_at"),
                    running, state == WorkflowStatus.FAILED && running == 0 && !rs.getBoolean("has_running_attempt"));
        }, args.toArray());
        return page(rows, limit, resource, filter, row -> new Position(row.createdAt(), row.executionId().toString(), 0));
    }

    /** Earlier cycles remain visible after retry; an empty page differs from an unknown execution. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<CycleItem> cycles(UUID id, int limit, String cursor) {
        validateLimit(limit);
        String resource = "executions/" + id + "/cycles";
        Position after = cursors.decode(cursor, resource, "");
        if (after != null && (after.time() != null || after.key() != null
                || after.number() < 1 || after.number() > Integer.MAX_VALUE)) {
            throw new InvalidQueryException("Invalid cycle cursor position");
        }
        requireExecution(id);
        var args = new ArrayList<Object>();
        args.add(id);
        String boundary = "";
        if (after != null) { boundary = " AND cycle_no < ?"; args.add((int) after.number()); }
        args.add(limit + 1);
        var rows = jdbc.query("SELECT * FROM workflow_cycle WHERE workflow_id = ?" + boundary
                + " ORDER BY cycle_no DESC LIMIT ?", (rs, row) -> new CycleItem(rs.getInt("cycle_no"),
                WorkflowStatus.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("failed_step_id"), error(rs)), args.toArray());
        return page(rows, limit, resource, "", row -> new Position(null, null, row.cycleNo()));
    }

    /** Reads real claims only: steps blocked by workflow failure do not get synthetic attempt records. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AttemptItem> attempts(UUID id, String stepId, Integer cycleNo, int limit, String cursor) {
        validateLimit(limit);
        if (stepId != null && stepId.isBlank()) throw new InvalidQueryException("stepId must not be blank");
        if (cycleNo != null && cycleNo < 1) throw new InvalidQueryException("cycleNo must be positive");
        String resource = "executions/" + id + "/attempts";
        // JSON avoids ambiguous concatenations when step IDs contain punctuation or separators.
        String filter = json.writeValueAsString(List.of(stepId == null ? "" : stepId, cycleNo == null ? "" : cycleNo.toString()));
        Position after = cursors.decode(cursor, resource, filter);
        if (after != null && (after.time() == null || after.key() == null || after.key().isBlank() || after.number() < 1)) {
            throw new InvalidQueryException("Invalid attempt cursor position");
        }
        requireExecution(id);
        var args = new ArrayList<Object>();
        args.add(id);
        var where = new StringBuilder(" WHERE workflow_id = ?");
        if (stepId != null) { where.append(" AND step_id = ?"); args.add(stepId); }
        if (cycleNo != null) { where.append(" AND cycle_no = ?"); args.add(cycleNo); }
        if (after != null) {
            // Mixed sort directions require explicit comparisons, including all timestamp tie-breakers.
            where.append(" AND (started_at < ? OR (started_at = ? AND step_id > ?)"
                    + " OR (started_at = ? AND step_id = ? AND attempt_no < ?))");
            Timestamp time = Timestamp.from(after.time());
            args.add(time); args.add(time); args.add(after.key());
            args.add(time); args.add(after.key()); args.add(after.number());
        }
        args.add(limit + 1);
        var rows = jdbc.query("SELECT * FROM step_attempt" + where
                + " ORDER BY started_at DESC, step_id ASC, attempt_no DESC LIMIT ?", (rs, row) -> {
            AttemptError failure = rs.getString("error_code") == null ? null : new AttemptError(
                    rs.getString("error_code"), rs.getString("error_message"), rs.getObject("failure_retryable", Boolean.class));
            String result = rs.getString("result_json");
            return new AttemptItem(rs.getString("step_id"), rs.getInt("cycle_no"), rs.getLong("attempt_no"),
                    rs.getString("worker_id"), AttemptStatus.valueOf(rs.getString("status")), instant(rs, "started_at"),
                    instant(rs, "finished_at"), result == null ? null : json.readTree(result), failure);
        }, args.toArray());
        return page(rows, limit, resource, filter, row -> new Position(row.startedAt(), row.stepId(), row.attemptNo()));
    }

    private void requireExecution(UUID id) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM workflow_execution WHERE id = ?)", Boolean.class, id))) {
            throw new ResourceNotFoundException("Execution not found");
        }
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) throw new InvalidQueryException("limit must be between 1 and 200");
    }

    /** Fetching one extra row determines whether to issue a continuation without a separate count query. */
    private <T> Page<T> page(List<T> rows, int limit, String resource, String filter, Function<T, Position> position) {
        boolean more = rows.size() > limit;
        List<T> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        String next = more ? cursors.encode(resource, filter, position.apply(items.getLast())) : null;
        return new Page<>(items, next);
    }

    /** Reads workflow, cycle, and step summaries from one consistent database snapshot. */
    // Multiple reads must describe the same committed execution, even while workers finish steps.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExecutionDetail execution(UUID id) {
        var steps = jdbc.query("SELECT step_id, status FROM step_execution WHERE workflow_id = ? ORDER BY step_id",
                (rs, row) -> new StepSummary(rs.getString(1), StepStatus.valueOf(rs.getString(2))), id);
        var results = jdbc.query("""
                SELECT w.*, c.error_code, c.error_message FROM workflow_execution w JOIN workflow_cycle c
                  ON c.workflow_id = w.id AND c.cycle_no = w.current_cycle_no WHERE w.id = ?
                """, (rs, row) -> {
            WorkflowStatus status = WorkflowStatus.valueOf(rs.getString("status"));
            int running = (int) steps.stream().filter(s -> s.status() == StepStatus.RUNNING).count();
            return new ExecutionDetail(id, status, rs.getInt("current_cycle_no"), instant(rs, "created_at"),
                    instant(rs, "first_started_at"), instant(rs, "finished_at"), running,
                    status == WorkflowStatus.FAILED && running == 0,
                    json.readTree(rs.getString("definition_json")), json.readTree(rs.getString("inputs_json")),
                    error(rs), steps);
        }, id);
        if (results.isEmpty()) throw new ResourceNotFoundException("Execution not found");
        return results.getFirst();
    }

    /** Combines a step's dependencies, current-cycle attempt count, output, and error in one status view. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StepDetail step(UUID id, String stepId) {
        var dependencies = jdbc.query("""
                SELECT depends_on_step_id FROM step_dependency WHERE workflow_id = ? AND step_id = ?
                ORDER BY depends_on_step_id
                """, (rs, row) -> rs.getString(1), id, stepId);
        var results = jdbc.query("""
                SELECT s.*, (SELECT count(*) FROM step_attempt a WHERE a.workflow_id = s.workflow_id
                    AND a.step_id = s.step_id AND a.cycle_no = w.current_cycle_no) AS attempt_count
                FROM step_execution s JOIN workflow_execution w ON w.id = s.workflow_id
                WHERE s.workflow_id = ? AND s.step_id = ?
                """, (rs, row) -> new StepDetail(stepId, rs.getString("action_type"),
                json.readTree(rs.getString("parameters_json")), dependencies, StepStatus.valueOf(rs.getString("status")),
                instant(rs, "eligible_at"), new RetryPolicy(rs.getInt("max_attempts"),
                rs.getLong("backoff_initial_ms"), rs.getLong("backoff_max_ms")), rs.getInt("attempt_count"),
                rs.getLong("last_attempt_no"), rs.getString("output_json") == null ? null
                        : json.readTree(rs.getString("output_json")), error(rs)), id, stepId);
        if (results.isEmpty()) throw new ResourceNotFoundException("Step not found");
        return results.getFirst();
    }

    private static ResourceError error(ResultSet rs) throws SQLException {
        return rs.getString("error_code") == null ? null
                : new ResourceError(rs.getString("error_code"), rs.getString("error_message"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
