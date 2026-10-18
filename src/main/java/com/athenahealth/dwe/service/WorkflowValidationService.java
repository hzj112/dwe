package com.athenahealth.dwe.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

import com.athenahealth.dwe.config.EngineProperties;
import com.athenahealth.dwe.domain.RetryPolicy;
import com.athenahealth.dwe.domain.StepDefinition;
import com.athenahealth.dwe.domain.WorkflowDefinition;
import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.handler.StepValidationException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Checks submitted JSON, registered handlers, retry settings, and dependency graph before persistence. */
@Service
public class WorkflowValidationService {
    private final HandlerRegistry handlers;
    private final EngineProperties defaults;

    public WorkflowValidationService(HandlerRegistry handlers, EngineProperties defaults) {
        this.handlers = handlers;
        this.defaults = defaults;
    }

    /** Identifies one invalid field using a path such as definition.steps[0].id. */
    public record Violation(String path, String message) { }
    /** Carries both the original definition snapshot and the internal definition with defaults applied. */
    public record ValidatedSubmission(JsonNode definitionSnapshot, JsonNode inputs, WorkflowDefinition definition) { }

    public static final class InvalidWorkflowException extends RuntimeException {
        private final List<Violation> violations;

        public InvalidWorkflowException(List<Violation> violations) {
            super("Workflow definition is invalid");
            this.violations = List.copyOf(violations);
        }

        public List<Violation> violations() { return violations; }
    }

    /** Returns a validated submission or reports validation problems without writing any database rows. */
    public ValidatedSubmission validate(JsonNode definition, JsonNode inputs) {
        var errors = new ArrayList<Violation>();
        if (definition == null || !definition.isObject()) {
            throw invalid("definition", "A definition object is required");
        }
        fields(definition, Set.of("steps"), "definition", errors);
        JsonNode stepsNode = definition.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw invalid("definition.steps", "A nonempty array of steps is required");
        }
        JsonNode normalizedInputs = inputs == null ? JsonNodeFactory.instance.objectNode() : inputs;
        if (!normalizedInputs.isObject()) {
            errors.add(new Violation("inputs", "Expected a JSON object"));
        }
        var steps = new LinkedHashMap<String, StepDefinition>();
        for (int i = 0; i < stepsNode.size(); i++) {
            JsonNode node = stepsNode.get(i);
            String path = "definition.steps[" + i + "]";
            if (!node.isObject()) {
                errors.add(new Violation(path, "Expected a step object"));
                continue;
            }
            fields(node, Set.of("id", "actionType", "parameters", "dependsOn", "retry"), path, errors);
            String id = nonBlank(node.get("id"), path + ".id", errors);
            String type = nonBlank(node.get("actionType"), path + ".actionType", errors);
            JsonNode parameters = node.get("parameters");
            if (parameters == null) {
                parameters = JsonNodeFactory.instance.objectNode();
            }
            if (!parameters.isObject()) {
                errors.add(new Violation(path + ".parameters", "Expected a JSON object"));
            }
            var dependencies = new ArrayList<String>();
            JsonNode dependenciesNode = node.get("dependsOn");
            if (dependenciesNode != null) {
                if (!dependenciesNode.isArray()) {
                    errors.add(new Violation(path + ".dependsOn", "Expected an array"));
                } else {
                    var seen = new HashSet<String>();
                    for (int j = 0; j < dependenciesNode.size(); j++) {
                        String dependency = nonBlank(dependenciesNode.get(j), path + ".dependsOn[" + j + "]", errors);
                        if (dependency != null) {
                            dependencies.add(dependency);
                            if (!seen.add(dependency)) {
                                errors.add(new Violation(path + ".dependsOn", "Duplicate dependency: " + dependency));
                            }
                        }
                    }
                }
            }
            JsonNode retry = node.get("retry");
            if (retry == null) {
                retry = JsonNodeFactory.instance.objectNode();
            }
            RetryPolicy policy = policy(retry, path + ".retry", errors);
            if (type != null) {
                if (!handlers.contains(type)) {
                    errors.add(new Violation(path + ".actionType", "Unregistered action type: " + type));
                } else if (parameters.isObject()) {
                    try {
                        handlers.get(type).validateParameters(parameters.deepCopy());
                    } catch (StepValidationException e) {
                        errors.add(new Violation(path + ".parameters", e.getMessage()));
                    }
                }
            }
            if (id != null && type != null && policy != null && parameters.isObject()) {
                if (steps.putIfAbsent(id, new StepDefinition(id, type, parameters, dependencies, policy)) != null) {
                    errors.add(new Violation(path + ".id", "Duplicate step ID: " + id));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidWorkflowException(errors);
        }
        // Resolve dependency IDs only after all steps are known; forward references are allowed.
        for (StepDefinition step : steps.values()) {
            for (String dependency : step.dependsOn()) {
                if (!steps.containsKey(dependency) || step.id().equals(dependency)) {
                    errors.add(new Violation("definition.steps", "Invalid dependency " + step.id() + " -> " + dependency));
                }
            }
        }
        if (errors.isEmpty() && hasCycle(steps)) {
            errors.add(new Violation("definition.steps", "Dependencies contain a cycle"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidWorkflowException(errors);
        }
        // Preserve the original payload separately from execution settings that include default values.
        return new ValidatedSubmission(definition.deepCopy(), normalizedInputs.deepCopy(),
                new WorkflowDefinition(new ArrayList<>(steps.values())));
    }

    /** Applies defaults to omitted retry fields individually, then checks the effective combination. */
    private RetryPolicy policy(JsonNode retry, String path, List<Violation> errors) {
        if (!retry.isObject()) {
            errors.add(new Violation(path, "Expected a retry object"));
            return null;
        }
        fields(retry, Set.of("maxAttempts", "backoffInitialMs", "backoffMaxMs"), path, errors);
        Long attempts = positiveInteger(retry.get("maxAttempts"), defaults.defaultMaxAttempts(), Integer.MAX_VALUE,
                path + ".maxAttempts", errors);
        Long initial = positiveInteger(retry.get("backoffInitialMs"), defaults.defaultBackoffInitialMs(), RetryPolicy.MAX_BACKOFF_MS,
                path + ".backoffInitialMs", errors);
        Long maximum = positiveInteger(retry.get("backoffMaxMs"), defaults.defaultBackoffMaxMs(), RetryPolicy.MAX_BACKOFF_MS,
                path + ".backoffMaxMs", errors);
        if (attempts == null || initial == null || maximum == null) {
            return null;
        }
        if (maximum < initial) {
            errors.add(new Violation(path, "Effective maximum backoff must be at least initial backoff"));
            return null;
        }
        return new RetryPolicy(attempts.intValue(), initial, maximum);
    }

    /** Uses a default only for an omitted field; explicit null, fractional, and oversized values are invalid. */
    private static Long positiveInteger(JsonNode node, long fallback, long maximum, String path, List<Violation> errors) {
        if (node == null) {
            return fallback;
        }
        try {
            if (node.isNumber()) {
                long value = node.decimalValue().toBigIntegerExact().longValueExact();
                if (value >= 1 && value <= maximum) {
                    return value;
                }
            }
        } catch (ArithmeticException ignored) {
            // Fractional and out-of-range JSON numbers are validation errors.
        }
        errors.add(new Violation(path, "Expected a positive integer within the supported storage range"));
        return null;
    }

    private static String nonBlank(JsonNode node, String path, List<Violation> errors) {
        if (node != null && node.isString() && !node.stringValue().isBlank()) {
            return node.stringValue();
        }
        errors.add(new Violation(path, "Expected a nonblank string"));
        return null;
    }

    private static void fields(JsonNode node, Set<String> allowed, String path, List<Violation> errors) {
        node.properties().forEach(entry -> {
            if (!allowed.contains(entry.getKey())) {
                errors.add(new Violation(path + "." + entry.getKey(), "Unknown field"));
            }
        });
    }

    /** Removes dependency-free nodes in order; any nodes left over belong to or depend on a cycle. */
    private static boolean hasCycle(Map<String, StepDefinition> steps) {
        var indegree = new HashMap<String, Integer>();
        var children = new HashMap<String, List<String>>();
        steps.values().forEach(step -> {
            indegree.put(step.id(), step.dependsOn().size());
            step.dependsOn().forEach(parent -> children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(step.id()));
        });
        var ready = new ArrayDeque<String>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove();
            visited++;
            for (String child : children.getOrDefault(id, List.of())) {
                if (indegree.compute(child, (ignored, degree) -> degree - 1) == 0) {
                    ready.add(child);
                }
            }
        }
        return visited != steps.size();
    }

    private static InvalidWorkflowException invalid(String path, String message) {
        return new InvalidWorkflowException(List.of(new Violation(path, message)));
    }
}
