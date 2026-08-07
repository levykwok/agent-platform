/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A data or control-flow connection between two workflow ports. */
public record WorkflowEdge(
        String edgeId,
        WorkflowEndpoint from,
        WorkflowEndpoint to,
        String kind,
        Map<String, Object> binding,
        Map<String, Object> condition,
        boolean defaultEdge) {

    public WorkflowEdge {
        edgeId = edgeId == null ? "" : edgeId.trim();
        kind = normalize(kind, "data");
        binding = binding == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(binding));
        condition = condition == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(condition));
    }

    public WorkflowEdge(
            String edgeId,
            WorkflowEndpoint from,
            WorkflowEndpoint to,
            String kind,
            Map<String, Object> binding) {
        this(edgeId, from, to, kind, binding, Map.of(), false);
    }

    public boolean data() {
        return "data".equals(kind);
    }

    public boolean control() {
        return "control".equals(kind);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
