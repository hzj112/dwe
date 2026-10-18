package com.athenahealth.dwe.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.athenahealth.dwe.domain.StepClaim;

import tools.jackson.databind.JsonNode;

/** Provides one handler invocation with inputs, direct dependency outputs, identity, and cancellation checks. */
public final class StepContext {
    private final StepClaim claim;
    private final JsonNode inputs;
    private final JsonNode parameters;
    private final Map<String, JsonNode> dependencyOutputs;
    private final BooleanSupplier cancelled;

    /** Copies JSON values so a handler's local edits cannot mutate the claim or other invocations' data. */
    public StepContext(StepClaim claim, BooleanSupplier cancelled) {
        this.claim = claim;
        this.cancelled = cancelled;
        inputs = claim.workflowInputs().deepCopy();
        parameters = claim.parameters().deepCopy();
        var outputs = new LinkedHashMap<String, JsonNode>();
        claim.dependencyOutputs().forEach((key, value) -> outputs.put(key, value.deepCopy()));
        dependencyOutputs = Map.copyOf(outputs);
    }

    public UUID executionId() { return claim.executionId(); }
    public String stepId() { return claim.stepId(); }
    public int cycleNo() { return claim.cycleNo(); }
    public long attemptNo() { return claim.attemptNo(); }
    public JsonNode workflowInputs() { return inputs; }
    public JsonNode parameters() { return parameters; }
    public Map<String, JsonNode> dependencyOutputs() { return dependencyOutputs; }
    /** Stable across attempts; handlers can use this key to deduplicate external side effects. */
    public String idempotencyKey() { return executionId() + ":" + stepId(); }

    /** Long-running handlers should call this between operations to stop when local ownership is lost. */
    public void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new InterruptedException("Step attempt no longer permits local execution");
        }
    }
}
