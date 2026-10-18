package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.domain.RetryPolicy;
import com.athenahealth.dwe.domain.StepDefinition;
import com.athenahealth.dwe.domain.WorkflowDefinition;
import com.athenahealth.dwe.service.WorkflowValidationService.ValidatedSubmission;
import com.athenahealth.dwe.support.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

class MigrationIT extends IntegrationTestSupport {
    @Autowired Flyway flyway;

    @Test
    void migrationIsRestartSafeAndExistingExecutionIsRetained() {
        UUID id = submitOne();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(queries.execution(id).executionId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseRejectsInvalidJsonOwnershipAndDependencyReferences() {
        UUID id = submitOne();
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_execution SET inputs_json = '[]'::jsonb WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE step_execution SET status = 'RUNNING' WHERE workflow_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO step_dependency VALUES (?, 'a', 'missing')", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO step_attempt(workflow_id, step_id, attempt_no, cycle_no, worker_id, status, finished_at)
                VALUES (?, 'a', 1, 1, 'w', 'FAILED', clock_timestamp())
                """, id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deferredCurrentCycleForeignKeyRejectsPartialSubmission() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO workflow_execution(id, definition_json, inputs_json, status, current_cycle_no)
                VALUES (?, '{}'::jsonb, '{}'::jsonb, 'PENDING', 1)
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_execution", Integer.class)).isZero();
    }

    @Test
    void failedSubmissionTransactionDoesNotLeavePartialExecutionRecords() {
        var invalid = new ValidatedSubmission(json.createObjectNode(), json.createObjectNode(),
                new WorkflowDefinition(List.of(new StepDefinition("a", "ECHO", json.createObjectNode(),
                        List.of("missing"), new RetryPolicy(3, 1000, 30000)))));
        assertThatThrownBy(() -> store.submit(invalid)).isInstanceOf(DataIntegrityViolationException.class);
        for (String table : List.of("workflow_execution", "workflow_cycle", "step_execution", "step_dependency")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
    }
}
