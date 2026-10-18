package com.athenahealth.dwe.handler.demo;

import org.springframework.stereotype.Component;

import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.StepHandler;
import com.athenahealth.dwe.handler.StepValidationException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Example DELAY handler that waits up to 60 seconds, useful for observing execution and lease renewal. */
@Component
public class DelayHandler implements StepHandler {
    @Override
    public String actionType() { return "DELAY"; }

    @Override
    public void validateParameters(JsonNode parameters) throws StepValidationException {
        JsonNode duration = parameters.get("durationMs");
        if (duration == null || !duration.isIntegralNumber() || !duration.canConvertToLong()
                || duration.longValue() < 0 || duration.longValue() > 60_000) {
            throw new StepValidationException("durationMs must be an integer from 0 through 60000");
        }
    }

    /** Sleep responds to thread interruption; cancellation checks also guard entry and result creation. */
    @Override
    public JsonNode execute(StepContext context) throws InterruptedException {
        context.checkCancelled();
        long duration = context.parameters().get("durationMs").longValue();
        Thread.sleep(duration);
        context.checkCancelled();
        return JsonNodeFactory.instance.objectNode().put("durationMs", duration);
    }
}
