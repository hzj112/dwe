package com.athenahealth.dwe.handler;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Maps actionType names to the predefined handler objects discovered by Spring at startup. */
@Component
public final class HandlerRegistry {
    private final Map<String, StepHandler> handlers = new HashMap<>();

    public HandlerRegistry(Collection<StepHandler> predefinedHandlers) {
        predefinedHandlers.forEach(this::register);
    }

    /** Rejects ambiguous or missing names at startup instead of failing later during step execution. */
    private void register(StepHandler handler) {
        String type = handler.actionType();
        if (type == null || type.isBlank() || handlers.putIfAbsent(type, handler) != null) {
            throw new IllegalStateException("Invalid or duplicate handler action type: " + type);
        }
    }

    public boolean contains(String type) { return handlers.containsKey(type); }

    /** Looks up the same handler contract used for submission validation and worker execution. */
    public StepHandler get(String type) {
        StepHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("Unregistered action type: " + type);
        }
        return handler;
    }
}
