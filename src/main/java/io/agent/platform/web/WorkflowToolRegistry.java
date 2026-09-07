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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Registry for explicit Workflow-as-Tool bindings. */
@Component
public class WorkflowToolRegistry {

    private static final String SQLITE_TABLE = "platform_workflow_tools";

    private final WorkflowAssetService workflowAssetService;
    private final AgentDefinitionRegistry agentRegistry;
    private final PlatformStorageLayer storage;
    private final PlatformAssetAccessService assetAccess;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WorkflowToolRegistration> registrations = new ConcurrentHashMap<>();

    public WorkflowToolRegistry(
            WorkflowAssetService workflowAssetService,
            AgentDefinitionRegistry agentRegistry,
            PlatformStorageLayer storage) {
        this(workflowAssetService, agentRegistry, storage, null);
    }

    @Autowired
    public WorkflowToolRegistry(
            WorkflowAssetService workflowAssetService,
            AgentDefinitionRegistry agentRegistry,
            PlatformStorageLayer storage,
            PlatformAssetAccessService assetAccess) {
        this.workflowAssetService = workflowAssetService;
        this.agentRegistry = agentRegistry;
        this.storage = storage;
        this.assetAccess = assetAccess;
        storage.initializeSqliteSchema(
                "CREATE TABLE IF NOT EXISTS "
                        + SQLITE_TABLE
                        + " (tool_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL)");
    }

    @PostConstruct
    private void load() {
        if (storage.isSqliteEnabled()) {
            loadSqlite();
            if (!registrations.isEmpty()) return;
        }
        Path path = storage.cacheRoot().resolve("workflow-tools.json");
        if (!Files.exists(path)) return;
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            rows.stream().map(this::fromMap).filter(java.util.Objects::nonNull)
                    .forEach(item -> registrations.put(item.toolId(), item));
            if (storage.isSqliteEnabled() && !registrations.isEmpty()) persist();
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
        workflowAssetService.requirePublished(registration.workflowId());
        workflowAssetService.requirePublishedVersion(
                registration.workflowId(), registration.workflowVersion());
        return registration;
    }

    public WorkflowAsset workflowForAgent(String toolId, String agentId) {
        WorkflowToolRegistration registration = requireForAgent(toolId, agentId);
        return workflowAssetService.requirePublishedVersion(
                registration.workflowId(), registration.workflowVersion());
    }

    public synchronized WorkflowToolRegistration register(Map<String, Object> payload) {
        return register(payload, null);
    }

    public synchronized WorkflowToolRegistration register(
            Map<String, Object> payload, PlatformAuthService.Principal principal) {
        if (principal != null) PlatformRolePolicy.requireBuilder(principal);
        String workflowId = string(payload, "workflow_id");
        int requestedVersion = number(payload.get("workflow_version"), 0);
        WorkflowAsset activeWorkflow =
                workflowAssetService.requirePublished(workflowId, principal);
        WorkflowAsset workflow =
                requestedVersion > 0
                        ? workflowAssetService.requirePublishedVersion(
                                workflowId, requestedVersion, principal)
                        : activeWorkflow;
        String toolId = string(payload, "tool_id");
        if (toolId.isBlank()) {
            toolId = "workflow_tool_" + UUID.randomUUID().toString().replace("-", "");
        }
        if (registrations.containsKey(toolId)) {
            throw new IllegalArgumentException("Workflow Tool already exists: " + toolId);
        }
        WorkflowToolRegistration registration =
                normalize(toolId, workflow, payload, null, principal);
        validateAgentBindings(registration, workflow, principal);
        registrations.put(toolId, registration);
        try {
            persist();
        } catch (RuntimeException error) {
            registrations.remove(toolId, registration);
            throw error;
        }
        return registration;
    }

    public synchronized WorkflowToolRegistration update(String toolId, Map<String, Object> payload) {
        return update(toolId, payload, null);
    }

    public synchronized WorkflowToolRegistration update(
            String toolId,
            Map<String, Object> payload,
            PlatformAuthService.Principal principal) {
        WorkflowToolRegistration existing = require(toolId);
        requireWritable(existing, principal);
        String workflowId = string(payload, "workflow_id");
        String selectedWorkflowId = workflowId.isBlank() ? existing.workflowId() : workflowId;
        int requestedVersion =
                number(payload.get("workflow_version"), existing.workflowVersion());
        workflowAssetService.requirePublished(selectedWorkflowId, principal);
        WorkflowAsset workflow =
                workflowAssetService.requirePublishedVersion(
                        selectedWorkflowId, requestedVersion, principal);
        WorkflowToolRegistration registration =
                normalize(toolId, workflow, payload, existing, principal);
        validateAgentBindings(registration, workflow, principal);
        registrations.put(toolId, registration);
        try {
            persist();
        } catch (RuntimeException error) {
            registrations.put(toolId, existing);
            throw error;
        }
        return registration;
    }

    public synchronized void delete(String toolId) {
        delete(toolId, null);
    }

    public synchronized void delete(
            String toolId, PlatformAuthService.Principal principal) {
        requireWritable(require(toolId), principal);
        WorkflowToolRegistration removed = registrations.remove(toolId);
        try {
            persist();
        } catch (RuntimeException error) {
            registrations.put(toolId, removed);
            throw error;
        }
    }

    public List<Map<String, Object>> rows() {
        return rows(null);
    }

    public List<Map<String, Object>> rows(PlatformAuthService.Principal principal) {
        return all().stream().filter(item -> principal == null || canRead(item, principal)).map(registration -> {
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
            row.put("owner_id", registration.ownerId());
            row.put("org_id", registration.orgId());
            row.put("visibility", registration.visibility());
            row.put("created_at", registration.createdAt());
            row.put("updated_at", registration.updatedAt());
            return row;
        }).toList();
    }

    private WorkflowToolRegistration normalize(
            String toolId,
            WorkflowAsset workflow,
            Map<String, Object> payload,
            WorkflowToolRegistration existing,
            PlatformAuthService.Principal principal) {
        List<String> agents = stringList(payload.get("allowed_agents"));
        if (!payload.containsKey("allowed_agents") && existing != null) agents = existing.allowedAgents();
        boolean enabled = payload.containsKey("enabled")
                ? Boolean.TRUE.equals(payload.get("enabled"))
                : existing == null || existing.enabled();
        String now = java.time.Instant.now().toString();
        String visibility =
                existing == null
                        ? requestedVisibility(payload, principal)
                        : payload.containsKey("visibility")
                                ? requestedVisibility(payload, principal)
                                : existing.visibility();
        return new WorkflowToolRegistration(
                toolId,
                workflow.workflowId(),
                workflow.version(),
                string(payload, "name", existing == null ? toolId : existing.name()),
                string(payload, "description", existing == null ? workflow.description() : existing.description()),
                workflow.inputSchema(),
                agents,
                enabled,
                enabled ? "ACTIVE" : "DISABLED",
                existing == null
                        ? principal == null ? "platform" : principal.userId()
                        : existing.ownerId(),
                existing == null
                        ? principal == null ? "platform" : principal.orgId()
                        : existing.orgId(),
                visibility,
                existing == null ? now : existing.createdAt(),
                now);
    }

    private void validateAgentBindings(
            WorkflowToolRegistration registration,
            WorkflowAsset workflow,
            PlatformAuthService.Principal principal) {
        if (principal != null
                && !"PLATFORM_ADMIN".equals(principal.role())
                && registration.allowedAgents().isEmpty()) {
            throw new PlatformAuthService.AuthException(
                    400, "非平台管理员创建 Workflow Tool 时必须明确绑定 Agent");
        }
        for (String agentId : registration.allowedAgents()) {
            if (agentRegistry.findPublished(agentId).isEmpty()) {
                throw new IllegalArgumentException("Workflow Tool allowed Agent not found: " + agentId);
            }
            if (principal != null && assetAccess != null) {
                assetAccess.requireWritable("AGENT", agentId, principal);
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
        if (storage.isSqliteEnabled()) {
            persistSqlite();
            return;
        }
        Path path = storage.cacheRoot().resolve("workflow-tools.json");
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), all().stream().map(this::toMap).toList());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist Workflow Tool registrations: " + path, e);
        }
    }

    private void loadSqlite() {
        String sql = "SELECT payload FROM " + SQLITE_TABLE + " ORDER BY tool_id";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                Map<String, Object> row =
                        objectMapper.readValue(
                                resultSet.getString("payload"), new TypeReference<>() {});
                WorkflowToolRegistration item = fromMap(row);
                if (item != null) registrations.put(item.toolId(), item);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load Workflow Tool registrations", error);
        }
    }

    private void persistSqlite() {
        String insert =
                "INSERT INTO "
                        + SQLITE_TABLE
                        + " (tool_id,payload,updated_at) VALUES (?,?,?)";
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + SQLITE_TABLE)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                String now = java.time.Instant.now().toString();
                for (WorkflowToolRegistration item : all()) {
                    statement.setString(1, item.toolId());
                    statement.setString(2, objectMapper.writeValueAsString(toMap(item)));
                    statement.setString(3, now);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Failed to persist Workflow Tool registrations", error);
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
        row.put("owner_id", item.ownerId());
        row.put("org_id", item.orgId());
        row.put("visibility", item.visibility());
        row.put("created_at", item.createdAt());
        row.put("updated_at", item.updatedAt());
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
                string(row, "status", "DISABLED"),
                string(row, "owner_id", "platform"),
                string(row, "org_id", "platform"),
                string(row, "visibility", "PUBLIC"),
                string(row, "created_at", ""),
                string(row, "updated_at", ""));
    }

    private static boolean canRead(
            WorkflowToolRegistration item, PlatformAuthService.Principal principal) {
        return "PLATFORM_ADMIN".equals(principal.role())
                || "PUBLIC".equals(item.visibility())
                || ("ORGANIZATION".equals(item.visibility())
                        && item.orgId().equals(principal.orgId()))
                || item.ownerId().equals(principal.userId());
    }

    private static void requireWritable(
            WorkflowToolRegistration item, PlatformAuthService.Principal principal) {
        if (principal == null || "PLATFORM_ADMIN".equals(principal.role())) return;
        PlatformRolePolicy.requireBuilder(principal);
        if (!item.ownerId().equals(principal.userId())
                && !("ORG_ADMIN".equals(principal.role())
                        && item.orgId().equals(principal.orgId()))) {
            throw new PlatformAuthService.AuthException(403, "没有权限修改该 Workflow Tool");
        }
    }

    private static String requestedVisibility(
            Map<String, Object> payload, PlatformAuthService.Principal principal) {
        if (principal == null) return "PUBLIC";
        String requested = string(payload, "visibility", "PRIVATE").toUpperCase();
        if ("PUBLIC".equals(requested) && !"PLATFORM_ADMIN".equals(principal.role())) {
            return "PRIVATE";
        }
        if ("ORGANIZATION".equals(requested)
                && !List.of("PLATFORM_ADMIN", "ORG_ADMIN").contains(principal.role())) {
            return "PRIVATE";
        }
        return List.of("PRIVATE", "ORGANIZATION", "PUBLIC").contains(requested)
                ? requested
                : "PRIVATE";
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
