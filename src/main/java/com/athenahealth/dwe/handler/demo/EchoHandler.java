package com.athenahealth.dwe.handler.demo;

import org.springframework.stereotype.Component;

import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.StepHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Example ECHO handler that returns its inputs, parameters, and dependency outputs for inspection. */
@Component
public class EchoHandler implements StepHandler {
    @Override
    public String actionType() { return "ECHO"; }

    // ECHO accepts any parameter object; the shared validator already checks that it is an object.
    @Override
    public void validateParameters(JsonNode parameters) { }

    @Override
    public JsonNode execute(StepContext context) throws InterruptedException {
        context.checkCancelled();
        var output = JsonNodeFactory.instance.objectNode();
        output.set("inputs", context.workflowInputs().deepCopy());
        output.set("parameters", context.parameters().deepCopy());
        var dependencies = output.putObject("dependencies");
        context.dependencyOutputs().forEach((key, value) -> dependencies.set(key, value.deepCopy()));
        return output;
    }
}
