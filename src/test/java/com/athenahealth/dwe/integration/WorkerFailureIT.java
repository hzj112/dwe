package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.demo.EchoHandler;
import com.athenahealth.dwe.persistence.WorkflowStore;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import com.athenahealth.dwe.worker.LeaseManager;
import com.athenahealth.dwe.worker.WorkerRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Injects specific lost responses around real PostgreSQL writes and runs the actual worker/lease executors. */
class WorkerFailureIT extends IntegrationTestSupport {
    @Autowired private PlatformTransactionManager transactions;

    /** The database commits a claim, but the caller sees an exception; ownership confirmation must reuse it. */
    @Test
    void lostClaimCommitResponseIsReconciledWithoutAnotherAttempt() {
        var id = submitOne();
        store.advance();
        var faultStore = new WorkflowStore(jdbc, json, properties, new LostCommitResponse());
        var claim = faultStore.claimNext("owner").orElseThrow();
        assertThat(store.owns(claim)).isTrue();
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(store.complete(claim, json.createObjectNode()).accepted()).isTrue();
        awaitCompleted(id);
    }

    /** If confirmation also fails, no handler starts; recovery later makes the durable claim executable again. */
    @Test
    void unconfirmedClaimDoesNotExecuteAndCanBeRecovered() {
        var id = submitOne();
        store.advance();
        var confirmations = new AtomicInteger();
        var faultStore = new WorkflowStore(jdbc, json, properties, new LostCommitResponse()) {
            @Override
            public boolean owns(StepClaim claim) {
                confirmations.incrementAndGet();
                throw new DataAccessResourceFailureException("Ownership confirmation unavailable");
            }
        };
        var handler = new HeldEchoHandler(false);
        handler.release.countDown();
        try (var worker = new TestWorker(faultStore, properties, handler)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> confirmations.get() == 1);
            assertThat(handler.calls("a")).isZero();
            assertThat(attempts(id)).isEqualTo(1);
        }
        jdbc.update("UPDATE step_execution SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE workflow_id = ?", id);
        assertThat(store.recoverExpired()).isEqualTo(1);
        jdbc.update("UPDATE step_execution SET eligible_at = clock_timestamp() WHERE workflow_id = ?", id);
        try (var replacement = new TestWorker(store, properties, handler)) { awaitCompleted(id); }
        assertThat(handler.calls("a")).isEqualTo(1);
        assertThat(attempts(id)).isEqualTo(2);
    }

    /** A lost acknowledgement is reconciled from history without rerunning the handler or rewriting completion. */
    @Test
    void lostOutcomeResponseDoesNotReinvokeTheHandler() {
        var id = submitOne();
        store.advance();
        var reports = new AtomicInteger();
        var reconciliations = new AtomicInteger();
        WorkflowStore faultStore = mock(WorkflowStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            var result = store.complete(invocation.getArgument(0), invocation.getArgument(1));
            if (reports.incrementAndGet() == 1) throw new DataAccessResourceFailureException("Committed response lost");
            return result;
        }).when(faultStore).complete(any(), any());
        doAnswer(invocation -> {
            reconciliations.incrementAndGet();
            return store.recordedOutcome(invocation.getArgument(0));
        }).when(faultStore).recordedOutcome(any());
        var handler = new HeldEchoHandler(false);
        handler.release.countDown();
        try (var worker = new TestWorker(faultStore, properties, handler)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> reconciliations.get() == 1);
            awaitCompleted(id);
        }
        assertThat(handler.calls("a")).isEqualTo(1);
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(reports).hasValue(1);
    }

    /** If the first write never committed, reconciliation finds RUNNING and only persistence is retried. */
    @Test
    void outcomeFailureBeforeCommitRetriesPersistenceWithoutReexecution() {
        var id = submitOne();
        store.advance();
        var reports = new AtomicInteger();
        WorkflowStore faultStore = mock(WorkflowStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            if (reports.incrementAndGet() == 1) throw new DataAccessResourceFailureException("Write did not commit");
            return store.complete(invocation.getArgument(0), invocation.getArgument(1));
        }).when(faultStore).complete(any(), any());
        var handler = new HeldEchoHandler(false);
        handler.release.countDown();
        try (var worker = new TestWorker(faultStore, properties, handler)) {
            awaitCompleted(id);
        }
        assertThat(reports).hasValue(2);
        assertThat(handler.calls("a")).isEqualTo(1);
        assertThat(attempts(id)).isEqualTo(1);
    }

    /** Confirmed heartbeats resume after brief injected outages, retaining the same database attempt. */
    @Test
    void briefRenewalFailureDoesNotRequireReplacementExecution() throws Exception {
        var id = submitOne();
        store.advance();
        var calls = new AtomicInteger();
        var confirmations = new AtomicInteger();
        WorkflowStore faultStore = mock(WorkflowStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) throw new DataAccessResourceFailureException("Brief outage");
            boolean result = store.renew(invocation.getArgument(0));
            if (result) confirmations.incrementAndGet();
            return result;
        }).when(faultStore).renew(any());
        var handler = new HeldEchoHandler(false);
        try (var worker = new TestWorker(faultStore, properties, handler)) {
            try {
                assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(5)).until(() -> confirmations.get() >= 2);
                handler.release.countDown();
                awaitCompleted(id);
            } finally { handler.release.countDown(); }
        }
        assertThat(attempts(id)).isEqualTo(1);
    }

    /** An uncooperative handler keeps its slot after ownership loss and cannot report a late result. */
    @Test
    void ignoredInterruptionRetainsCapacityUntilHandlerActuallyExits() throws Exception {
        var id = submitRoots();
        WorkflowStore faultStore = mock(WorkflowStore.class, delegatesTo(store));
        doAnswer(invocation -> { throw new DataAccessResourceFailureException("Outage"); })
                .when(faultStore).renew(any());
        var handler = new HeldEchoHandler(true);
        try (var worker = new TestWorker(faultStore, runtimeProperties(properties.shutdownGrace()), handler)) {
            try {
                assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(6)).until(() -> handler.interruptions.get() > 0);
                await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                    assertThat(attempts(id)).isEqualTo(1);
                    assertThat(queries.step(id, "b").lastAttemptNo()).isZero();
                });
                handler.release.countDown();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(queries.step(id, "b").status()).isEqualTo(StepStatus.COMPLETED));
                assertThat(queries.step(id, "a").status()).isEqualTo(StepStatus.RUNNING);
                assertThat(queries.step(id, "a").output()).isNull();
            } finally { handler.release.countDown(); }
        }
    }

    /** Shutdown stops new claims but continues renewals and outcome reporting during the drain window. */
    @Test
    void gracefulShutdownRenewsDuringDrainAndLeavesNewWorkUnclaimed() throws Exception {
        var id = submitRoots();
        var handler = new HeldEchoHandler(false);
        try (var worker = new TestWorker(store, runtimeProperties(properties.shutdownGrace()), handler);
             var pool = Executors.newSingleThreadExecutor()) {
            try {
                assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();
                var before = lease(id);
                var stopped = pool.submit(() -> { worker.runtime.stop(); });
                await().atMost(Duration.ofSeconds(2)).until(() -> !worker.runtime.isRunning());
                await().atMost(Duration.ofSeconds(3)).until(() -> lease(id).after(before));
                handler.release.countDown();
                stopped.get(10, TimeUnit.SECONDS);
                assertThat(queries.step(id, "a").status()).isEqualTo(StepStatus.COMPLETED);
                assertThat(queries.step(id, "b").lastAttemptNo()).isZero();
                assertThat(attempts(id)).isEqualTo(1);
            } finally { handler.release.countDown(); }
        }
    }

    /** At the grace deadline, remaining work is cancelled and renewals stop even if its handler ignores interruption. */
    @Test
    void shutdownDeadlineStopsRenewingUnfinishedWork() throws Exception {
        var id = submitRoots();
        var handler = new HeldEchoHandler(true);
        try (var worker = new TestWorker(store, runtimeProperties(Duration.ofMillis(200)), handler)) {
            try {
                assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();
                worker.runtime.stop();
                await().atMost(Duration.ofSeconds(2)).until(() -> handler.interruptions.get() > 0);
                var after = lease(id);
                await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                    assertThat(lease(id)).isEqualTo(after);
                    assertThat(attempts(id)).isEqualTo(1);
                });
                handler.release.countDown();
                assertThat(handler.returned.await(2, TimeUnit.SECONDS)).isTrue();
            } finally { handler.release.countDown(); }
        }
        assertThat(queries.step(id, "a").output()).isNull();
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM step_attempt WHERE workflow_id = ?", Integer.class, id);
    }

    private java.sql.Timestamp lease(UUID id) {
        return jdbc.queryForObject("SELECT lease_expires_at FROM step_execution WHERE workflow_id = ? AND step_id = 'a'",
                java.sql.Timestamp.class, id);
    }

    private void awaitCompleted(UUID id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            store.advance();
            assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
        });
    }

    private UUID submitRoots() {
        var id = submit("[{\"id\":\"a\",\"actionType\":\"ECHO\"},{\"id\":\"b\",\"actionType\":\"ECHO\"}]");
        store.advance();
        jdbc.update("""
                UPDATE step_execution SET eligible_at = clock_timestamp() - CASE step_id
                    WHEN 'a' THEN interval '2 seconds' ELSE interval '1 second' END WHERE workflow_id = ?
                """, id);
        return id;
    }

    /** One local slot makes overclaiming visible; all ownership timing remains the integration-test default. */
    private EngineProperties runtimeProperties(Duration grace) {
        return new EngineProperties(properties.leaseDuration(), properties.heartbeatInterval(), properties.pollInterval(),
                properties.coordinatorInterval(), properties.recoveryInterval(), properties.operationTimeout(),
                properties.safetyMargin(), grace, 1, properties.defaultMaxAttempts(),
                properties.defaultBackoffInitialMs(), properties.defaultBackoffMaxMs());
    }

    private final class TestWorker implements AutoCloseable {
        private final LeaseManager leases;
        private final WorkerRuntime runtime;

        private TestWorker(WorkflowStore actual, EngineProperties settings, EchoHandler handler) {
            leases = new LeaseManager(actual, settings);
            runtime = new WorkerRuntime(actual, new HandlerRegistry(List.of(handler)), settings, leases);
            runtime.start();
        }

        @Override
        public void close() {
            try { runtime.stop(); }
            finally { leases.close(); }
        }
    }

    /** Commits for real, then loses the first acknowledgement at the transaction-manager boundary. */
    private final class LostCommitResponse implements PlatformTransactionManager {
        private final AtomicBoolean lose = new AtomicBoolean(true);

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) { return transactions.getTransaction(definition); }
        @Override
        public void commit(TransactionStatus status) {
            transactions.commit(status);
            if (lose.compareAndSet(true, false)) throw new TransactionSystemException("Commit response lost");
        }
        @Override
        public void rollback(TransactionStatus status) { transactions.rollback(status); }
    }

    /** A bounded gate models business code that either honors or deliberately ignores interruption. */
    private static final class HeldEchoHandler extends EchoHandler {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch returned = new CountDownLatch(1);
        private final AtomicInteger interruptions = new AtomicInteger();
        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
        private final boolean ignoreInterruption;

        private HeldEchoHandler(boolean ignoreInterruption) { this.ignoreInterruption = ignoreInterruption; }

        @Override
        public JsonNode execute(StepContext context) throws InterruptedException {
            invocations.computeIfAbsent(context.stepId(), ignored -> new AtomicInteger()).incrementAndGet();
            if (!context.stepId().equals("a")) return super.execute(context);
            entered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            try {
                while (release.getCount() > 0) {
                    try {
                        if (!release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                            throw new AssertionError("Test did not release handler");
                        }
                    } catch (InterruptedException e) {
                        interruptions.incrementAndGet();
                        if (!ignoreInterruption) throw e;
                    }
                }
                // The uncooperative variant intentionally omits cancellation checks before returning a late result.
                return ignoreInterruption ? JsonNodeFactory.instance.objectNode().put("late", true) : super.execute(context);
            } finally { returned.countDown(); }
        }

        private int calls(String step) { return invocations.getOrDefault(step, new AtomicInteger()).get(); }
    }
}
