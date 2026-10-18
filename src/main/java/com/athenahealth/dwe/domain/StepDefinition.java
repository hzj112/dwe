package com.athenahealth.dwe.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** One step's configuration: its handler name, parameters, prerequisite step IDs, and retry policy. */
public record StepDefinition(String id, String actionType, JsonNode parameters,
                             List<String> dependsOn, RetryPolicy retry) {
    public StepDefinition {
        // Copy mutable inputs so the caller cannot change them through the original references.
        parameters = parameters.deepCopy();
        dependsOn = List.copyOf(dependsOn);
    }
}
