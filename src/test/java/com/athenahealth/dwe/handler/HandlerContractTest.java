package com.athenahealth.dwe.handler;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import com.athenahealth.dwe.domain.RetryPolicy;
import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.handler.StepContext;
import com.athenahealth.dwe.handler.demo.DelayHandler;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class HandlerContractTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void eachInvocationGetsIsolatedJsonAndStableIdempotencyKey() {
        UUID id = UUID.randomUUID();
        var claim = new StepClaim(id, "b", "ECHO", 1, 1, "w", json.createObjectNode().put("v", 1),
                json.createObjectNode(), Map.of("a", json.createObjectNode().put("result", 2)), System.nanoTime());
        var first = new StepContext(claim, () -> false);
        ((ObjectNode) first.workflowInputs()).put("v", 9);
        ((ObjectNode) first.dependencyOutputs().get("a")).put("result", 8);
        var second = new StepContext(new StepClaim(id, "b", "ECHO", 2, 3, "w2", claim.workflowInputs(),
                claim.parameters(), claim.dependencyOutputs(), System.nanoTime()), () -> false);
        assertThat(second.workflowInputs().get("v").intValue()).isEqualTo(1);
        assertThat(second.dependencyOutputs().get("a").get("result").intValue()).isEqualTo(2);
        assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(second.attemptNo()).isEqualTo(3);
    }

    @Test
    void cancellationIsCooperative() {
        var claim = new StepClaim(UUID.randomUUID(), "a", "DELAY", 1, 1, "w", json.createObjectNode(),
                json.createObjectNode().put("durationMs", 10), Map.of(), System.nanoTime());
        assertThatThrownBy(() -> new DelayHandler().execute(new StepContext(claim, () -> true)))
                .isInstanceOf(InterruptedException.class);
    }

    @Test
    void exponentialBackoffSaturatesAtCapForLargeAttemptCounts() {
        var policy = new RetryPolicy(3, RetryPolicy.MAX_BACKOFF_MS / 2 + 1, RetryPolicy.MAX_BACKOFF_MS);
        assertThat(policy.delayAfter(1)).isEqualTo(RetryPolicy.MAX_BACKOFF_MS / 2 + 1);
        assertThat(policy.delayAfter(2)).isEqualTo(RetryPolicy.MAX_BACKOFF_MS);
        assertThat(policy.delayAfter(Integer.MAX_VALUE)).isEqualTo(RetryPolicy.MAX_BACKOFF_MS);
    }
}
