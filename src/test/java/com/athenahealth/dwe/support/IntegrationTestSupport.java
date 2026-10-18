package com.athenahealth.dwe.support;

import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.persistence.*;
import com.athenahealth.dwe.service.WorkflowSubmissionService;
import com.athenahealth.dwe.support.PostgresTestConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "test"})
@Import(PostgresTestConfiguration.class)
public abstract class IntegrationTestSupport {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WorkflowStore store;
    @Autowired protected WorkflowQueries queries;
    @Autowired protected WorkflowSubmissionService submissions;
    @Autowired protected HandlerRegistry registry;
    @Autowired protected EngineProperties properties;
    @Autowired protected JsonMapper json;
    @LocalServerPort protected int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE workflow_execution, workflow_cycle, step_execution, step_dependency, step_attempt CASCADE");
    }

    protected UUID submit(String steps) {
        return submissions.submit(json.readTree("{\"steps\":" + steps + "}"), json.createObjectNode()).executionId();
    }

    protected UUID submitOne() { return submit("[{\"id\":\"a\",\"actionType\":\"ECHO\"}]"); }

    protected HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10));
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
}
