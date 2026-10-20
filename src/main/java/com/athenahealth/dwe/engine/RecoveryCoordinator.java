package com.athenahealth.dwe.engine;

import com.athenahealth.dwe.persistence.WorkflowStore;
import com.athenahealth.dwe.observability.EngineTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically settles expired ownership, including unfinished attempts on already-failed workflows. */
@Component
@Profile("api")
@ConditionalOnProperty(name = "engine.coordinator-enabled", matchIfMissing = true)
public class RecoveryCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RecoveryCoordinator.class);
    private final WorkflowStore store;
    private final EngineTelemetry telemetry;

    public RecoveryCoordinator(WorkflowStore store, EngineTelemetry telemetry) {
        this.store = store;
        this.telemetry = telemetry;
        telemetry.loop("recovery");
    }

    /** A failed pass is retried on the next schedule; successful transactions remain durable. */
    @Scheduled(fixedDelayString = "${engine.recovery-interval}")
    public void recover() {
        long started = System.nanoTime();
        try {
            int recovered = store.recoverExpired();
            telemetry.pass("recovery", started, true);
            if (recovered > 0) log.info("Recovered {} expired step attempts", recovered);
        } catch (RuntimeException e) {
            telemetry.pass("recovery", started, false);
            log.warn("Lease recovery pass failed; will retry on the next pass", e);
        }
    }
}
