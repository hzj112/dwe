package com.athenahealth.dwe.observability;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.athenahealth.dwe.engine.WorkflowCoordinator;
import com.athenahealth.dwe.persistence.WorkflowStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EngineTelemetryTest {
    @Test
    void loopWithoutASuccessStartsWithAStartupGracePeriod() {
        var registry = new SimpleMeterRegistry();
        new EngineTelemetry(registry).loop("coordinator");
        assertThat(registry.get("dwe.loop.last.success.timestamp.seconds").gauge().value()).isZero();
        assertThat(registry.get("dwe.loop.age.seconds").gauge().value()).isBetween(0.0, 30.0);
    }

    @Test
    void failingRegistryDoesNotEscapeIntoEngineDecisions() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("unavailable"));
        var telemetry = new EngineTelemetry(registry);
        assertThatCode(() -> telemetry.transition("attempt_finished", UUID.randomUUID(), "user-step", 1, 1,
                "worker", "COMPLETED")).doesNotThrowAnyException();
    }

    @Test
    void failedPassDoesNotAdvanceLastSuccessfulActivity() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new EngineTelemetry(registry);
        var store = mock(WorkflowStore.class);
        var coordinator = new WorkflowCoordinator(store, telemetry);
        coordinator.advance();
        double last = registry.get("dwe.loop.last.success.timestamp.seconds").gauge().value();
        assertThat(last).isPositive();
        doThrow(new IllegalStateException("database unavailable")).when(store).advance();
        coordinator.advance();
        assertThat(registry.get("dwe.loop.last.success.timestamp.seconds").gauge().value()).isEqualTo(last);
        assertThat(registry.get("dwe.operation.duration").tags("operation", "coordinator", "outcome", "error")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void executionIdentityNeverBecomesMetricLabels() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new EngineTelemetry(registry);
        for (int i = 0; i < 50; i++) {
            telemetry.transition("attempt_finished", UUID.randomUUID(), "step-" + i, 1, i + 1, "worker-" + i, "COMPLETED");
        }
        assertThat(registry.getMeters()).hasSize(1);
        assertThat(registry.get("dwe.events").counter().count()).isEqualTo(50);
        assertThat(registry.getMeters().getFirst().getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("event", "outcome");
    }
}
