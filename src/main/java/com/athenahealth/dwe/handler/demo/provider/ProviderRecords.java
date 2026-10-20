package com.athenahealth.dwe.handler.demo.provider;

import java.util.Locale;
import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.StepExecutionException;
import com.athenahealth.dwe.handler.StepValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** JSON conventions shared only by the provider demo handlers, independent of orchestration. */
final class ProviderRecords {
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    static final String[] REQUIRED = {"providerId", "name", "specialty", "state", "email"};

    private ProviderRecords() { }

    static void reference(JsonNode parameters, String name) throws StepValidationException {
        if (!parameters.path(name).isString() || parameters.path(name).stringValue().isBlank()) {
            throw new StepValidationException(name + " must be a nonblank dependency step ID");
        }
    }

    static JsonNode dependency(StepContext context, String parameter) throws StepExecutionException {
        JsonNode value = context.dependencyOutputs().get(context.parameters().path(parameter).asString());
        if (value == null || !value.isObject()) throw invalid("Missing dependency output for " + parameter);
        return value;
    }

    static JsonNode array(JsonNode value, String field) throws StepExecutionException {
        JsonNode result = value.path(field);
        if (!result.isArray()) throw invalid(field + " must be an array");
        return result;
    }

    static ObjectNode normalize(JsonNode record) throws StepExecutionException {
        if (!record.isObject()) throw invalid("Provider record must be an object");
        ObjectNode result = ((ObjectNode) record).deepCopy();
        for (String field : REQUIRED) {
            JsonNode value = result.path(field);
            if (!value.isString()) continue;
            String text = value.stringValue().strip();
            if (field.equals("state")) text = text.toUpperCase(Locale.ROOT);
            if (field.equals("email") && text.lastIndexOf('@') >= 0) {
                int separator = text.lastIndexOf('@');
                text = text.substring(0, separator + 1) + text.substring(separator + 1).toLowerCase(Locale.ROOT);
            }
            result.put(field, text);
        }
        return result;
    }

    static String id(JsonNode record) {
        JsonNode value = record.path("providerId");
        return value.isString() ? value.stringValue().strip() : "";
    }

    static ObjectNode finding(int row, JsonNode record, String field, String code, String message) {
        ObjectNode result = JSON.objectNode().put("sourceRow", row).put("field", field)
                .put("code", code).put("message", message);
        if (!id(record).isEmpty()) result.put("providerId", id(record));
        JsonNode value = record.get(field);
        if (value != null) result.set("value", value.deepCopy());
        return result;
    }

    static StepExecutionException invalid(String message) {
        return new StepExecutionException("INVALID_PROVIDER_INPUT", message, false);
    }
}
