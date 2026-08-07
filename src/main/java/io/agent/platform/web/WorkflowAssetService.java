/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.control.WorkflowContractValidator;
import io.agent.platform.control.WorkflowEdge;
import io.agent.platform.control.WorkflowEndpoint;
import io.agent.platform.control.WorkflowValidationResult;
import io.agent.platform.control.WorkflowNode;
import io.agent.platform.control.WorkflowNodeType;
import io.agent.platform.control.WorkflowTransition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** CRUD and lifecycle service for standalone Workflow assets. */
@Component
public class WorkflowAssetService {

    private static final String SQLITE_TABLE = "platform_workflows";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlatformStorageLayer storage;
    private final AgentDefinitionRegistry agentRegistry;
    private final WorkflowContractValidator contractValidator = new WorkflowContractValidator();
    private final Map<String, WorkflowAsset> workflows = new ConcurrentHashMap<>();

    public WorkflowAssetService(
            PlatformStorageLayer storage, AgentDefinitionRegistry agentRegistry) {
        this.storage = storage;
        this.agentRegistry = agentRegistry;
        if (storage.isSqliteEnabled()) {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_TABLE
                            + " (workflow_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL)");
        }
    }

    @jakarta.annotation.PostConstruct
    private void load() {
        if (storage.isSqliteEnabled()) {
            loadSqlite();
        } else {
            loadFile();
        }
    }

    public List<Map<String, Object>> list(String domain, String status) {
        return workflows.values().stream()
                .filter(asset -> domain == null || domain.isBlank() || domain.equals(asset.domain()))
                .filter(
                        asset ->
                                status == null
                                        || status.isBlank()
                                        || status.equalsIgnoreCase(asset.status()))
                .sorted(Comparator.comparing(WorkflowAsset::updatedAt).reversed())
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> get(String workflowId) {
        return toMap(require(workflowId));
    }

    public WorkflowAsset require(String workflowId) {
        WorkflowAsset asset = workflows.get(workflowId);
        if (asset == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        return asset;
    }

    public WorkflowAsset requirePublished(String workflowId) {
        WorkflowAsset asset = require(workflowId);
        if (!"PUBLISHED".equals(asset.status())) {
            throw new IllegalArgumentException("Workflow is not published: " + workflowId);
        }
        return asset;
    }

    public WorkflowValidationResult validateContracts(String workflowId) {
        return contractValidator.validate(require(workflowId));
    }

    public WorkflowValidationResult validateContracts(
            String workflowId, Map<String, Object> payload) {
        WorkflowAsset existing = require(workflowId);
        return contractValidator.validate(
                normalize(workflowId, payload == null ? Map.of() : payload, existing));
    }

    public synchronized Map<String, Object> create(Map<String, Object> payload) {
        String workflowId = workflowId(payload, "workflow_" + UUID.randomUUID().toString().replace("-", ""));
        if (workflows.containsKey(workflowId)) {
            throw new IllegalArgumentException("Workflow already exists: " + workflowId);
        }
        return save(workflowId, payload);
    }

    public synchronized Map<String, Object> save(String workflowId, Map<String, Object> payload) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflow_id is required");
        }
        WorkflowAsset existing = workflows.get(workflowId);
        WorkflowAsset asset = normalize(workflowId, payload == null ? Map.of() : payload, existing);
        validate(asset, false);
        workflows.put(workflowId, asset);
        persist(asset);
        return toMap(asset);
    }

    public synchronized Map<String, Object> publish(String workflowId) {
        WorkflowAsset existing = require(workflowId);
        validate(existing, true);
        WorkflowAsset published =
                new WorkflowAsset(
                        existing.workflowId(),
                        existing.version() + 1,
                        existing.name(),
                        existing.description(),
                        existing.domain(),
                        existing.triggerType(),
                        "PUBLISHED",
                        existing.inputSchema(),
                        existing.outputSchema(),
                        existing.nodes(),
                        existing.edges(),
                        existing.createdAt(),
                        Instant.now().toString(),
                        Instant.now().toString());
        workflows.put(workflowId, published);
        try {
            validateWorkflowCycles();
            persist(published);
        } catch (RuntimeException error) {
            workflows.put(workflowId, existing);
            throw error;
        }
        return toMap(published);
    }

    public synchronized Map<String, Object> unpublish(String workflowId) {
        WorkflowAsset existing = require(workflowId);
        WorkflowAsset draft =
                new WorkflowAsset(
                        existing.workflowId(),
                        existing.version(),
                        existing.name(),
                        existing.description(),
                        existing.domain(),
                        existing.triggerType(),
                        "DRAFT",
                        existing.inputSchema(),
                        existing.outputSchema(),
                        existing.nodes(),
                        existing.edges(),
                        existing.createdAt(),
                        Instant.now().toString(),
                        existing.publishedAt());
        workflows.put(workflowId, draft);
        persist(draft);
        return toMap(draft);
    }

    public synchronized void delete(String workflowId) {
        require(workflowId);
        workflows.remove(workflowId);
        if (storage.isSqliteEnabled()) {
            try (Connection connection = storage.connection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "DELETE FROM " + SQLITE_TABLE + " WHERE workflow_id = ?")) {
                statement.setString(1, workflowId);
                statement.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to delete workflow: " + workflowId, e);
            }
        } else {
            persistFile();
        }
    }

    private WorkflowAsset normalize(
            String workflowId, Map<String, Object> payload, WorkflowAsset existing) {
        String now = Instant.now().toString();
        List<WorkflowNode> nodes = nodes(payload.get("nodes"));
        List<WorkflowEdge> edges = edges(payload.get("edges"));
        Map<String, Object> requestedInputSchema =
                objectMap(payload.get("input_schema"), existing == null ? Map.of() : existing.inputSchema());
        Map<String, Object> requestedOutputSchema =
                objectMap(payload.get("output_schema"), existing == null ? Map.of() : existing.outputSchema());
        Map<String, Object> inputSchema = boundarySchema(nodes, WorkflowNodeType.INPUT, requestedInputSchema);
        Map<String, Object> outputSchema = boundarySchema(nodes, WorkflowNodeType.OUTPUT, requestedOutputSchema);
        // Editing always creates/updates a draft; publishing is an explicit lifecycle action.
        String status = "DRAFT";
        return new WorkflowAsset(
                workflowId,
                integer(payload, "version", existing == null ? 1 : existing.version()),
                string(payload, "name", existing == null ? workflowId : existing.name()),
                string(payload, "description", existing == null ? "" : existing.description()),
                string(payload, "domain", existing == null ? "platform" : existing.domain()),
                string(payload, "trigger_type", existing == null ? "manual" : existing.triggerType()),
                status,
                inputSchema,
                outputSchema,
                nodes.isEmpty() && existing != null && !payload.containsKey("nodes")
                        ? existing.nodes()
                        : nodes,
                edges.isEmpty() && existing != null && !payload.containsKey("edges")
                        ? existing.edges()
                        : edges,
                existing == null ? now : existing.createdAt(),
                now,
                existing == null ? "" : existing.publishedAt());
    }

    private Map<String, Object> boundarySchema(
            List<WorkflowNode> nodes,
            WorkflowNodeType boundaryType,
            Map<String, Object> fallback) {
        for (WorkflowNode node : nodes) {
            if (node != null && node.type() == boundaryType) {
                return objectMap(node.config().get("schema"), Map.of());
            }
        }
        return fallback;
    }

    private void validate(WorkflowAsset asset, boolean publishing) {
        if (asset.name().isBlank()) {
            throw new IllegalArgumentException("Workflow name is required");
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < asset.nodes().size(); i++) {
            WorkflowNode node = asset.nodes().get(i);
            if (node.nodeId() == null || node.nodeId().isBlank()) {
                throw new IllegalArgumentException("Workflow node id is required");
            }
            if (indexes.put(node.nodeId(), i) != null) {
                throw new IllegalArgumentException("Duplicate workflow node id: " + node.nodeId());
            }
            if (publishing
                    && (node.type() == WorkflowNodeType.AGENT_INVOKE
                            || node.type() == WorkflowNodeType.REACT_AGENT)) {
                if (node.refId() == null || node.refId().isBlank()) {
                    throw new IllegalArgumentException("Agent node requires ref_id: " + node.nodeId());
                }
                if (agentRegistry.findPublished(node.refId()).isEmpty()) {
                    throw new IllegalArgumentException("Agent not found: " + node.refId());
                }
                agentRegistry
                        .findPublished(node.refId())
                        .filter(
                                agent ->
                                        agent.toolRefs().contains(
                                                "workflow:" + asset.workflowId()))
                        .ifPresent(
                                agent ->
                                        { throw new IllegalArgumentException(
                                                "Workflow cycle detected: "
                                                        + asset.workflowId()
                                                        + " -> agent:"
                                                        + agent.agentId()
                                                        + " -> workflow:"
                                                        + asset.workflowId()); });
            }
            if (publishing && node.type() == WorkflowNodeType.SUBFLOW_INVOKE) {
                if (node.refId() == null || node.refId().isBlank()) {
                    throw new IllegalArgumentException("Subflow node requires ref_id: " + node.nodeId());
                }
                if (asset.workflowId().equals(node.refId())
                        || !workflows.containsKey(node.refId())) {
                    throw new IllegalArgumentException("Workflow subflow target not found: " + node.refId());
                }
            }
            if (publishing && node.type() == WorkflowNodeType.HTTP_REQUEST) {
                Object url = node.config().get("url");
                if (url == null || String.valueOf(url).isBlank()) {
                    throw new IllegalArgumentException("HTTP node requires config.url: " + node.nodeId());
                }
            }
            for (WorkflowTransition transition : node.transitions()) {
                Integer target = indexes.get(transition.nextStepId());
                if (target == null) {
                    if (publishing) {
                        throw new IllegalArgumentException(
                                "Transition target not found: " + transition.nextStepId());
                    }
                    continue;
                }
                if (target <= i) {
                    throw new IllegalArgumentException(
                            "Workflow transitions must move forward: " + node.nodeId());
                }
            }
        }
        if (publishing && asset.nodes().isEmpty()) {
            throw new IllegalArgumentException("A published workflow requires at least one node");
        }
        if (publishing) {
            WorkflowValidationResult contracts = contractValidator.validate(asset);
            if (!contracts.valid()) {
                String message =
                        contracts.diagnostics().stream()
                                .filter(diagnostic -> diagnostic.severity().name().equals("ERROR"))
                                .map(diagnostic -> diagnostic.code() + ": " + diagnostic.message())
                                .findFirst()
                                .orElse("Workflow contract validation failed");
                throw new IllegalArgumentException(message);
            }
        }
    }

    private void validateWorkflowCycles() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        workflows.values().forEach(
                asset ->
                        graph.put(
                                asset.workflowId(),
                                asset.nodes().stream()
                                        .filter(node -> node.type() == WorkflowNodeType.SUBFLOW_INVOKE)
                                        .map(WorkflowNode::refId)
                                        .filter(ref -> ref != null && !ref.isBlank())
                                        .toList()));
        Map<String, Integer> states = new LinkedHashMap<>();
        for (String workflowId : graph.keySet()) {
            visitWorkflow(workflowId, graph, states, new ArrayList<>());
        }
    }

    private void visitWorkflow(
            String workflowId,
            Map<String, List<String>> graph,
            Map<String, Integer> states,
            List<String> path) {
        int state = states.getOrDefault(workflowId, 0);
        if (state == 2) {
            return;
        }
        if (state == 1) {
            throw new IllegalArgumentException(
                    "Workflow subflow cycle detected: " + String.join(" -> ", path) + " -> " + workflowId);
        }
        states.put(workflowId, 1);
        path.add(workflowId);
        for (String target : graph.getOrDefault(workflowId, List.of())) {
            if (graph.containsKey(target)) {
                visitWorkflow(target, graph, states, path);
            }
        }
        path.remove(path.size() - 1);
        states.put(workflowId, 2);
    }

    private List<WorkflowNode> nodes(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(
                        raw -> {
                            try {
                                return objectMapper.convertValue(raw, WorkflowNode.class);
                            } catch (IllegalArgumentException e) {
                                throw new IllegalArgumentException("Invalid workflow node", e);
                            }
                        })
                .toList();
    }

    private List<WorkflowEdge> edges(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::edge)
                .toList();
    }

    private WorkflowEdge edge(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), value));
        return new WorkflowEdge(
                string(map, "edgeId", string(map, "edge_id", "")),
                endpoint(map.get("from")),
                endpoint(map.get("to")),
                string(map, "kind", "data"),
                objectMap(map.get("binding"), Map.of()),
                objectMap(map.get("condition"), Map.of()),
                Boolean.TRUE.equals(map.get("defaultEdge"))
                        || Boolean.TRUE.equals(map.get("default_edge")));
    }

    private WorkflowEndpoint endpoint(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> map.put(String.valueOf(key), item));
        return new WorkflowEndpoint(
                string(map, "nodeId", string(map, "node_id", "")),
                string(map, "portId", string(map, "port_id", "")));
    }

    private Map<String, Object> toMap(WorkflowAsset asset) {
        Map<String, Object> row = new LinkedHashMap<>(asset.metadata());
        row.put("input_schema", asset.inputSchema());
        row.put("output_schema", asset.outputSchema());
        row.put("nodes", asset.nodes());
        row.put("edges", asset.edges());
        row.put("node_count", asset.nodes().size());
        return row;
    }

    private void loadSqlite() {
        String sql = "SELECT workflow_id, payload FROM " + SQLITE_TABLE + " ORDER BY updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                WorkflowAsset asset = fromMap(mapFromJson(resultSet.getString("payload")));
                if (asset != null) {
                    workflows.put(asset.workflowId(), asset);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load workflows", e);
        }
    }

    private void loadFile() {
        Path path = workflowFile();
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<Map<String, Object>> rows =
                    objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            rows.stream().map(this::fromMap).filter(java.util.Objects::nonNull)
                    .forEach(asset -> workflows.put(asset.workflowId(), asset));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load workflows: " + path, e);
        }
    }

    private void persist(WorkflowAsset asset) {
        if (storage.isSqliteEnabled()) {
            String sql =
                    "INSERT INTO "
                            + SQLITE_TABLE
                            + " (workflow_id, payload, updated_at) VALUES (?, ?, ?) ON CONFLICT(workflow_id)"
                            + " DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at";
            try (Connection connection = storage.connection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, asset.workflowId());
                statement.setString(2, objectMapper.writeValueAsString(toMap(asset)));
                statement.setString(3, asset.updatedAt());
                statement.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to persist workflow: " + asset.workflowId(), e);
            }
        } else {
            persistFile();
        }
    }

    private void persistFile() {
        Path path = workflowFile();
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), workflows.values().stream().map(this::toMap).toList());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist workflows: " + path, e);
        }
    }

    private Path workflowFile() {
        return storage.cacheRoot().resolve("workflows.json");
    }

    private WorkflowAsset fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String id = string(map, "workflow_id", "");
        if (id.isBlank()) {
            return null;
        }
        return new WorkflowAsset(
                id,
                integer(map, "version", 1),
                string(map, "name", id),
                string(map, "description", ""),
                string(map, "domain", "platform"),
                string(map, "trigger_type", "manual"),
                string(map, "status", "DRAFT"),
                objectMap(map.get("input_schema"), Map.of()),
                objectMap(map.get("output_schema"), Map.of()),
                nodes(map.get("nodes")),
                edges(map.get("edges")),
                string(map, "created_at", ""),
                string(map, "updated_at", ""),
                string(map, "published_at", ""));
    }

    private Map<String, Object> mapFromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String workflowId(Map<String, Object> payload, String fallback) {
        String id = string(payload, "workflow_id", string(payload, "id", fallback));
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,127}")) {
            throw new IllegalArgumentException("workflow_id must be 2-128 characters");
        }
        return id;
    }

    private String string(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Map<String, Object> objectMap(Object value, Map<String, Object> fallback) {
        if (!(value instanceof Map<?, ?> raw)) {
            return fallback;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
