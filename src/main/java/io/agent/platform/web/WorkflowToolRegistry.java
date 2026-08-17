package io.agent.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.control.WorkflowToolRegistration;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Registry for explicit Workflow-as-Tool bindings. */
@Component
public class WorkflowToolRegistry {

    private final WorkflowAssetService workflowAssetService;
    private final AgentDefinitionRegistry agentRegistry;
    private final PlatformStorageLayer storage;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WorkflowToolRegistration> registrations = new ConcurrentHashMap<>();

    public WorkflowToolRegistry(
            WorkflowAssetService workflowAssetService,
            AgentDefinitionRegistry agentRegistry,
            PlatformStorageLayer storage) {
        this.workflowAssetService = workflowAssetService;
        this.agentRegistry = agentRegistry;
        this.storage = storage;
    }

    @PostConstruct
    private void load() {
        Path path = storage.cacheRoot().resolve("workflow-tools.json");
        if (!Files.exists(path)) return;
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            rows.stream().map(this::fromMap).filter(java.util.Objects::nonNull)
                    .forEach(item -> registrations.put(item.toolId(), item));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Workflow Tool registrations: " + path, e);
        }
    }

    public List<WorkflowToolRegistration> all() {
        return registrations.values().stream()
                .sorted(Comparator.comparing(WorkflowToolRegistration::toolId))
                .toList();
    }

    public WorkflowToolRegistration require(String toolId) {
        WorkflowToolRegistration registration = registrations.get(toolId);
        if (registration == null) {
            throw new IllegalArgumentException("Workflow Tool not found: " + toolId);
        }
        return registration;
    }

    public java.util.Optional<WorkflowToolRegistration> find(String toolId) {
        return java.util.Optional.ofNullable(registrations.get(toolId));
    }

    public WorkflowToolRegistration requireForAgent(String toolId, String agentId) {
        WorkflowToolRegistration registration = require(toolId);
        if (!registration.allows(agentId)) {
            throw new IllegalArgumentException(
                    "Workflow Tool is not allowed for Agent: " + toolId + " -> " + agentId);
        }
        WorkflowAsset workflow = workflowAssetService.requirePublished(registration.workflowId());
        if (workflow.version() != registration.workflowVersion()) {
            throw new IllegalArgumentException(
                    "Workflow Tool points to an unpublished or changed Workflow version: " + toolId);
        }
        return registration;
    }

    public WorkflowAsset workflowForAgent(String toolId, String agentId) {
        WorkflowToolRegistration registration = requireForAgent(toolId, agentId);
        return workflowAssetService.requirePublished(registration.workflowId());
    }

    public synchronized WorkflowToolRegistration register(Map<String, Object> payload) {
        String workflowId = string(payload, "workflow_id");
        WorkflowAsset workflow = workflowAssetService.requirePublished(workflowId);
        String toolId = string(payload, "tool_id");
        if (toolId.isBlank()) {
            toolId = "workflow_tool_" + UUID.randomUUID().toString().replace("-", "");
        }
        if (registrations.containsKey(toolId)) {
            throw new IllegalArgumentException("Workflow Tool already exists: " + toolId);
        }
        WorkflowToolRegistration registration = normalize(toolId, workflow, payload, null);
        validateAgentBindings(registration, workflow);
        registrations.put(toolId, registration);
        persist();
        return registration;
    }

    public synchronized WorkflowToolRegistration update(String toolId, Map<String, Object> payload) {
        WorkflowToolRegistration existing = require(toolId);
        String workflowId = string(payload, "workflow_id");
        WorkflowAsset workflow = workflowAssetService.requirePublished(
                workflowId.isBlank() ? existing.workflowId() : workflowId);
        WorkflowToolRegistration registration = normalize(toolId, workflow, payload, existing);
        validateAgentBindings(registration, workflow);
        registrations.put(toolId, registration);
        persist();
        return registration;
    }

    public synchronized void delete(String toolId) {
        require(toolId);
        registrations.remove(toolId);
        persist();
    }

    public List<Map<String, Object>> rows() {
        return all().stream().map(registration -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("tool_id", registration.toolId());
            row.put("type", "workflow");
            row.put("name", registration.name());
            row.put("description", registration.description());
            row.put("enabled", registration.enabled());
            row.put("status", registration.status());
            row.put("workflow_id", registration.workflowId());
            row.put("workflow_version", registration.workflowVersion());
            row.put("parameter_schema", registration.inputSchema());
            row.put("allowed_agents", registration.allowedAgents());
            return row;
        }).toList();
    }

    private WorkflowToolRegistration normalize(
            String toolId,
            WorkflowAsset workflow,
            Map<String, Object> payload,
            WorkflowToolRegistration existing) {
        List<String> agents = stringList(payload.get("allowed_agents"));
        if (!payload.containsKey("allowed_agents") && existing != null) agents = existing.allowedAgents();
        boolean enabled = payload.containsKey("enabled")
                ? Boolean.TRUE.equals(payload.get("enabled"))
                : existing == null || existing.enabled();
        return new WorkflowToolRegistration(
                toolId,
                workflow.workflowId(),
                workflow.version(),
                string(payload, "name", existing == null ? toolId : existing.name()),
                string(payload, "description", existing == null ? workflow.description() : existing.description()),
                workflow.inputSchema(),
                agents,
                enabled,
                enabled ? "ACTIVE" : "DISABLED");
    }

    private void validateAgentBindings(
            WorkflowToolRegistration registration, WorkflowAsset workflow) {
        for (String agentId : registration.allowedAgents()) {
            if (agentRegistry.findPublished(agentId).isEmpty()) {
                throw new IllegalArgumentException("Workflow Tool allowed Agent not found: " + agentId);
            }
        }
        for (var node : workflow.nodes()) {
            if (node.type() != io.agent.platform.control.WorkflowNodeType.AGENT_INVOKE
                    && node.type() != io.agent.platform.control.WorkflowNodeType.REACT_AGENT) {
                continue;
            }
            var agent = agentRegistry.findPublished(node.refId()).orElse(null);
            if (agent != null && agent.toolRefs().contains(registration.toolId())) {
                throw new IllegalArgumentException(
                        "Workflow Tool would create a cycle through Agent: "
                                + registration.toolId() + " -> " + agent.agentId());
            }
        }
    }

    private void persist() {
        Path path = storage.cacheRoot().resolve("workflow-tools.json");
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), all().stream().map(this::toMap).toList());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist Workflow Tool registrations: " + path, e);
        }
    }

    private Map<String, Object> toMap(WorkflowToolRegistration item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tool_id", item.toolId());
        row.put("workflow_id", item.workflowId());
        row.put("workflow_version", item.workflowVersion());
        row.put("name", item.name());
        row.put("description", item.description());
        row.put("input_schema", item.inputSchema());
        row.put("allowed_agents", item.allowedAgents());
        row.put("enabled", item.enabled());
        row.put("status", item.status());
        return row;
    }

    private WorkflowToolRegistration fromMap(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return null;
        String toolId = string(row, "tool_id", "");
        String workflowId = string(row, "workflow_id", "");
        if (toolId.isBlank() || workflowId.isBlank()) return null;
        return new WorkflowToolRegistration(
                toolId,
                workflowId,
                number(row.get("workflow_version"), 1),
                string(row, "name", toolId),
                string(row, "description", ""),
                map(row.get("input_schema")),
                stringList(row.get("allowed_agents")),
                Boolean.TRUE.equals(row.get("enabled")),
                string(row, "status", "DISABLED"));
    }

    private static String string(Map<String, Object> row, String key, String fallback) {
        Object value = row == null ? null : row.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static int number(Object value, int fallback) {
        try { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String string(Map<String, Object> payload, String key) {
        return string(payload, key, "");
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
        return List.copyOf(result);
    }
}
