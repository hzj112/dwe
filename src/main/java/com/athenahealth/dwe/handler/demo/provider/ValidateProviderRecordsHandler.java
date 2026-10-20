package com.athenahealth.dwe.handler.demo.provider;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.athenahealth.dwe.handler.*;
import tools.jackson.databind.JsonNode;

/** Local directory-quality rules plus explicit, disabled-by-default demonstration fault controls. */
@Component
public class ValidateProviderRecordsHandler implements StepHandler {
    private static final Set<String> STATES = Set.of(("AL AK AZ AR CA CO CT DE FL GA HI ID IL IN IA KS KY LA ME MD MA MI MN "
            + "MS MO MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY DC").split(" "));
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+$", Pattern.UNICODE_CHARACTER_CLASS);

    @Override
    public String actionType() { return "PROVIDER_VALIDATE"; }

    @Override
    public void validateParameters(JsonNode parameters) throws StepValidationException {
        ProviderRecords.reference(parameters, "sourceStep");
        if (parameters.has("demoFailFirstAttempt") && !parameters.path("demoFailFirstAttempt").isBoolean()) {
            throw new StepValidationException("demoFailFirstAttempt must be a boolean");
        }
        if (parameters.has("demoFirstAttemptDelayMs")) {
            var delay = parameters.path("demoFirstAttemptDelayMs");
            if (!delay.isIntegralNumber() || !delay.canConvertToLong() || delay.longValue() < 0 || delay.longValue() > 120000) {
                throw new StepValidationException("demoFirstAttemptDelayMs must be an integer from 0 to 120000");
            }
        }
    }

    @Override
    public JsonNode execute(StepContext context) throws StepExecutionException, InterruptedException {
        context.checkCancelled();
        // Lifetime attempt identity survives worker replacement and manual retry cycles.
        if (context.attemptNo() == 1) {
            long remaining = context.parameters().path("demoFirstAttemptDelayMs").asLong(0);
            while (remaining > 0) {
                context.checkCancelled();
                long sleep = Math.min(remaining, 100);
                Thread.sleep(sleep);
                remaining -= sleep;
            }
            context.checkCancelled();
            if (context.parameters().path("demoFailFirstAttempt").asBoolean(false)) {
                throw new StepExecutionException("DEMO_PROVIDER_SERVICE_UNAVAILABLE", "Simulated transient validation failure", true);
            }
        }
        var rows = ProviderRecords.array(ProviderRecords.dependency(context, "sourceStep"), "records");
        var result = ProviderRecords.JSON.objectNode().put("inputRecords", rows.size());
        var findings = result.putArray("findings");
        for (JsonNode row : rows) {
            context.checkCancelled();
            var record = ProviderRecords.normalize(row.path("record"));
            int source = row.path("sourceRow").intValue();
            for (String field : ProviderRecords.REQUIRED) {
                JsonNode value = record.path(field);
                String code = null, message = null;
                if (value.isMissingNode() || value.isNull() || (value.isString() && value.stringValue().isBlank())) {
                    code = "MISSING_FIELD"; message = "Required field is missing or blank";
                } else if (!value.isString()) {
                    code = "INVALID_FIELD_TYPE"; message = "Required field must be a string";
                } else if (field.equals("state") && !STATES.contains(value.stringValue())) {
                    code = "INVALID_STATE"; message = "Expected a US state or DC postal abbreviation";
                } else if (field.equals("email") && !EMAIL.matcher(value.stringValue()).matches()) {
                    code = "INVALID_EMAIL"; message = "Expected local-part@domain with a dotted domain and no whitespace";
                }
                if (code != null) findings.add(ProviderRecords.finding(source, record, field, code, message));
            }
        }
        return result;
    }
}
