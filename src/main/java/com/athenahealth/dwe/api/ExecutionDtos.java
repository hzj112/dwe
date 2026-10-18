package com.athenahealth.dwe.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.athenahealth.dwe.domain.RetryPolicy;
import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.service.WorkflowValidationService.Violation;

import tools.jackson.databind.JsonNode;

/** Groups data transfer objects (DTOs): the request and response shapes Spring converts to/from JSON. */
public final class ExecutionDtos {
    private ExecutionDtos() { }

    /** Keeps the submitted definition as JSON so validation can preserve the original client payload. */
    public record SubmissionRequest(JsonNode definition, JsonNode inputs) { }
    /** Acknowledges creation; this receipt does not mean the workflow has finished. */
    public record ExecutionReceipt(UUID executionId, WorkflowStatus status, int currentCycleNo) { }
    /** A stored workflow or step failure, rather than an HTTP request error. */
    public record ResourceError(String code, String message) { }
    public record StepSummary(String stepId, StepStatus status) { }
    /** Execution status view; retryAllowed describes state eligibility, ahead of the future retry endpoint. */
    public record ExecutionDetail(UUID executionId, WorkflowStatus status, int currentCycleNo,
                                  Instant createdAt, Instant firstStartedAt, Instant finishedAt,
                                  int runningStepCount, boolean retryAllowed, JsonNode definition,
                                  JsonNode inputs, ResourceError error, List<StepSummary> steps) { }
    /** Step status view; lastAttemptNo is a lifetime counter, while attemptsInCurrentCycle counts this cycle. */
    public record StepDetail(String stepId, String actionType, JsonNode parameters,
                             List<String> dependsOn, StepStatus status, Instant eligibleAt,
                             RetryPolicy retry, int attemptsInCurrentCycle, long lastAttemptNo,
                             JsonNode output, ResourceError error) { }
    /** HTTP request failure, optionally including individual invalid fields. */
    public record ApiError(String code, String message, List<Violation> details) { }
    /** Gives every HTTP error response the same top-level "error" field. */
    public record ErrorEnvelope(ApiError error) { }
}
