package com.athenahealth.dwe.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.athenahealth.dwe.persistence.WorkflowStore;

/** Periodically advances persisted workflows in the API process; business handlers run on workers. */
@Component
@Profile("api")
@ConditionalOnProperty(name = "engine.coordinator-enabled", matchIfMissing = true)
public class WorkflowCoordinator {
    private static final Logger log = LoggerFactory.getLogger(WorkflowCoordinator.class);
    private final WorkflowStore store;

    public WorkflowCoordinator(WorkflowStore store) { this.store = store; }

    /** Makes dependency-ready steps eligible and completes workflows whose steps have all completed. */
    @Scheduled(fixedDelayString = "${engine.coordinator-interval}")
    public void advance() {
        try {
            store.advance();
        } catch (RuntimeException e) {
            log.warn("Coordinator pass failed; will retry on the next pass", e);
        }
    }
}
