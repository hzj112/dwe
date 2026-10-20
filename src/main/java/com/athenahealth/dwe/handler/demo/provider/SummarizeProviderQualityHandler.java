package com.athenahealth.dwe.handler.demo.provider;

import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.stereotype.Component;
import com.athenahealth.dwe.handler.*;
import tools.jackson.databind.JsonNode;

/** Combines independently produced normalization and validation results into stable business JSON. */
@Component
public class SummarizeProviderQualityHandler implements StepHandler {
    @Override
    public String actionType() { return "PROVIDER_SUMMARIZE"; }

    @Override
    public void validateParameters(JsonNode parameters) throws StepValidationException {
        ProviderRecords.reference(parameters, "normalizedStep");
        ProviderRecords.reference(parameters, "validatedStep");
    }

    @Override
    public JsonNode execute(StepContext context) throws StepExecutionException, InterruptedException {
        context.checkCancelled();
        var normalized = ProviderRecords.dependency(context, "normalizedStep");
        var validated = ProviderRecords.dependency(context, "validatedStep");
        if (normalized.path("inputRecords").intValue() != validated.path("inputRecords").intValue()) {
            throw ProviderRecords.invalid("Branch input counts disagree");
        }
        var cleaned = ProviderRecords.array(normalized, "cleanedRecords");
        var validation = ProviderRecords.array(validated, "findings");
        var findings = new ArrayList<JsonNode>();
        ProviderRecords.array(normalized, "findings").forEach(findings::add);
        validation.forEach(findings::add);
        findings.sort(Comparator.comparingInt((JsonNode node) -> node.path("sourceRow").intValue())
                .thenComparing(node -> node.path("field").asString()).thenComparing(node -> node.path("code").asString()));
        var output = ProviderRecords.JSON.objectNode();
        var report = output.putObject("report").put("schemaVersion", 1);
        report.putObject("summary").put("inputRecords", normalized.path("inputRecords").intValue())
                .put("cleanedRecords", cleaned.size()).put("uniqueProviderIds", normalized.path("uniqueProviderIds").intValue())
                .put("duplicatesRemoved", normalized.path("duplicatesRemoved").intValue())
                .put("validationFindings", validation.size()).put("conflicts", normalized.path("conflicts").intValue())
                .put("totalFindings", findings.size());
        var ordered = report.putArray("findings");
        for (JsonNode finding : findings) {
            context.checkCancelled();
            ordered.add(finding.deepCopy());
        }
        report.set("provenance", ProviderRecords.array(normalized, "provenance").deepCopy());
        output.set("cleanedRecords", cleaned.deepCopy());
        return output;
    }
}
