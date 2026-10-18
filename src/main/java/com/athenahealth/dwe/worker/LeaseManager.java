package com.athenahealth.dwe.worker;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.persistence.WorkflowStore;

/**
 * Renews worker claims and requests cancellation before local ownership becomes unsafe.
 * Heartbeats and deadline checks use separate threads so a busy handler cannot block either task.
 */
@Component
@Profile("worker")
public class LeaseManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LeaseManager.class);
    private final WorkflowStore store;
    private final EngineProperties properties;
    private final ScheduledExecutorService heartbeats;
    private final ScheduledExecutorService deadlines;
    private final Set<Handle> handles = ConcurrentHashMap.newKeySet();

    public LeaseManager(WorkflowStore store, EngineProperties properties) {
        this.store = store;
        this.properties = properties;
        heartbeats = Executors.newScheduledThreadPool(properties.workerConcurrency(),
                Thread.ofPlatform().daemon().name("lease-renew-", 0).factory());
        deadlines = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("lease-deadline-", 0).factory());
    }

    /** Starts heartbeat and local deadline monitoring for one claimed attempt. */
    public Handle track(StepClaim claim) {
        Handle handle = new Handle(claim);
        handles.add(handle);
        handle.start();
        return handle;
    }

    /** Per-attempt ownership monitor; cancellation is cooperative and cannot forcibly stop arbitrary code. */
    public final class Handle implements AutoCloseable {
        private final StepClaim claim;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile long deadline;
        private volatile Thread handlerThread;
        private boolean closed;
        private ScheduledFuture<?> renewal;
        private ScheduledFuture<?> expiry;

        private Handle(StepClaim claim) {
            this.claim = claim;
            // Start from the request's beginning so database and network delays consume the local window.
            deadline = claim.requestStartedNanos() + properties.ownershipWindowNanos();
        }

        private synchronized void start() {
            scheduleExpiry();
            scheduleRenewal(properties.heartbeatInterval().toMillis());
        }

        /** Associates the running handler thread with this lease so cancellation can interrupt it. */
        public void bindThread() {
            handlerThread = Thread.currentThread();
            if (isCancelled()) handlerThread.interrupt();
        }

        /** Also checks elapsed time directly, in case the scheduled deadline task has been delayed. */
        public boolean isCancelled() {
            if (System.nanoTime() - deadline >= 0) cancel("local ownership deadline elapsed");
            return cancelled.get();
        }

        /** Signals cancellation and interrupts the handler; the database still fences any later report. */
        public void cancel() {
            cancel("worker shutdown or local cancellation");
        }

        /** Logs the first ownership loss once; cancellation can never be reversed by a late heartbeat. */
        private void cancel(String reason) {
            if (cancelled.compareAndSet(false, true)) {
                log.info("Stopping local execution {} step {} attempt {}: {}",
                        claim.executionId(), claim.stepId(), claim.attemptNo(), reason);
            }
            Thread thread = handlerThread;
            if (thread != null) thread.interrupt();
        }

        private synchronized void scheduleExpiry() {
            if (expiry != null) expiry.cancel(false);
            if (!closed) expiry = deadlines.schedule(this::checkExpiry,
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }

        private synchronized void checkExpiry() {
            if (closed) return;
            if (System.nanoTime() - deadline >= 0) cancel("local ownership deadline elapsed");
            else scheduleExpiry();
        }

        private synchronized void scheduleRenewal(long delayMs) {
            if (!closed && !cancelled.get()) {
                renewal = heartbeats.schedule(this::renew, delayMs, TimeUnit.MILLISECONDS);
            }
        }

        /** Extends the local deadline only after confirmed renewal; errors never extend the old deadline. */
        private void renew() {
            if (isCancelled()) return;
            long requestStarted = System.nanoTime();
            long nextDelay = properties.heartbeatInterval().toMillis();
            try {
                boolean accepted = store.renew(claim);
                synchronized (this) {
                    if (closed) return;
                    // Confirmation received after the previous local deadline cannot revive ownership.
                    if (!accepted) { cancel("database rejected lease renewal"); return; }
                    if (isCancelled()) return;
                    deadline = requestStarted + properties.ownershipWindowNanos();
                    scheduleExpiry();
                }
            } catch (RuntimeException e) {
                log.warn("Lease renewal unavailable for execution {} step {}", claim.executionId(), claim.stepId());
                nextDelay = Math.min(1000, properties.heartbeatInterval().toMillis());
            }
            scheduleRenewal(nextDelay);
        }

        /** Stops local monitoring when the job exits; this does not release or change the database claim. */
        @Override
        public synchronized void close() {
            closed = true;
            if (renewal != null) renewal.cancel(false);
            if (expiry != null) expiry.cancel(false);
            handles.remove(this);
        }
    }

    /** Cancels remaining attempts and shuts down monitoring threads when Spring destroys this component. */
    @Override
    @PreDestroy
    public void close() {
        handles.forEach(handle -> { handle.cancel(); handle.close(); });
        heartbeats.shutdownNow();
        deadlines.shutdownNow();
    }
}
