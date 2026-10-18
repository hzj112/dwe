package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.demo.EchoHandler;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import com.athenahealth.dwe.worker.LeaseManager;
import com.athenahealth.dwe.worker.WorkerRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Exercises real worker runtimes against PostgreSQL; gates make concurrency observable without timing guesses. */
class ParallelWorkflowIT extends IntegrationTestSupport {
    /** A single worker must run both branches together and hold the join until both outputs are durable. */
    @Test
    void parallelBranchesOverlapAndJoinWaitsForBothOutputs() {
        var id = submit("""
                [{"id":"start","actionType":"ECHO"},
                 {"id":"b","actionType":"ECHO","dependsOn":["start"]},
                 {"id":"c","actionType":"ECHO","dependsOn":["start"]},
                 {"id":"join","actionType":"ECHO","dependsOn":["b","c"]},
                 {"id":"finish","actionType":"ECHO","dependsOn":["join"]}]
                """);
        var handler = new GatedEchoHandler("b", "c");
        try (var worker = new TestWorker(handler)) {
            try {
                // Both branches must enter their handlers before either is allowed to finish.
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    store.advance();
                    assertThat(handler.calls("b")).isEqualTo(1);
                    assertThat(handler.calls("c")).isEqualTo(1);
                });
                assertThat(queries.step(id, "b").status()).isEqualTo(StepStatus.RUNNING);
                assertThat(queries.step(id, "c").status()).isEqualTo(StepStatus.RUNNING);

                handler.release("b");
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(queries.step(id, "b").status()).isEqualTo(StepStatus.COMPLETED));
                store.advance();
                assertThat(queries.step(id, "join").status()).isEqualTo(StepStatus.PENDING);
                assertThat(queries.step(id, "join").eligibleAt()).isNull();
                assertThat(handler.calls("join")).isZero();
                assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.RUNNING);

                handler.release("c");
                awaitCompleted(id);
                var dependencies = queries.step(id, "join").output().get("dependencies");
                assertThat(dependencies.propertyNames()).containsExactlyInAnyOrder("b", "c");
                assertThat(dependencies.get("b").get("dependencies").propertyNames()).containsExactly("start");
                assertThat(dependencies.get("c").get("dependencies").propertyNames()).containsExactly("start");
                assertThat(queries.step(id, "finish").output().get("dependencies").propertyNames())
                        .containsExactly("join");
                assertOneCompletedAttemptPerStep(id, 5);
            } finally {
                // Always release handlers before draining workers, including when an assertion fails.
                handler.releaseAll();
            }
        }
    }

    /** Holding the winning handler open gives the other worker time to compete for the same step. */
    @Test
    void twoWorkersCompetingForOneStepInvokeItOnlyOnce() {
        var id = submitOne();
        var handler = new GatedEchoHandler("a");
        store.advance();
        try (var first = new TestWorker(handler); var second = new TestWorker(handler)) {
            try {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(handler.calls("a")).isEqualTo(1));
                // Keep the winner running while both worker pollers continue looking for work.
                await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(handler.calls("a")).isEqualTo(1);
                    assertThat(attemptCount(id)).isEqualTo(1);
                });
                handler.releaseAll();
                awaitCompleted(id);
                assertOneCompletedAttemptPerStep(id, 1);
            } finally {
                handler.releaseAll();
            }
        }
    }

    /** Fills one worker first, then proves a second worker adds capacity without either overclaiming. */
    @Test
    void twoWorkersRespectTheirCapacityAndLeaveExcessWorkUnclaimed() {
        var id = submit("""
                [{"id":"a","actionType":"ECHO"},{"id":"b","actionType":"ECHO"},
                 {"id":"c","actionType":"ECHO"},{"id":"d","actionType":"ECHO"},
                 {"id":"e","actionType":"ECHO"}]
                """);
        var handler = new GatedEchoHandler("a", "b", "c", "d", "e");
        store.advance();
        try (var first = new TestWorker(handler)) {
            try {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(handler.totalCalls()).isEqualTo(2);
                    assertThat(attemptCount(id)).isEqualTo(2);
                });
                try (var second = new TestWorker(handler)) {
                    try {
                        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                                assertThat(handler.totalCalls()).isEqualTo(4));
                        // One worker is already full; the other must own the next two attempts.
                        var counts = jdbc.queryForList("""
                                SELECT count(*) AS claims FROM step_attempt WHERE workflow_id = ?
                                GROUP BY worker_id ORDER BY worker_id
                                """, Long.class, id);
                        assertThat(counts).containsExactly(2L, 2L);
                        assertThat(attemptCount(id)).isEqualTo(4);
                        assertThat(queries.execution(id).runningStepCount()).isEqualTo(4);
                        assertThat(jdbc.queryForObject("""
                                SELECT count(*) FROM step_execution WHERE workflow_id = ?
                                  AND status = 'PENDING' AND last_attempt_no = 0
                                """, Integer.class, id)).isEqualTo(1);
                        handler.releaseAll();
                        awaitCompleted(id);
                        assertOneCompletedAttemptPerStep(id, 5);
                    } finally {
                        handler.releaseAll();
                    }
                }
            } finally {
                handler.releaseAll();
            }
        }
    }

    /** The test profile disables scheduled coordination, so each bounded wait advances it explicitly. */
    private void awaitCompleted(UUID id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            store.advance();
            assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
        });
    }

    private int attemptCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM step_attempt WHERE workflow_id = ?", Integer.class, id);
    }

    /** Checks both durable outcomes and the absence of extra attempts, rather than only final workflow status. */
    private void assertOneCompletedAttemptPerStep(UUID id, int expectedSteps) {
        assertThat(attemptCount(id)).isEqualTo(expectedSteps);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM step_attempt WHERE workflow_id = ? AND status = 'COMPLETED' AND attempt_no = 1
                """, Integer.class, id)).isEqualTo(expectedSteps);
    }

    /** Owns a worker and its independent heartbeat scheduler, with cleanup on every test exit path. */
    private final class TestWorker implements AutoCloseable {
        private final LeaseManager leases = new LeaseManager(store, properties);
        private final WorkerRuntime runtime;

        private TestWorker(EchoHandler handler) {
            runtime = new WorkerRuntime(store, new HandlerRegistry(List.of(handler)), properties, leases);
            runtime.start();
        }

        @Override
        public void close() {
            try { runtime.stop(); }
            finally { leases.close(); }
        }
    }

    /** Test-only ECHO implementation; named steps block on bounded, individually releasable gates. */
    private static final class GatedEchoHandler extends EchoHandler {
        private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        private GatedEchoHandler(String... blockedSteps) {
            for (String step : blockedSteps) gates.put(step, new CountDownLatch(1));
        }

        @Override
        public JsonNode execute(StepContext context) throws InterruptedException {
            calls.computeIfAbsent(context.stepId(), ignored -> new AtomicInteger()).incrementAndGet();
            CountDownLatch gate = gates.get(context.stepId());
            if (gate != null && !gate.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Test did not release step " + context.stepId());
            }
            return super.execute(context);
        }

        private int calls(String step) { return calls.getOrDefault(step, new AtomicInteger()).get(); }
        private int totalCalls() { return calls.values().stream().mapToInt(AtomicInteger::get).sum(); }
        private void release(String step) { gates.get(step).countDown(); }
        private void releaseAll() { gates.values().forEach(CountDownLatch::countDown); }
    }
}
