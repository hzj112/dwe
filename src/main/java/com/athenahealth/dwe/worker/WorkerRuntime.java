package com.athenahealth.dwe.worker;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.handler.*;
import com.athenahealth.dwe.persistence.WorkflowStore;

import tools.jackson.databind.JsonNode;

/**
 * Worker process lifecycle: polls for claims, runs handlers within a fixed capacity, and reports outcomes.
 * Spring starts and stops it through SmartLifecycle; the worker profile does not expose an HTTP server.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "engine.worker-enabled", matchIfMissing = true)
public class WorkerRuntime implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);
    private final String workerId = UUID.randomUUID().toString();
    private final WorkflowStore store;
    private final HandlerRegistry registry;
    private final EngineProperties properties;
    private final LeaseManager leases;
    private final Semaphore capacity;
    private final Set<Job> jobs = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("worker-poll-", 0).factory());
    private final ExecutorService handlers;
    private volatile boolean running;

    public WorkerRuntime(WorkflowStore store, HandlerRegistry registry, EngineProperties properties, LeaseManager leases) {
        this.store = store;
        this.registry = registry;
        this.properties = properties;
        this.leases = leases;
        capacity = new Semaphore(properties.workerConcurrency());
        handlers = Executors.newFixedThreadPool(properties.workerConcurrency(),
                Thread.ofPlatform().daemon().name("step-handler-", 0).factory());
    }

    /** Starts periodic polling; the poller's non-daemon thread keeps the worker process alive. */
    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        poller.scheduleWithFixedDelay(this::poll, 0, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        log.info("Worker {} started with {} execution slots", workerId, properties.workerConcurrency());
    }

    /** Reserves capacity before claiming so leases are not spent waiting behind other handler jobs. */
    private void poll() {
        try {
            while (running && capacity.tryAcquire()) {
                boolean assigned = false;
                try {
                    var claim = store.claimNext(workerId);
                    if (claim.isEmpty()) return;
                    Job job = new Job(claim.get(), leases.track(claim.get()));
                    jobs.add(job);
                    try {
                        handlers.execute(job);
                        assigned = true;
                    } catch (RejectedExecutionException e) {
                        jobs.remove(job);
                        job.lease.close();
                        throw e;
                    }
                } finally {
                    if (!assigned) capacity.release();
                }
            }
        } catch (RuntimeException e) {
            if (running) log.warn("Worker polling failed; will retry", e);
        }
    }

    /** One claimed attempt and its lease tracking, retained until execution and reporting have exited. */
    private final class Job implements Runnable {
        private final StepClaim claim;
        private final LeaseManager.Handle lease;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Job(StepClaim claim, LeaseManager.Handle lease) { this.claim = claim; this.lease = lease; }

        /** Runs the handler once and translates its output or exception into a completion/failure report. */
        @Override
        public void run() {
            lease.bindThread();
            try {
                var context = new StepContext(claim, lease::isCancelled);
                context.checkCancelled();
                JsonNode output;
                try {
                    output = registry.get(claim.actionType()).execute(context);
                    if (output == null || !output.isObject()) {
                        throw new StepExecutionException("INVALID_OUTPUT", "Handler output must be a JSON object", false);
                    }
                } catch (StepExecutionException e) {
                    report(null, e.code(), e.getMessage(), e.retryable());
                    return;
                } catch (InterruptedException e) {
                    if (!lease.isCancelled()) {
                        Thread.interrupted();
                        report(null, "HANDLER_INTERRUPTED", "Handler execution interrupted", true);
                    }
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("Handler failed for execution {} step {}", claim.executionId(), claim.stepId(), e);
                    report(null, "HANDLER_ERROR", "Handler raised an unexpected exception", true);
                    return;
                }
                report(output, null, null, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finish();
            }
        }

        /** Retries uncertain database writes while local ownership permits; never re-executes the handler. */
        private void report(JsonNode output, String code, String message, boolean retryable) {
            // Retry only the persistence operation after an uncertain response, never the handler invocation.
            while (!lease.isCancelled()) {
                try {
                    if (output == null) store.fail(claim, code, message, retryable);
                    else store.complete(claim, output);
                    return;
                } catch (RuntimeException e) {
                    // A committed completion can make the next heartbeat reject renewal. Read its durable
                    // outcome immediately; reconciliation is read-only and does not revive local ownership.
                    try {
                        var recorded = store.recordedOutcome(claim);
                        if (recorded.isPresent()) {
                            log.info("Reconciled execution {} step {} attempt {} as {}", claim.executionId(),
                                    claim.stepId(), claim.attemptNo(), recorded.get().status());
                            return;
                        }
                    } catch (RuntimeException unavailable) {
                        log.debug("Outcome reconciliation unavailable for execution {} step {}", claim.executionId(), claim.stepId());
                    }
                    log.warn("Outcome write unavailable for execution {} step {}; retrying within lease",
                            claim.executionId(), claim.stepId());
                    try { Thread.sleep(200); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        }

        /** Releases lease tracking and capacity exactly once, including cleanup of jobs that never ran. */
        private void finish() {
            if (finished.compareAndSet(false, true)) {
                lease.close();
                jobs.remove(this);
                capacity.release();
            }
        }
    }

    /** Stops claiming, allows a grace period for jobs to finish, then requests cancellation of remaining jobs. */
    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false;
        poller.shutdownNow();
        handlers.shutdown();
        try {
            if (handlers.awaitTermination(properties.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        jobs.forEach(job -> job.lease.cancel());
        handlers.shutdownNow().forEach(task -> ((Job) task).finish());
    }

    @Override
    public boolean isRunning() { return running; }
}
