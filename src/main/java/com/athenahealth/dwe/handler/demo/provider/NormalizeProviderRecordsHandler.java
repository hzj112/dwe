package com.athenahealth.dwe.handler.demo.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import com.athenahealth.dwe.handler.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Removes identical normalized records while preserving conflicting records and source provenance. */
@Component
public class NormalizeProviderRecordsHandler implements StepHandler {
    private record Retained(ObjectNode record, int sourceRow, ArrayNode sourceRows) { }

    @Override
    public String actionType() { return "PROVIDER_NORMALIZE"; }

    @Override
    public void validateParameters(JsonNode parameters) throws StepValidationException {
        ProviderRecords.reference(parameters, "sourceStep");
    }

    @Override
    public JsonNode execute(StepContext context) throws StepExecutionException, InterruptedException {
        context.checkCancelled();
        var rows = ProviderRecords.array(ProviderRecords.dependency(context, "sourceStep"), "records");
        var result = ProviderRecords.JSON.objectNode().put("inputRecords", rows.size());
        var cleaned = result.putArray("cleanedRecords");
        var findings = result.putArray("findings");
        var provenance = result.putArray("provenance");
        var byId = new LinkedHashMap<String, List<Retained>>();
        int duplicates = 0, conflicts = 0;
        for (JsonNode row : rows) {
            context.checkCancelled();
            int source = row.path("sourceRow").intValue();
            ObjectNode record = ProviderRecords.normalize(row.path("record"));
            String id = ProviderRecords.id(record);
            List<Retained> variants = id.isEmpty() ? new ArrayList<>() : byId.computeIfAbsent(id, key -> new ArrayList<>());
            Retained identical = variants.stream().filter(item -> item.record().equals(record)).findFirst().orElse(null);
            if (identical != null) {
                identical.sourceRows().add(source);
                findings.add(ProviderRecords.finding(source, record, "providerId", "DUPLICATE_RECORD",
                        "Identical normalized record removed").put("relatedSourceRow", identical.sourceRow()));
                duplicates++;
                continue;
            }
            if (!variants.isEmpty()) {
                findings.add(ProviderRecords.finding(source, record, "providerId", "CONFLICTING_PROVIDER_ID",
                        "Different record with the same provider ID retained")
                        .put("relatedSourceRow", variants.getFirst().sourceRow()));
                conflicts++;
            }
            cleaned.add(record);
            var sources = provenance.addObject().put("cleanedRecordIndex", cleaned.size()).putArray("sourceRows");
            sources.add(source);
            variants.add(new Retained(record, source, sources));
        }
        return result.put("uniqueProviderIds", byId.size()).put("duplicatesRemoved", duplicates).put("conflicts", conflicts);
    }
}
