/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum OrchestrationMode {
    SINGLE,
    SUPERVISOR,
    ROUTER,
    PIPELINE;

    /** Accepts the former Agent-level WORKFLOW name when loading persisted configuration. */
    @JsonCreator
    public static OrchestrationMode fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return SINGLE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("WORKFLOW".equals(normalized)) {
            return PIPELINE;
        }
        return OrchestrationMode.valueOf(normalized);
    }

    @JsonValue
    public String wireValue() {
        return name();
    }
}
