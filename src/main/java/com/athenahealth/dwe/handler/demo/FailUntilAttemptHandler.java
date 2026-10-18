package com.athenahealth.dwe.handler.demo;

import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.StepExecutionException;
import com.athenahealth.dwe.handler.StepHandler;
import com.athenahealth.dwe.handler.StepValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Stateless demonstration handler whose failures survive process restarts because attempt numbers are durable. */
@Component
public class FailUntilAttemptHandler implements StepHandler {
    @Override
    public String actionType() { return "FAIL_UNTIL_ATTEMPT"; }

    /** failAttempts counts lifetime attempts; retryable defaults to true and can demonstrate permanent errors. */
    @Override
    public void validateParameters(JsonNode parameters) throws StepValidationException {
        JsonNode failures = parameters.get("failAttempts");
        if (failures == null || !failures.isIntegralNumber() || !failures.canConvertToLong() || failures.longValue() < 0) {
            throw new StepValidationException("failAttempts must be a nonnegative integer within the long range");
        }
        JsonNode retryable = parameters.get("retryable");
        if (retryable != null && !retryable.isBoolean()) {
            throw new StepValidationException("retryable must be a boolean when supplied");
        }
    }

    /** No in-memory counter or retry loop: the worker invokes this once for each claimed attempt. */
    @Override
    public JsonNode execute(StepContext context) throws StepExecutionException, InterruptedException {
        context.checkCancelled();
        if (context.attemptNo() <= context.parameters().get("failAttempts").longValue()) {
            JsonNode retryable = context.parameters().get("retryable");
            throw new StepExecutionException("DEMO_FAILURE", "Configured failure for lifetime attempt " + context.attemptNo(),
                    retryable == null || retryable.booleanValue());
        }
        return JsonNodeFactory.instance.objectNode().put("attemptNo", context.attemptNo())
                .put("cycleNo", context.cycleNo()).put("idempotencyKey", context.idempotencyKey());
    }
}
