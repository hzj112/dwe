package com.athenahealth.dwe.handler.demo.provider;

import org.springframework.stereotype.Component;
import com.athenahealth.dwe.handler.*;
import tools.jackson.databind.JsonNode;

/** Loads submitted records; malformed structure is an error, field-quality issues are report data. */
@Component
public class LoadProviderRecordsHandler implements StepHandler {
    @Override
    public String actionType() { return "PROVIDER_LOAD"; }

    @Override
    public void validateParameters(JsonNode parameters) { }

    @Override
    public JsonNode execute(StepContext context) throws StepExecutionException, InterruptedException {
        context.checkCancelled();
        var records = ProviderRecords.array(context.workflowInputs(), "records");
        var result = ProviderRecords.JSON.objectNode();
        var rows = result.putArray("records");
        for (int i = 0; i < records.size(); i++) {
            context.checkCancelled();
            if (!records.get(i).isObject()) throw ProviderRecords.invalid("Record " + (i + 1) + " must be an object");
            rows.addObject().put("sourceRow", i + 1).set("record", records.get(i).deepCopy());
        }
        return result;
    }
}
