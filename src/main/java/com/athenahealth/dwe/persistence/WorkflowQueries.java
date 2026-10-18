package com.athenahealth.dwe.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.athenahealth.dwe.api.ExecutionDtos.*;
import com.athenahealth.dwe.domain.*;

import tools.jackson.databind.json.JsonMapper;

/** Reads persisted execution state and assembles API response objects without changing execution state. */
@Repository
public class WorkflowQueries {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public WorkflowQueries(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) { super(message); }
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
