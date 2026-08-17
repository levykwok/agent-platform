package io.agent.platform.control;

import java.util.List;
import java.util.Map;

/** Published Workflow exposed to Agents through a stable tool contract. */
public record WorkflowToolRegistration(
        String toolId,
        String workflowId,
        int workflowVersion,
        String name,
        String description,
        Map<String, Object> inputSchema,
        List<String> allowedAgents,
        boolean enabled,
        String status) {

    public WorkflowToolRegistration {
        toolId = toolId == null ? "" : toolId.trim();
        workflowId = workflowId == null ? "" : workflowId.trim();
        workflowVersion = workflowVersion <= 0 ? 1 : workflowVersion;
        name = name == null || name.isBlank() ? toolId : name.trim();
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        allowedAgents = allowedAgents == null ? List.of() : List.copyOf(allowedAgents);
        status = "ACTIVE".equalsIgnoreCase(status) ? "ACTIVE" : "DISABLED";
    }

    public boolean allows(String agentId) {
        return enabled && "ACTIVE".equals(status)
                && (allowedAgents.isEmpty() || allowedAgents.contains(agentId));
    }
}
