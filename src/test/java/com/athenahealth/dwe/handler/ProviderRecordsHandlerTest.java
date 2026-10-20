package com.athenahealth.dwe.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.handler.demo.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

/** Business rules and deterministic results are tested independently of the workflow engine. */
class ProviderRecordsHandlerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void sampleProducesExpectedReportAndSourceProvenance() throws Exception {
        var sample = json.readTree(Files.readString(Path.of("demo/provider-quality/normal.json")));
        var output = pipeline(sample.path("inputs"));
        assertThat(output.path("report").path("summary")).isEqualTo(json.readTree(
                Files.readString(Path.of("demo/provider-quality/expected-summary.json"))));
        assertThat(output.path("cleanedRecords").size()).isEqualTo(4);
        var findings = output.path("report").path("findings");
        assertThat(findings.get(0).path("code").asString()).isEqualTo("MISSING_FIELD");
        assertThat(findings.get(1).path("code").asString()).isEqualTo("INVALID_STATE");
        assertThat(findings.get(1).path("value").asString()).isEqualTo("XX");
        assertThat(findings.get(2).path("relatedSourceRow").intValue()).isEqualTo(2);
        assertThat(output.path("report").path("provenance").get(1).path("sourceRows"))
                .isEqualTo(json.readTree("[2,5]"));
        assertThat(output.path("cleanedRecords").get(2).has("email")).isFalse();
        assertThat(pipeline(sample.path("inputs"))).isEqualTo(output);
    }

    @Test
    void normalizationIsSharedAndPreservesLocalEmailCaseAndExtraMetadata() throws Exception {
        var inputs = json.readTree("""
                {"records":[
                  {"providerId":" P1 ","name":" Alice ","specialty":" Cardiology ","state":" ma ",
                   "email":" Alice@EXAMPLE.COM ","active":false},
                  {"providerId":"P1","name":"Alice","specialty":"Cardiology","state":"MA",
                   "email":"Alice@example.com","active":false}]}
                """);
        var before = inputs.deepCopy();
        var output = pipeline(inputs);
        var summary = output.path("report").path("summary");
        assertThat(summary.path("duplicatesRemoved").intValue()).isEqualTo(1);
        assertThat(summary.path("validationFindings").intValue()).isZero();
        assertThat(output.path("cleanedRecords").get(0).path("email").asString()).isEqualTo("Alice@example.com");
        assertThat(output.path("cleanedRecords").get(0).path("active").booleanValue()).isFalse();
        assertThat(inputs).isEqualTo(before);
    }

    @Test
    void conflictsAreRetainedAndMissingIdsNeverFormADuplicateGroup() throws Exception {
        var output = pipeline(json.readTree("""
                {"records":[{"providerId":"P1","name":"Alice"},{"providerId":"P1","name":"Bob"},
                  {"providerId":"P1","name":"Bob"},{"name":"Unknown"},{"name":"Unknown"}]}
                """));
        var summary = output.path("report").path("summary");
        assertThat(summary.path("cleanedRecords").intValue()).isEqualTo(4);
        assertThat(summary.path("uniqueProviderIds").intValue()).isEqualTo(1);
        assertThat(summary.path("conflicts").intValue()).isEqualTo(1);
        assertThat(summary.path("duplicatesRemoved").intValue()).isEqualTo(1);
        assertThat(output.path("report").path("provenance").get(1).path("sourceRows"))
                .isEqualTo(json.readTree("[2,3]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"  \"", "42", "true", "[]", "{}"})
    void invalidBusinessFieldsAreFindingsRatherThanExecutionFailures(String value) throws Exception {
        var record = (ObjectNode) json.readTree("""
                {"providerId":"P1","name":"Alice","specialty":"Cardiology","state":"DC","email":"a@example.com"}
                """);
        for (String field : new String[]{"providerId", "name", "specialty", "state", "email"}) {
            var changed = record.deepCopy();
            changed.set(field, json.readTree(value));
            var inputs = json.createObjectNode();
            inputs.putArray("records").add(changed);
            var findings = pipeline(inputs).path("report").path("findings");
            assertThat(findings.size()).isEqualTo(1);
            assertThat(findings.get(0).path("field").asString()).isEqualTo(field);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a@localhost", "a@@example.com", "a @example.com", "a\u00a0@example.com", "@example.com", "a@.com", "a@example..com"})
    void rejectsMalformedEmailAsOneFinding(String email) throws Exception {
        var inputs = json.readTree("""
                {"records":[{"providerId":"P1","name":"Alice","specialty":"Cardiology","state":"ny","email":"valid@example.com"}]}
                """);
        ((ObjectNode) inputs.path("records").get(0)).put("email", email);
        var findings = pipeline(inputs).path("report").path("findings");
        assertThat(findings.size()).isEqualTo(1);
        assertThat(findings.get(0).path("code").asString()).isEqualTo("INVALID_EMAIL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"records\":null}", "{\"records\":{}}", "{\"records\":[1]}", "{\"records\":[null]}"})
    void malformedBatchesFailPermanently(String inputs) {
        assertThatThrownBy(() -> pipeline(json.readTree(inputs)))
                .isInstanceOfSatisfying(StepExecutionException.class, error -> {
                    assertThat(error.code()).isEqualTo("INVALID_PROVIDER_INPUT");
                    assertThat(error.retryable()).isFalse();
                });
    }

    @Test
    void emptyBatchHasAValidZeroCountReport() throws Exception {
        var output = pipeline(json.readTree("{\"records\":[]}"));
        assertThat(output.path("report").path("summary").path("totalFindings").intValue()).isZero();
        assertThat(output.path("cleanedRecords").isEmpty()).isTrue();
    }

    @Test
    void faultsUseDurableAttemptIdentityAndDoNotAlterSuccessfulOutput() throws Exception {
        var load = run(new LoadProviderRecordsHandler(), "{}", json.readTree("{\"records\":[]}"), Map.of(), 1);
        var handler = new ValidateProviderRecordsHandler();
        String parameters = "{\"sourceStep\":\"load\",\"demoFailFirstAttempt\":true,\"demoFirstAttemptDelayMs\":1}";
        assertThatThrownBy(() -> run(handler, parameters, json.createObjectNode(), Map.of("load", load), 1))
                .isInstanceOfSatisfying(StepExecutionException.class, error -> assertThat(error.retryable()).isTrue());
        var retried = run(new ValidateProviderRecordsHandler(), parameters, json.createObjectNode(), Map.of("load", load), 2);
        assertThat(retried).isEqualTo(run(handler, "{\"sourceStep\":\"load\"}", json.createObjectNode(), Map.of("load", load), 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"sourceStep\":1}", "{\"sourceStep\":\"load\",\"demoFailFirstAttempt\":null}",
            "{\"sourceStep\":\"load\",\"demoFirstAttemptDelayMs\":-1}",
            "{\"sourceStep\":\"load\",\"demoFirstAttemptDelayMs\":120001}",
            "{\"sourceStep\":\"load\",\"demoFirstAttemptDelayMs\":0.5}",
            "{\"sourceStep\":\"load\",\"demoFirstAttemptDelayMs\":9223372036854775808}"})
    void invalidFaultParametersAreRejectedAtSubmission(String parameters) {
        assertThatThrownBy(() -> new ValidateProviderRecordsHandler().validateParameters(json.readTree(parameters)))
                .isInstanceOf(StepValidationException.class);
    }

    @Test
    void delayCooperatesWithOwnershipCancellation() {
        var checks = new AtomicInteger();
        var context = context("{\"sourceStep\":\"load\",\"demoFirstAttemptDelayMs\":120000}",
                json.createObjectNode(), Map.of(), 1, () -> checks.incrementAndGet() >= 3);
        assertThatThrownBy(() -> new ValidateProviderRecordsHandler().execute(context)).isInstanceOf(InterruptedException.class);
    }

    @Test
    void missingDependencyFailsPermanently() {
        assertThatThrownBy(() -> run(new NormalizeProviderRecordsHandler(), "{\"sourceStep\":\"absent\"}",
                json.createObjectNode(), Map.of(), 1)).isInstanceOf(StepExecutionException.class);
    }

    private JsonNode pipeline(JsonNode inputs) throws Exception {
        var loaded = run(new LoadProviderRecordsHandler(), "{}", inputs, Map.of(), 1);
        var normalized = run(new NormalizeProviderRecordsHandler(), "{\"sourceStep\":\"load\"}", inputs, Map.of("load", loaded), 1);
        var validated = run(new ValidateProviderRecordsHandler(), "{\"sourceStep\":\"load\"}", inputs, Map.of("load", loaded), 1);
        var summary = run(new SummarizeProviderQualityHandler(), "{\"normalizedStep\":\"n\",\"validatedStep\":\"v\"}",
                inputs, Map.of("n", normalized, "v", validated), 1);
        return run(new FinalizeProviderReportHandler(), "{\"summaryStep\":\"s\"}", inputs, Map.of("s", summary), 1);
    }

    private JsonNode run(StepHandler handler, String parameters, JsonNode inputs, Map<String, JsonNode> dependencies, long attempt)
            throws Exception {
        handler.validateParameters(json.readTree(parameters));
        return handler.execute(context(parameters, inputs, dependencies, attempt, () -> false));
    }

    private StepContext context(String parameters, JsonNode inputs, Map<String, JsonNode> dependencies,
                                long attempt, BooleanSupplier cancelled) {
        return new StepContext(new StepClaim(UUID.randomUUID(), "step", "PROVIDER", 1, attempt, "owner",
                inputs, json.readTree(parameters), dependencies, System.nanoTime()), cancelled);
    }
}
