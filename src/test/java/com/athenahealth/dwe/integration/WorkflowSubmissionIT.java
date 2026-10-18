package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class WorkflowSubmissionIT extends IntegrationTestSupport {
    private static final String ROOT = "/api/v1/executions";

    @Test
    void submitAndQueryPreservesOriginalDefinitionAndEffectiveConfiguration() throws Exception {
        var response = request("POST", ROOT, """
                {"definition":{"steps":[{"id":"a","actionType":"ECHO","retry":{"maxAttempts":1}}]}}
                """);
        assertThat(response.statusCode()).isEqualTo(201);
        String id = body(response).get("executionId").stringValue();
        assertThat(response.headers().firstValue("Location")).contains(ROOT + "/" + id);
        var detail = request("GET", ROOT + "/" + id, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(body(detail).get("status").stringValue()).isEqualTo("PENDING");
        assertThat(body(detail).get("definition").get("steps").get(0).has("parameters")).isFalse();
        var step = request("GET", ROOT + "/" + id + "/steps/a", null);
        assertThat(body(step).get("retry").get("backoffInitialMs").longValue()).isEqualTo(1000);
        assertThat(body(step).get("parameters").isObject()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"definition\":{\"steps\":[]}}",
            "{\"definition\":{\"steps\":[{\"id\":\"a\",\"actionType\":\"unknown\"}]}}",
            "{\"definition\":{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\"}]},\"unexpected\":1}",
            "{\"definition\":{\"steps\":[{\"id\":\"a\",\"actionType\":\"ECHO\"}]},\"inputs\":null}"})
    void invalidRequestsReturnErrorEnvelopeWithoutWritingRows(String request) throws Exception {
        var response = request("POST", ROOT, request);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").stringValue()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_execution", Integer.class)).isZero();
    }

    @Test
    void missingResourcesAndMalformedIdentifiersHaveStableErrors() throws Exception {
        assertThat(request("GET", ROOT + "/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        assertThat(request("GET", ROOT + "/invalid", null).statusCode()).isEqualTo(400);
        assertThat(request("GET", ROOT + "/" + submitOne() + "/steps/missing", null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/unknown-route", null).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", ROOT + "/" + submitOne(), null).statusCode()).isEqualTo(405);
    }
}
