/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import java.util.List;

public record OrchestrationPolicy(
        OrchestrationMode mode,
        List<SubagentBinding> subagents,
        List<RouteRule> routes,
        List<WorkflowStep> workflow) {

    public static OrchestrationPolicy single() {
        return new OrchestrationPolicy(OrchestrationMode.SINGLE, List.of(), List.of(), List.of());
    }

    public OrchestrationPolicy {
        mode = mode == null ? OrchestrationMode.SINGLE : mode;
        subagents = subagents == null ? List.of() : List.copyOf(subagents);
        routes = routes == null ? List.of() : List.copyOf(routes);
        workflow = workflow == null ? List.of() : List.copyOf(workflow);
    }
}
