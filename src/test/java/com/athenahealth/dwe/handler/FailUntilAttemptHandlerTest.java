package com.athenahealth.dwe.handler;

import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.handler.demo.FailUntilAttemptHandler;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Documents the demo handler's parameter contract and stateless lifetime-attempt behavior. */
class FailUntilAttemptHandlerTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final FailUntilAttemptHandler handler = new FailUntilAttemptHandler();

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"failAttempts\":null}", "{\"failAttempts\":-1}",
            "{\"failAttempts\":1.5}", "{\"failAttempts\":\"1\"}", "{\"failAttempts\":true}",
            "{\"failAttempts\":9223372036854775808}", "{\"failAttempts\":1,\"retryable\":null}",
            "{\"failAttempts\":1,\"retryable\":\"true\"}"})
    void rejectsInvalidParameters(String parameters) {
        assertThatThrownBy(() -> handler.validateParameters(json.readTree(parameters)))
                .isInstanceOf(StepValidationException.class);
    }

    /** A new handler object still fails the same attempt; there is no process-local failure counter. */
    @Test
    void configuredFailureIsRetryableByDefault() throws Exception {
        var context = context(2, 1, "{\"failAttempts\":2}");
        handler.validateParameters(context.parameters());
        assertThatThrownBy(() -> new FailUntilAttemptHandler().execute(context))
                .isInstanceOfSatisfying(StepExecutionException.class, error -> {
                    assertThat(error.code()).isEqualTo("DEMO_FAILURE");
                    assertThat(error.retryable()).isTrue();
                });
    }

    @Test
    void canDemonstrateAPermanentFailure() {
        assertThatThrownBy(() -> handler.execute(context(1, 1, "{\"failAttempts\":1,\"retryable\":false}")))
                .isInstanceOfSatisfying(StepExecutionException.class, error -> assertThat(error.retryable()).isFalse());
    }

    /** Lifetime attempt 4 succeeds even when it is the first attempt of a new cycle. */
    @Test
    void successUsesDurableAttemptIdentityAcrossCycles() throws Exception {
        var context = context(4, 2, "{\"failAttempts\":3}");
        var result = handler.execute(context);
        assertThat(result.get("attemptNo").longValue()).isEqualTo(4);
        assertThat(result.get("cycleNo").intValue()).isEqualTo(2);
        assertThat(result.get("idempotencyKey").stringValue()).isEqualTo(context.executionId() + ":a");
    }

    @Test
    void cancelledInvocationDoesNotPerformBusinessExecution() {
        var original = context(1, 1, "{\"failAttempts\":0}");
        var claim = new StepClaim(original.executionId(), "a", "FAIL_UNTIL_ATTEMPT", 1, 1, "owner",
                json.createObjectNode(), original.parameters(), Map.of(), System.nanoTime());
        assertThatThrownBy(() -> handler.execute(new StepContext(claim, () -> true)))
                .isInstanceOf(InterruptedException.class);
    }

    private StepContext context(long attempt, int cycle, String parameters) {
        return new StepContext(new StepClaim(UUID.randomUUID(), "a", "FAIL_UNTIL_ATTEMPT", cycle, attempt, "owner",
                json.createObjectNode(), json.readTree(parameters), Map.of(), System.nanoTime()), () -> false);
    }
}
