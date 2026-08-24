/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;
import java.util.Map;

public record SubagentBinding(
        String bindingId,
        String targetAgentId,
        String role,
        String description,
        boolean exposeToUser,
        List<String> toolRefs,
        Map<String, Object> outputSchema) {

    public SubagentBinding(
            String bindingId,
            String targetAgentId,
            String role,
            String description,
            boolean exposeToUser,
            List<String> toolRefs) {
        this(bindingId, targetAgentId, role, description, exposeToUser, toolRefs, Map.of());
    }

    public SubagentBinding {
        toolRefs = toolRefs == null ? List.of() : List.copyOf(toolRefs);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
    }
}
