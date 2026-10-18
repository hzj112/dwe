package com.athenahealth.dwe.support;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.persistence.WorkflowStore;
import com.athenahealth.dwe.worker.LeaseManager;
import com.athenahealth.dwe.worker.WorkerRuntime;

/** Starts a real worker for integration tests and drains it before closing its heartbeat executors. */
public final class WorkerHarness implements AutoCloseable {
    private final LeaseManager leases;
    private final WorkerRuntime worker;

    public WorkerHarness(WorkflowStore store, HandlerRegistry registry, EngineProperties properties) {
        leases = new LeaseManager(store, properties);
        worker = new WorkerRuntime(store, registry, properties, leases);
        worker.start();
    }

    @Override
    public void close() {
        try { worker.stop(); }
        finally { leases.close(); }
    }
}
