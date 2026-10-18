package com.athenahealth.dwe.domain;

import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Data for one claimed attempt, including the worker identity and inputs needed to execute it.
 * The increasing attempt number is a fencing token: older attempts cannot overwrite newer state.
 * requestStartedNanos measures local elapsed time; it is not a database timestamp.
 */
public record StepClaim(UUID executionId, String stepId, String actionType,
                        int cycleNo, long attemptNo, String workerId,
                        JsonNode workflowInputs, JsonNode parameters,
                        Map<String, JsonNode> dependencyOutputs, long requestStartedNanos) {
    public StepClaim {
        dependencyOutputs = Map.copyOf(dependencyOutputs);
    }
}
