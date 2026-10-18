package com.athenahealth.dwe.handler;

import java.util.List;
import org.junit.jupiter.api.Test;

import com.athenahealth.dwe.handler.HandlerRegistry;
import com.athenahealth.dwe.handler.demo.*;

import static org.assertj.core.api.Assertions.*;

class HandlerRegistryTest {
    @Test
    void duplicateRegistrationsFailAtStartup() {
        assertThatThrownBy(() -> new HandlerRegistry(List.of(new EchoHandler(), new EchoHandler())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registeredTypesResolveAndUnknownTypesFail() {
        var registry = new HandlerRegistry(List.of(new EchoHandler(), new DelayHandler()));
        assertThat(registry.get("ECHO")).isInstanceOf(EchoHandler.class);
        assertThat(registry.contains("custom")).isFalse();
        assertThatThrownBy(() -> registry.get("custom")).isInstanceOf(IllegalArgumentException.class);
    }
}
