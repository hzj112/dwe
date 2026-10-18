package com.athenahealth.dwe.service;

import org.springframework.stereotype.Service;

import com.athenahealth.dwe.api.ExecutionDtos.ExecutionReceipt;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.persistence.WorkflowStore;

import tools.jackson.databind.JsonNode;

/** Connects API submission to validation and persistence; actual step execution happens later on workers. */
@Service
public class WorkflowSubmissionService {
    private final WorkflowValidationService validation;
    private final WorkflowStore store;

    public WorkflowSubmissionService(WorkflowValidationService validation, WorkflowStore store) {
        this.validation = validation;
        this.store = store;
    }

    /** Validates the complete request before writing it, then returns the new execution's identity. */
    public ExecutionReceipt submit(JsonNode definition, JsonNode inputs) {
        var submission = validation.validate(definition, inputs);
        return new ExecutionReceipt(store.submit(submission), WorkflowStatus.PENDING, 1);
    }
}
