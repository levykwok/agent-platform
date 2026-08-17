/*
 * Copyright 2026 by the company contributors.
 */
package io.agent.platform.control;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detects cycles in the published Agent/Workflow call graph. */
final class OrchestrationCycleValidator {

    private OrchestrationCycleValidator() {}

    static void validate(Map<String, AgentDefinition> definitions) {
        Set<String> visited = new LinkedHashSet<>();
        for (String agentId : definitions.keySet()) {
            visit(agentId, definitions, visited, new ArrayList<>());
        }
    }

    private static void visit(
            String agentId,
            Map<String, AgentDefinition> definitions,
            Set<String> visited,
            List<String> path) {
        if (visited.contains(agentId)) {
            return;
        }
        int cycleStart = path.indexOf(agentId);
        if (cycleStart >= 0) {
            List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
            cycle.add(agentId);
            throw new IllegalStateException(
                    "Orchestration cycle detected: " + String.join(" -> ", cycle));
        }
        AgentDefinition definition = definitions.get(agentId);
        if (definition == null) {
            return;
        }
        path.add(agentId);
        for (String target : targets(definition)) {
            visit(target, definitions, visited, path);
        }
        path.remove(path.size() - 1);
        visited.add(agentId);
    }

    private static Set<String> targets(AgentDefinition definition) {
        Set<String> targets = new LinkedHashSet<>();
        OrchestrationPolicy policy = definition.orchestration();
        switch (policy.mode()) {
            case ROUTER -> policy.routes().forEach(route -> targets.add(route.targetAgentId()));
            case SUPERVISOR ->
                    policy.subagents().forEach(binding -> targets.add(binding.targetAgentId()));
            case WORKFLOW -> policy.workflow().forEach(step -> targets.add(step.agentId()));
            case SINGLE -> {}
        }
        targets.removeIf(target -> target == null || target.isBlank());
        return targets;
    }
}
