/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;

public record SubagentBinding(
        String bindingId,
        String targetAgentId,
        String role,
        String description,
        boolean exposeToUser,
        List<String> toolRefs) {

    public SubagentBinding {
        toolRefs = toolRefs == null ? List.of() : List.copyOf(toolRefs);
    }
}
