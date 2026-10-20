package com.athenahealth.dwe.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.handler.*;
import com.athenahealth.dwe.handler.demo.provider.*;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import com.athenahealth.dwe.support.WorkerHarness;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Uses existing submission/output APIs and actual PostgreSQL-backed worker runtimes. */
class ProviderRecordsDemoIT extends IntegrationTestSupport {
    @Test
    void registeredHandlersProducePersistedReportThroughExistingApis() throws Exception {
        var response = request("POST", "/api/v1/executions", fixture("normal"));
        assertThat(response.statusCode()).isEqualTo(201);
        var id = UUID.fromString(body(response).path("executionId").asString());
        try (var worker = new WorkerHarness(store, registry, properties)) {
            completed(id);
        }
        var output = body(request("GET", "/api/v1/executions/" + id + "/steps/publish-report", null)).path("output");
        assertExpected(output);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM step_attempt WHERE workflow_id = ?", Integer.class, id)).isEqualTo(5);
        // Reading persisted results after the worker closes performs no handler execution.
        assertThat(queries.step(id, "publish-report").output()).isEqualTo(output);
    }

    @Test
    void retryRecordsFailureAndBackoffThenProducesSameBusinessOutput() throws Exception {
        var sample = json.readTree(fixture("retry"));
        var id = submissions.submit(sample.path("definition"), sample.path("inputs")).executionId();
        try (var worker = new WorkerHarness(store, registry, properties)) {
            completed(id);
        }
        assertExpected(queries.step(id, "publish-report").output());
        var statuses = jdbc.queryForList("""
                SELECT status FROM step_attempt WHERE workflow_id = ? AND step_id = 'validate-records' ORDER BY attempt_no
                """, String.class, id);
        assertThat(statuses).containsExactly("FAILED", "COMPLETED");
        double backoff = jdbc.queryForObject("""
                SELECT extract(epoch FROM (b.started_at - a.finished_at)) FROM step_attempt a JOIN step_attempt b
                  ON a.workflow_id = b.workflow_id AND a.step_id = b.step_id AND b.attempt_no = 2
                WHERE a.workflow_id = ? AND a.step_id = 'validate-records' AND a.attempt_no = 1
                """, Double.class, id);
        assertThat(backoff).isGreaterThanOrEqualTo(1);
    }

    @Test
    void badBatchStructureFailsWithoutRetryAndWithoutAFinalReport() throws Exception {
        var sample = json.readTree(fixture("normal"));
        var id = submissions.submit(sample.path("definition"), json.readTree("{\"records\":[42]}")).executionId();
        try (var worker = new WorkerHarness(store, registry, properties)) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                store.advance();
                assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
            });
        }
        assertThat(jdbc.queryForList("SELECT error_code FROM step_attempt WHERE workflow_id = ?", String.class, id))
                .containsExactly("INVALID_PROVIDER_INPUT");
        assertThat(queries.step(id, "publish-report").output()).isNull();
    }

    @Test
    void businessBranchesOverlapAndSummaryWaitsForBoth() throws Exception {
        var entered = new CountDownLatch(2);
        var normalize = new GatedHandler(new NormalizeProviderRecordsHandler(), entered);
        var validate = new GatedHandler(new ValidateProviderRecordsHandler(), entered);
        var handlers = new HandlerRegistry(List.of(new LoadProviderRecordsHandler(), normalize, validate,
                new SummarizeProviderQualityHandler(), new FinalizeProviderReportHandler()));
        var sample = json.readTree(fixture("normal"));
        var id = submissions.submit(sample.path("definition"), sample.path("inputs")).executionId();
        try (var worker = new WorkerHarness(store, handlers, properties)) {
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    store.advance();
                    return entered.getCount() == 0;
                });
                assertThat(queries.step(id, "normalize-records").status()).isEqualTo(StepStatus.RUNNING);
                assertThat(queries.step(id, "validate-records").status()).isEqualTo(StepStatus.RUNNING);
                normalize.release.countDown();
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    store.advance();
                    assertThat(queries.step(id, "normalize-records").status()).isEqualTo(StepStatus.COMPLETED);
                });
                store.advance();
                assertThat(queries.step(id, "summarize-quality").status()).isEqualTo(StepStatus.PENDING);
                assertThat(queries.step(id, "summarize-quality").eligibleAt()).isNull();
                validate.release.countDown();
                completed(id);
                assertExpected(queries.step(id, "publish-report").output());
            } finally {
                normalize.release.countDown();
                validate.release.countDown();
            }
        }
    }

    private void completed(UUID id) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            store.advance();
            assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
        });
    }

    private void assertExpected(JsonNode output) throws Exception {
        assertThat(output.path("report").path("summary")).isEqualTo(json.readTree(Files.readString(
                Path.of("demo/provider-quality/expected-summary.json"))));
        assertThat(output.path("cleanedRecords").size()).isEqualTo(4);
        assertThat(output.path("report").path("findings").size()).isEqualTo(3);
    }

    private String fixture(String mode) throws Exception {
        return Files.readString(Path.of("demo/provider-quality/" + mode + ".json"));
    }

    private static final class GatedHandler implements StepHandler {
        private final StepHandler delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);

        private GatedHandler(StepHandler delegate, CountDownLatch entered) {
            this.delegate = delegate;
            this.entered = entered;
        }

        @Override
        public String actionType() { return delegate.actionType(); }

        @Override
        public void validateParameters(JsonNode parameters) throws StepValidationException { delegate.validateParameters(parameters); }

        @Override
        public JsonNode execute(StepContext context) throws StepExecutionException, InterruptedException {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test branch was not released");
            return delegate.execute(context);
        }
    }
}
