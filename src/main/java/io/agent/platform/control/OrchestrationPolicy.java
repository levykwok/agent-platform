/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;

public record OrchestrationPolicy(
        OrchestrationMode mode,
        List<SubagentBinding> subagents,
        List<RouteRule> routes,
        List<WorkflowStep> workflow,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges) {

    public OrchestrationPolicy(
            OrchestrationMode mode,
            List<SubagentBinding> subagents,
            List<RouteRule> routes,
            List<WorkflowStep> workflow) {
        this(mode, subagents, routes, workflow, List.of(), List.of());
    }

    /** Backward-compatible constructor for policies without typed edges. */
    public OrchestrationPolicy(
            OrchestrationMode mode,
            List<SubagentBinding> subagents,
            List<RouteRule> routes,
            List<WorkflowStep> workflow,
            List<WorkflowNode> nodes) {
        this(mode, subagents, routes, workflow, nodes, List.of());
    }

    public static OrchestrationPolicy single() {
        return new OrchestrationPolicy(OrchestrationMode.SINGLE, List.of(), List.of(), List.of());
    }

    public OrchestrationPolicy {
        mode = mode == null ? OrchestrationMode.SINGLE : mode;
        subagents = subagents == null ? List.of() : List.copyOf(subagents);
        routes = routes == null ? List.of() : List.copyOf(routes);
        workflow = workflow == null ? List.of() : List.copyOf(workflow);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** Returns generic nodes while keeping the original workflow YAML schema compatible. */
    public List<WorkflowNode> workflowNodes() {
        if (!nodes.isEmpty()) {
            return nodes;
        }
        return workflow.stream().map(WorkflowNode::fromLegacy).toList();
    }
}
