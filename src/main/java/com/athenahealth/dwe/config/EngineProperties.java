package com.athenahealth.dwe.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.athenahealth.dwe.domain.RetryPolicy;

/** Reads engine.* settings from application configuration and rejects unsafe timing or capacity values. */
@ConfigurationProperties("engine")
public record EngineProperties(
        Duration leaseDuration, Duration heartbeatInterval, Duration pollInterval,
        Duration coordinatorInterval, Duration operationTimeout, Duration safetyMargin,
        Duration shutdownGrace, int workerConcurrency, int defaultMaxAttempts,
        long defaultBackoffInitialMs, long defaultBackoffMaxMs) {

    /** Validates settings at startup, including enough time to renew a lease before it expires. */
    public EngineProperties {
        positive("lease-duration", leaseDuration);
        positive("heartbeat-interval", heartbeatInterval);
        positive("poll-interval", pollInterval);
        positive("coordinator-interval", coordinatorInterval);
        positive("operation-timeout", operationTimeout);
        positive("safety-margin", safetyMargin);
        positive("shutdown-grace", shutdownGrace);
        if (heartbeatInterval.plus(operationTimeout).plus(safetyMargin).compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("Heartbeat, operation timeout, and safety margin must fit within the lease");
        }
        if (workerConcurrency < 1 || defaultMaxAttempts < 1 || defaultBackoffInitialMs < 1
                || defaultBackoffMaxMs < defaultBackoffInitialMs || defaultBackoffMaxMs > RetryPolicy.MAX_BACKOFF_MS) {
            throw new IllegalArgumentException("Invalid worker capacity or default retry policy");
        }
    }

    /** Stops local execution before database lease expiry, leaving a safety margin for timing uncertainty. */
    public long ownershipWindowNanos() {
        return leaseDuration.minus(safetyMargin).toNanos();
    }

    private static void positive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
    }
}
