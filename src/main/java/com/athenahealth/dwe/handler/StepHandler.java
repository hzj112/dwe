package com.athenahealth.dwe.handler;

import tools.jackson.databind.JsonNode;

/** Contract for business step handlers; the worker manages leases, persistence, and retry scheduling. */
public interface StepHandler {
    /** The registered name referenced by a submitted step's actionType field. */
    String actionType();
    /** Checks handler-specific parameters during submission, without performing the business action. */
    void validateParameters(JsonNode parameters) throws StepValidationException;
    /** Runs outside database transactions; returns a JSON object and should cooperate with cancellation. */
    JsonNode execute(StepContext context) throws StepExecutionException, InterruptedException;
}
