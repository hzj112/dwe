package com.athenahealth.dwe.domain;

import java.util.List;

/** The validated workflow structure used internally; execution status is stored separately in the database. */
public record WorkflowDefinition(List<StepDefinition> steps) {
    public WorkflowDefinition {
        // Keep later changes to the caller's list from changing this workflow's step list.
        steps = List.copyOf(steps);
    }
}
