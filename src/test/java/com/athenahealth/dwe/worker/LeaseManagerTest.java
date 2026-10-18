package com.athenahealth.dwe.worker;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.persistence.WorkflowStore;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Injects renewal responses to test local monotonic deadlines independently of database lease decisions. */
class LeaseManagerTest {
    private final WorkflowStore store = mock(WorkflowStore.class);
    private final EngineProperties properties = new EngineProperties(
            Duration.ofMillis(1500), Duration.ofMillis(50), Duration.ofMillis(20),
            Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMillis(100),
            Duration.ofMillis(300), Duration.ofMillis(200), 2, 3, 100, 1000);

    /** Brief outages are tolerated if a later confirmed renewal arrives before the old local deadline. */
    @Test
    void transientFailuresCanRecoverWithinTheConfirmedWindow() {
        var calls = new AtomicInteger();
        when(store.renew(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) throw new DataAccessResourceFailureException("temporary outage");
            return true;
        });
        var claim = claim("a");
        try (var manager = new LeaseManager(store, properties); var handle = manager.track(claim)) {
            await().atMost(Duration.ofSeconds(3)).until(() -> calls.get() >= 3);
            // Successful heartbeats must carry the attempt safely beyond its initial ownership window.
            await().atMost(Duration.ofSeconds(3)).until(() ->
                    System.nanoTime() - claim.requestStartedNanos() > properties.ownershipWindowNanos());
            assertThat(handle.isCancelled()).isFalse();
        }
    }

    /** Repeated connection errors cannot turn an unconfirmed renewal into additional ownership time. */
    @Test
    void unconfirmedRenewalsExpireAndStopFurtherRequests() {
        var calls = new AtomicInteger();
        when(store.renew(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            throw new DataAccessResourceFailureException("outage");
        });
        try (var manager = new LeaseManager(store, properties); var handle = manager.track(claim("a"))) {
            await().atMost(Duration.ofSeconds(3)).until(handle::isCancelled);
            assertThat(calls.get()).isPositive();
            int atCancellation = calls.get();
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(calls).hasValue(atCancellation));
        }
    }

    /** An explicit database rejection means immediate loss of ownership, without waiting for local expiry. */
    @Test
    void rejectedRenewalCancelsTheAttempt() {
        var calls = new AtomicInteger();
        when(store.renew(any())).thenAnswer(invocation -> { calls.incrementAndGet(); return false; });
        try (var manager = new LeaseManager(store, properties); var handle = manager.track(claim("a"))) {
            await().atMost(Duration.ofSeconds(1)).until(handle::isCancelled);
            assertThat(calls).hasValue(1);
        }
    }

    /** A response that arrives after ownership was given up must not revive the local attempt. */
    @Test
    void lateSuccessfulResponseCannotReverseCancellation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(store.renew(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("Test did not release renewal");
            returned.countDown();
            return true;
        });
        try (var manager = new LeaseManager(store, properties); var handle = manager.track(claim("a"))) {
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(3)).until(handle::isCancelled);
                release.countDown();
                assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue();
                await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                    assertThat(handle.isCancelled()).isTrue();
                    assertThat(calls).hasValue(1);
                });
            } finally { release.countDown(); }
        }
    }

    /** A blocked heartbeat cannot starve another attempt's renewal or the separate expiry scheduler. */
    @Test
    void blockedRenewalDoesNotBlockOtherHeartbeatsOrDeadlineCancellation() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var healthyCalls = new AtomicInteger();
        when(store.renew(any())).thenAnswer(invocation -> {
            StepClaim claim = invocation.getArgument(0);
            if (claim.stepId().equals("blocked")) {
                entered.countDown();
                if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("Test did not release renewal");
            } else healthyCalls.incrementAndGet();
            return true;
        });
        try (var manager = new LeaseManager(store, properties);
             var blocked = manager.track(claim("blocked")); var healthy = manager.track(claim("healthy"))) {
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(3)).until(blocked::isCancelled);
                assertThat(healthyCalls.get()).isGreaterThan(2);
                assertThat(healthy.isCancelled()).isFalse();
            } finally { release.countDown(); }
        }
    }

    private StepClaim claim(String step) {
        var object = JsonNodeFactory.instance.objectNode();
        return new StepClaim(UUID.randomUUID(), step, "ECHO", 1, 1, "owner",
                object, object, Map.of(), System.nanoTime());
    }
}
