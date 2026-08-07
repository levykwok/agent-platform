/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A typed connection point exposed by a workflow node. */
public record WorkflowPort(
        String portId,
        String direction,
        String contractRef,
        Map<String, Object> schema,
        boolean required,
        String cardinality,
        String description) {

    public WorkflowPort {
        portId = portId == null ? "" : portId.trim();
        direction = normalize(direction, "input");
        contractRef = contractRef == null ? "" : contractRef.trim();
        schema = schema == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(schema));
        cardinality = normalize(cardinality, "one");
        description = description == null ? "" : description;
    }

    public WorkflowPort(
            String portId,
            String direction,
            String contractRef,
            Map<String, Object> schema,
            boolean required,
            String description) {
        this(portId, direction, contractRef, schema, required, "one", description);
    }

    public boolean input() {
        return "input".equals(direction);
    }

    public boolean output() {
        return "output".equals(direction);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
