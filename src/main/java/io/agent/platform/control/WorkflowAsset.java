/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A independently managed workflow asset, separate from an AgentDefinition. */
public record WorkflowAsset(
        String workflowId,
        int version,
        String name,
        String description,
        String domain,
        String triggerType,
        String status,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        String createdAt,
        String updatedAt,
        String publishedAt) {

    public WorkflowAsset {
        version = version <= 0 ? 1 : version;
        name = name == null || name.isBlank() ? workflowId : name;
        description = description == null ? "" : description;
        domain = domain == null || domain.isBlank() ? "platform" : domain;
        triggerType = triggerType == null || triggerType.isBlank() ? "manual" : triggerType;
        status = "PUBLISHED".equalsIgnoreCase(status) ? "PUBLISHED" : "DRAFT";
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        createdAt = createdAt == null ? "" : createdAt;
        updatedAt = updatedAt == null ? "" : updatedAt;
        publishedAt = publishedAt == null ? "" : publishedAt;
    }

    /** Backward-compatible constructor for workflows saved before typed edges existed. */
    public WorkflowAsset(
            String workflowId,
            int version,
            String name,
            String description,
            String domain,
            String triggerType,
            String status,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            List<WorkflowNode> nodes,
            String createdAt,
            String updatedAt,
            String publishedAt) {
        this(workflowId, version, name, description, domain, triggerType, status, inputSchema,
                outputSchema, nodes, List.of(), createdAt, updatedAt, publishedAt);
    }

    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflow_id", workflowId);
        metadata.put("version", version);
        metadata.put("name", name);
        metadata.put("description", description);
        metadata.put("domain", domain);
        metadata.put("trigger_type", triggerType);
        metadata.put("status", status);
        metadata.put("created_at", createdAt);
        metadata.put("updated_at", updatedAt);
        metadata.put("published_at", publishedAt);
        return metadata;
    }
}
