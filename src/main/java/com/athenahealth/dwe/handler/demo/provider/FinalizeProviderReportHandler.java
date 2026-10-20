package com.athenahealth.dwe.handler.demo.provider;

import org.springframework.stereotype.Component;
import com.athenahealth.dwe.handler.*;
import tools.jackson.databind.JsonNode;

/** Produces ordinary persisted step output; the external client owns presentation and file export. */
@Component
public class FinalizeProviderReportHandler implements StepHandler {
    @Override
    public String actionType() { return "PROVIDER_FINALIZE"; }

    @Override
    public void validateParameters(JsonNode parameters) throws StepValidationException {
        ProviderRecords.reference(parameters, "summaryStep");
    }

    @Override
    public JsonNode execute(StepContext context) throws StepExecutionException, InterruptedException {
        context.checkCancelled();
        var summary = ProviderRecords.dependency(context, "summaryStep");
        if (!summary.path("report").isObject()) throw ProviderRecords.invalid("Summary report must be an object");
        var output = ProviderRecords.JSON.objectNode();
        output.set("report", summary.path("report").deepCopy());
        output.set("cleanedRecords", ProviderRecords.array(summary, "cleanedRecords").deepCopy());
        return output;
    }
}
