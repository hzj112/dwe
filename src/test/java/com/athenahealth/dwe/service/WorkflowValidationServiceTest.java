package com.athenahealth.dwe.service;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.handler.demo.*;
import com.athenahealth.dwe.service.WorkflowValidationService;

import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class WorkflowValidationServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final WorkflowValidationService validation = new WorkflowValidationService(
            new HandlerRegistry(List.of(new EchoHandler(), new DelayHandler())), defaults());

    public static EngineProperties defaults() {
        return new EngineProperties(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5),
                2, 3, 1000, 30000);
    }

    @Test
    void defaultsAreResolvedIndividuallyWithoutChangingSnapshot() {
        var definition = json.readTree("""
                {"steps":[{"id":"a","actionType":"ECHO","retry":{"maxAttempts":1}}]}
                """);
        var result = validation.validate(definition, null);
        var step = result.definition().steps().getFirst();
        assertThat(step.retry().maxAttempts()).isEqualTo(1);
        assertThat(step.retry().backoffInitialMs()).isEqualTo(1000);
        assertThat(step.parameters().isObject()).isTrue();
        assertThat(result.inputs().isObject()).isTrue();
        assertThat(result.definitionSnapshot()).isEqualTo(definition);
        assertThat(result.definitionSnapshot().get("steps").get(0).has("parameters")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"steps\":[]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"unknown\"}]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\",\"parameters\":null}]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\",\"dependsOn\":null}]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\",\"retry\":null}]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\",\"extra\":1}]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"DELAY\"}]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\",\"retry\":{\"maxAttempts\":1.5}}]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\",\"retry\":{\"backoffInitialMs\":40000}}]}",
            "{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\",\"retry\":{\"backoffMaxMs\":9223372036854775807}}]}"
    })
    void rejectsInvalidStructureAndParameters(String definition) {
        assertThatThrownBy(() -> validation.validate(json.readTree(definition), null))
                .isInstanceOf(WorkflowValidationService.InvalidWorkflowException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":\"a\",\"dependsOn\":[\"b\"]}]",
            "[{\"id\":\"a\",\"dependsOn\":[\"a\"]}]",
            "[{\"id\":\"a\"},{\"id\":\"a\"}]",
            "[{\"id\":\"a\",\"dependsOn\":[\"b\"]},{\"id\":\"b\",\"dependsOn\":[\"a\"]}]",
            "[{\"id\":\"a\"},{\"id\":\"b\",\"dependsOn\":[\"a\",\"a\"]}]"
    })
    void rejectsInvalidGraphs(String steps) {
        var definition = json.createObjectNode();
        var nodes = json.readTree(steps);
        nodes.forEach(node -> ((tools.jackson.databind.node.ObjectNode) node).put("actionType", "ECHO"));
        definition.set("steps", nodes);
        assertThatThrownBy(() -> validation.validate(definition, null))
                .isInstanceOf(WorkflowValidationService.InvalidWorkflowException.class);
    }

    @Test
    void validatesLongChainsWithoutRecursiveStackGrowth() {
        var definition = json.createObjectNode();
        var steps = definition.putArray("steps");
        for (int i = 0; i < 5000; i++) {
            var step = steps.addObject().put("id", "s" + i).put("actionType", "ECHO");
            if (i > 0) step.putArray("dependsOn").add("s" + (i - 1));
        }
        assertThat(validation.validate(definition, null).definition().steps()).hasSize(5000);
    }

    @Test
    void explicitNullInputsAreRejected() {
        assertThatThrownBy(() -> validation.validate(
                json.readTree("{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\"}]}"), json.nullNode()))
                .isInstanceOf(WorkflowValidationService.InvalidWorkflowException.class);
    }
}
