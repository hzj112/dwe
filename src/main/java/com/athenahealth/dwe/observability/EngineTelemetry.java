package com.athenahealth.dwe.observability;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Best-effort process observations; identifiers belong in logs, never in metric labels. */
@Component
public class EngineTelemetry {
    private static final Logger log = LoggerFactory.getLogger(EngineTelemetry.class);
    private static final EngineTelemetry DISABLED = new EngineTelemetry(null);
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicLong> loopSuccess = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> loopActivity = new ConcurrentHashMap<>();

    public EngineTelemetry(MeterRegistry registry) { this.registry = registry; }

    /** Keeps directly constructed worker/test instances independent of a monitoring backend. */
    public static EngineTelemetry disabled() { return DISABLED; }

    public void worker(IntSupplier occupied, int capacity) {
        safely(() -> {
            Gauge.builder("dwe.worker.slots.occupied", occupied, IntSupplier::getAsInt)
                    .strongReference(true).register(registry);
            Gauge.builder("dwe.worker.slots.capacity", () -> capacity).strongReference(true).register(registry);
        });
    }

    public void loop(String name) {
        safely(() -> {
            AtomicLong timestamp = loopSuccess.computeIfAbsent(name, key -> new AtomicLong());
            AtomicLong activity = loopActivity.computeIfAbsent(name, key -> new AtomicLong(System.nanoTime()));
            Gauge.builder("dwe.loop.last.success.timestamp.seconds", timestamp, value -> value.get() / 1000.0)
                    .tag("loop", name).register(registry);
            Gauge.builder("dwe.loop.age.seconds", activity,
                    value -> Math.max(0, System.nanoTime() - value.get()) / 1_000_000_000.0)
                    .tag("loop", name).register(registry);
        });
    }

    public void pass(String name, long started, boolean success) {
        operation(name, started, success ? "success" : "error");
        if (success) safely(() -> {
            loopSuccess.get(name).set(System.currentTimeMillis());
            loopActivity.get(name).set(System.nanoTime());
        });
    }

    /** Operation/outcome values are fixed at call sites, not derived from user input or error messages. */
    public void operation(String operation, long started, String outcome) {
        safely(() -> Timer.builder("dwe.operation.duration").tags("operation", operation, "outcome", outcome)
                .publishPercentileHistogram().register(registry)
                .record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS));
    }

    public void count(String event, String outcome) {
        safely(() -> registry.counter("dwe.events", "event", event, "outcome", outcome).increment());
    }

    /** Called only after a newly applied database transition commits, never for duplicate reports. */
    public void transition(String event, UUID executionId, String stepId, int cycleNo,
                           long attemptNo, String workerId, String outcome) {
        count(event, outcome);
        safely(() -> log.atInfo().addKeyValue("event", event).addKeyValue("executionId", executionId)
                .addKeyValue("stepId", stepId).addKeyValue("cycleNo", cycleNo)
                .addKeyValue("attemptNo", attemptNo).addKeyValue("workerId", workerId)
                .addKeyValue("outcome", outcome).log("Workflow lifecycle transition committed"));
    }

    private void safely(Runnable observation) {
        if (registry == null) return;
        try { observation.run(); }
        catch (RuntimeException ignored) {
            // Monitoring must not change a claim, outcome, renewal, or shutdown decision.
        }
    }
}
