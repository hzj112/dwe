package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.demo.EchoHandler;
import com.athenahealth.dwe.persistence.WorkflowQueries;
import com.athenahealth.dwe.persistence.PageCursor;
import com.athenahealth.dwe.worker.*;
import com.athenahealth.dwe.support.IntegrationTestSupport;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class SequentialWorkflowIT extends IntegrationTestSupport {
    @Test
    void realWorkerExecutesSequentialStepsAndResultsSurviveWorkerRestart() {
        var id = submit("""
                [{"id":"a","actionType":"ECHO","parameters":{"message":"first"}},
                 {"id":"b","actionType":"DELAY","parameters":{"durationMs":30},"dependsOn":["a"]},
                 {"id":"c","actionType":"ECHO","dependsOn":["b"]}]
                """);
        try (var leases = new LeaseManager(store, properties)) {
            var worker = new WorkerRuntime(store, registry, properties, leases);
            worker.start();
            try {
                await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                    store.advance();
                    assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
                });
            } finally { worker.stop(); }
        }
        var restartedQueries = new WorkflowQueries(jdbc, json, new PageCursor(json));
        assertThat(restartedQueries.step(id, "c").output().get("dependencies").propertyNames()).containsExactly("b");
        assertThat(restartedQueries.step(id, "c").output().get("dependencies").get("b").get("durationMs").intValue()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM step_attempt WHERE workflow_id = ? AND status = 'COMPLETED'", Integer.class, id)).isEqualTo(3);
        try (var leases = new LeaseManager(store, properties)) {
            var worker = new WorkerRuntime(store, registry, properties, leases);
            worker.start();
            worker.stop();
        }
        assertThat(restartedQueries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void heartbeatsKeepSlowHandlerAliveBeyondOriginalLease() {
        var id = submit("[{\"id\":\"slow\",\"actionType\":\"DELAY\",\"parameters\":{\"durationMs\":4200}}]");
        try (var leases = new LeaseManager(store, properties)) {
            var worker = new WorkerRuntime(store, registry, properties, leases);
            worker.start();
            try {
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    store.advance();
                    assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
                });
            } finally { worker.stop(); }
        }
        assertThat(queries.step(id, "slow").lastAttemptNo()).isEqualTo(1);
    }

    @Test
    void invalidHandlerOutputIsRecordedAsPermanentFailureWithoutReexecution() {
        var invocations = new AtomicInteger();
        var badRegistry = new HandlerRegistry(List.of(new EchoHandler() {
            @Override
            public JsonNode execute(StepContext context) {
                invocations.incrementAndGet();
                return json.createArrayNode();
            }
        }));
        var id = submitOne();
        try (var leases = new LeaseManager(store, properties)) {
            var worker = new WorkerRuntime(store, badRegistry, properties, leases);
            worker.start();
            try {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    store.advance();
                    assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
                });
            } finally { worker.stop(); }
        }
        assertThat(queries.step(id, "a").error().code()).isEqualTo("INVALID_OUTPUT");
        assertThat(invocations).hasValue(1);
    }
}
