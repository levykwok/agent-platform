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
import io.agent.platform.control.WorkflowHttpSecurity;
import io.agent.platform.control.WorkflowJdbcSecurity;
import io.agent.platform.control.WorkflowValidationResult;
import io.agent.platform.control.WorkflowNode;
import io.agent.platform.control.WorkflowNodeType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** CRUD and lifecycle service for standalone Workflow assets. */
@Component
public class WorkflowAssetService {

    private static final String SQLITE_TABLE = "platform_workflows";
    private static final String SQLITE_VERSION_TABLE = "platform_workflow_versions";
    private static final String SQLITE_PUBLICATION_TABLE = "platform_workflow_publications";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlatformStorageLayer storage;
    private final AgentDefinitionRegistry agentRegistry;
    private final PlatformAssetAccessService assetAccess;
    private final WorkflowContractValidator contractValidator = new WorkflowContractValidator();
    private final Map<String, WorkflowAsset> workflows = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, WorkflowAsset>> publishedVersions =
            new ConcurrentHashMap<>();
    private final Map<String, Integer> activePublishedVersions = new ConcurrentHashMap<>();

    public WorkflowAssetService(
            PlatformStorageLayer storage, AgentDefinitionRegistry agentRegistry) {
        this(storage, agentRegistry, null);
    }

    @Autowired
    public WorkflowAssetService(
            PlatformStorageLayer storage,
            AgentDefinitionRegistry agentRegistry,
            PlatformAssetAccessService assetAccess) {
        this.storage = storage;
        this.agentRegistry = agentRegistry;
        this.assetAccess = assetAccess;
        if (storage.isSqliteEnabled()) {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_TABLE
                            + " (workflow_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_VERSION_TABLE
                            + " (workflow_id TEXT NOT NULL, version INTEGER NOT NULL, payload TEXT NOT NULL,"
                            + " published_at TEXT NOT NULL, PRIMARY KEY(workflow_id, version))",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_PUBLICATION_TABLE
                            + " (workflow_id TEXT PRIMARY KEY, active_version INTEGER NOT NULL,"
                            + " updated_at TEXT NOT NULL)");
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
        return list(domain, status, null);
    }

    public List<Map<String, Object>> list(
            String domain, String status, PlatformAuthService.Principal principal) {
        return workflows.values().stream()
                .map(
                        asset -> {
                            if (!"PUBLISHED".equalsIgnoreCase(status)) return asset;
                            Integer active = activePublishedVersions.get(asset.workflowId());
                            return active == null
                                    ? null
                                    : publishedVersions
                                            .getOrDefault(asset.workflowId(), Map.of())
                                            .get(active);
                        })
                .filter(java.util.Objects::nonNull)
                .filter(asset -> principal == null || canRead(asset, principal))
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
        return get(workflowId, null);
    }

    public Map<String, Object> get(
            String workflowId, PlatformAuthService.Principal principal) {
        WorkflowAsset asset = require(workflowId);
        requireReadable(asset, principal);
        return toMap(asset);
    }

    public WorkflowAsset require(String workflowId) {
        WorkflowAsset asset = workflows.get(workflowId);
        if (asset == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        return asset;
    }

    public WorkflowAsset requirePublished(String workflowId) {
        return requirePublished(workflowId, null);
    }

    public WorkflowAsset requirePublished(
            String workflowId, PlatformAuthService.Principal principal) {
        WorkflowAsset current = require(workflowId);
        requireReadable(current, principal);
        Integer activeVersion = activePublishedVersions.get(workflowId);
        if (activeVersion == null) {
            throw new IllegalArgumentException("Workflow is not published: " + workflowId);
        }
        return requirePublishedVersion(workflowId, activeVersion, principal);
    }

    public WorkflowAsset requirePublishedVersion(String workflowId, int version) {
        return requirePublishedVersion(workflowId, version, null);
    }

    public WorkflowAsset requirePublishedVersion(
            String workflowId, int version, PlatformAuthService.Principal principal) {
        WorkflowAsset current = require(workflowId);
        requireReadable(current, principal);
        WorkflowAsset revision =
                publishedVersions.getOrDefault(workflowId, Map.of()).get(version);
        if (revision == null) {
            throw new IllegalArgumentException(
                    "Published Workflow version not found: " + workflowId + "@" + version);
        }
        return revision;
    }

    public List<Map<String, Object>> versions(
            String workflowId, PlatformAuthService.Principal principal) {
        WorkflowAsset current = require(workflowId);
        requireReadable(current, principal);
        Integer active = activePublishedVersions.get(workflowId);
        return publishedVersions.getOrDefault(workflowId, Map.of()).values().stream()
                .sorted(Comparator.comparingInt(WorkflowAsset::version).reversed())
                .map(
                        revision -> {
                            Map<String, Object> row = toMap(revision);
                            row.put("active", revision.version() == (active == null ? -1 : active));
                            return row;
                        })
                .toList();
    }

    public WorkflowValidationResult validateContracts(String workflowId) {
        return validateContracts(workflowId, (PlatformAuthService.Principal) null);
    }

    public WorkflowValidationResult validateContracts(
            String workflowId, PlatformAuthService.Principal principal) {
        WorkflowAsset asset = require(workflowId);
        requireReadable(asset, principal);
        return contractValidator.validate(asset);
    }

    public WorkflowValidationResult validateContracts(
            String workflowId, Map<String, Object> payload) {
        return validateContracts(workflowId, payload, null);
    }

    public WorkflowValidationResult validateContracts(
            String workflowId,
            Map<String, Object> payload,
            PlatformAuthService.Principal principal) {
        WorkflowAsset existing = require(workflowId);
        requireReadable(existing, principal);
        return contractValidator.validate(
                normalize(workflowId, payload == null ? Map.of() : payload, existing, principal));
    }

    public synchronized Map<String, Object> create(Map<String, Object> payload) {
        return create(payload, null);
    }

    public synchronized Map<String, Object> create(
            Map<String, Object> payload, PlatformAuthService.Principal principal) {
        String workflowId = workflowId(payload, "workflow_" + UUID.randomUUID().toString().replace("-", ""));
        if (workflows.containsKey(workflowId)) {
            throw new IllegalArgumentException("Workflow already exists: " + workflowId);
        }
        return save(workflowId, payload, principal);
    }

    public synchronized Map<String, Object> save(String workflowId, Map<String, Object> payload) {
        return save(workflowId, payload, null);
    }

    public synchronized Map<String, Object> save(
            String workflowId,
            Map<String, Object> payload,
            PlatformAuthService.Principal principal) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflow_id is required");
        }
        if (principal != null) PlatformRolePolicy.requireBuilder(principal);
        WorkflowAsset existing = workflows.get(workflowId);
        if (existing != null) requireWritable(existing, principal);
        WorkflowAsset asset =
                normalize(workflowId, payload == null ? Map.of() : payload, existing, principal);
        validate(asset, false, principal);
        workflows.put(workflowId, asset);
        persist(asset);
        return toMap(asset);
    }

    public synchronized Map<String, Object> publish(String workflowId) {
        return publish(workflowId, null);
    }

    public synchronized Map<String, Object> publish(
            String workflowId, PlatformAuthService.Principal principal) {
        WorkflowAsset existing = require(workflowId);
        requireWritable(existing, principal);
        validate(existing, true, principal);
        Integer previousActiveVersion = activePublishedVersions.get(workflowId);
        int nextVersion =
                publishedVersions.getOrDefault(workflowId, Map.of()).keySet().stream()
                                .mapToInt(Integer::intValue)
                                .max()
                                .orElse(0)
                        + 1;
        String publishedAt = Instant.now().toString();
        List<WorkflowNode> publishedNodes = pinPublishedDependencies(existing.nodes(), principal);
        WorkflowAsset published =
                new WorkflowAsset(
                        existing.workflowId(),
                        nextVersion,
                        existing.name(),
                        existing.description(),
                        existing.domain(),
                        existing.triggerType(),
                        "PUBLISHED",
                        existing.inputSchema(),
                        existing.outputSchema(),
                        publishedNodes,
                        existing.edges(),
                        existing.createdAt(),
                        publishedAt,
                        publishedAt,
                        existing.ownerType(),
                        existing.ownerId(),
                        existing.orgId(),
                        existing.createdBy(),
                        existing.visibility());
        workflows.put(workflowId, published);
        try {
            validateWorkflowCycles();
            publishedVersions
                    .computeIfAbsent(workflowId, ignored -> new ConcurrentHashMap<>())
                    .put(nextVersion, published);
            activePublishedVersions.put(workflowId, nextVersion);
            persistPublishedState(published);
        } catch (RuntimeException error) {
            workflows.put(workflowId, existing);
            publishedVersions.getOrDefault(workflowId, Map.of()).remove(nextVersion);
            if (previousActiveVersion == null) {
                activePublishedVersions.remove(workflowId);
            } else {
                activePublishedVersions.put(workflowId, previousActiveVersion);
            }
            throw error;
        }
        return toMap(published);
    }

    private List<WorkflowNode> pinPublishedDependencies(
            List<WorkflowNode> nodes, PlatformAuthService.Principal principal) {
        List<WorkflowNode> pinned = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            boolean workflowForeach =
                    node.type() == WorkflowNodeType.FOREACH
                            && "workflow"
                                    .equalsIgnoreCase(
                                            String.valueOf(
                                                    node.config()
                                                            .getOrDefault("target_type", "")));
            if (node.type() != WorkflowNodeType.SUBFLOW_INVOKE && !workflowForeach) {
                pinned.add(node);
                continue;
            }
            String targetId =
                    node.refId() == null || node.refId().isBlank()
                            ? String.valueOf(node.config().getOrDefault("target_id", ""))
                            : node.refId();
            WorkflowAsset target = requirePublished(targetId, principal);
            Map<String, Object> config = new LinkedHashMap<>(node.config());
            config.put("workflow_version", target.version());
            pinned.add(
                    new WorkflowNode(
                            node.nodeId(),
                            node.type(),
                            node.refId(),
                            node.instruction(),
                            config,
                            node.inputMapping(),
                            node.outputSchema(),
                            node.timeoutMs(),
                            node.maxRetries(),
                            node.failurePolicy(),
                            node.inputPorts(),
                            node.outputPorts()));
        }
        return List.copyOf(pinned);
    }

    public synchronized Map<String, Object> unpublish(String workflowId) {
        return unpublish(workflowId, null);
    }

    public synchronized Map<String, Object> unpublish(
            String workflowId, PlatformAuthService.Principal principal) {
        WorkflowAsset existing = require(workflowId);
        requireWritable(existing, principal);
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
                        existing.publishedAt(),
                        existing.ownerType(),
                        existing.ownerId(),
                        existing.orgId(),
                        existing.createdBy(),
                        existing.visibility());
        workflows.put(workflowId, draft);
        Integer previousActiveVersion = activePublishedVersions.remove(workflowId);
        try {
            persistUnpublishedState(draft);
        } catch (RuntimeException error) {
            workflows.put(workflowId, existing);
            if (previousActiveVersion != null) {
                activePublishedVersions.put(workflowId, previousActiveVersion);
            }
            throw error;
        }
        return toMap(draft);
    }

    public synchronized void delete(String workflowId) {
        delete(workflowId, null);
    }

    public synchronized void delete(
            String workflowId, PlatformAuthService.Principal principal) {
        requireWritable(require(workflowId), principal);
        WorkflowAsset removed = workflows.remove(workflowId);
        Map<Integer, WorkflowAsset> removedVersions = publishedVersions.remove(workflowId);
        Integer removedActive = activePublishedVersions.remove(workflowId);
        try {
            if (storage.isSqliteEnabled()) {
                try (Connection connection = storage.connection()) {
                    connection.setAutoCommit(false);
                    deleteByWorkflowId(connection, SQLITE_PUBLICATION_TABLE, workflowId);
                    deleteByWorkflowId(connection, SQLITE_VERSION_TABLE, workflowId);
                    deleteByWorkflowId(connection, SQLITE_TABLE, workflowId);
                    connection.commit();
                }
            } else {
                persistFile();
            }
        } catch (Exception error) {
            workflows.put(workflowId, removed);
            if (removedVersions != null) publishedVersions.put(workflowId, removedVersions);
            if (removedActive != null) activePublishedVersions.put(workflowId, removedActive);
            throw new IllegalStateException("Failed to delete workflow: " + workflowId, error);
        }
    }

    private WorkflowAsset normalize(
            String workflowId,
            Map<String, Object> payload,
            WorkflowAsset existing,
            PlatformAuthService.Principal principal) {
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
                existing == null ? 1 : existing.version(),
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
                existing == null ? "" : existing.publishedAt(),
                existing == null ? ownerType(principal) : existing.ownerType(),
                existing == null ? ownerId(principal) : existing.ownerId(),
                existing == null ? orgId(principal) : existing.orgId(),
                existing == null ? createdBy(principal) : existing.createdBy(),
                existing == null ? visibility(principal, payload) : existing.visibility());
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

    private void validate(
            WorkflowAsset asset,
            boolean publishing,
            PlatformAuthService.Principal principal) {
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
                requireReadableAgent(node.refId(), principal);
            }
            if (publishing && node.type() == WorkflowNodeType.SUBFLOW_INVOKE) {
                if (node.refId() == null || node.refId().isBlank()) {
                    throw new IllegalArgumentException("Subflow node requires ref_id: " + node.nodeId());
                }
                if (asset.workflowId().equals(node.refId())) {
                    throw new IllegalArgumentException("Workflow subflow target not found: " + node.refId());
                }
                requirePublished(node.refId(), principal);
            }
            if (publishing
                    && (node.type() == WorkflowNodeType.HTTP_REQUEST
                            || node.type() == WorkflowNodeType.MESSAGE_SEND)) {
                Object url = node.config().get("url");
                if (url == null || String.valueOf(url).isBlank()) {
                    throw new IllegalArgumentException("HTTP node requires config.url: " + node.nodeId());
                }
                WorkflowHttpSecurity.validateStaticUrl(String.valueOf(url));
                String method =
                        String.valueOf(node.config().getOrDefault("method", "POST"))
                                .trim()
                                .toUpperCase();
                boolean mutating = !List.of("GET", "HEAD", "OPTIONS").contains(method);
                Object idempotencyKey = node.config().get("idempotency_key");
                if (mutating
                        && node.maxRetries() > 0
                        && (idempotencyKey == null
                                || String.valueOf(idempotencyKey).isBlank())) {
                    throw new IllegalArgumentException(
                            "Mutating HTTP node retries require config.idempotency_key: "
                                    + node.nodeId());
                }
                Object headers = node.config().get("headers");
                if (headers instanceof Map<?, ?> headerMap) {
                    for (Map.Entry<?, ?> header : headerMap.entrySet()) {
                        if (WorkflowHttpSecurity.sensitiveHeader(String.valueOf(header.getKey()))
                                && !String.valueOf(header.getValue()).trim().startsWith("env:")) {
                            throw new IllegalArgumentException(
                                    "Sensitive Workflow HTTP headers must use env: references: "
                                            + node.nodeId()
                                            + "."
                                            + header.getKey());
                        }
                    }
                }
            }
            if (publishing && node.type() == WorkflowNodeType.FOREACH) {
                String targetId = node.refId() == null ? "" : node.refId();
                String targetType =
                        String.valueOf(node.config().getOrDefault("target_type", ""))
                                .trim()
                                .toLowerCase();
                if (!targetId.isBlank()
                        && !List.of("agent", "workflow").contains(targetType)) {
                    throw new IllegalArgumentException(
                            "foreach target_type must be agent or workflow: " + node.nodeId());
                }
                if ("agent".equals(targetType)
                        && agentRegistry.findPublished(targetId).isEmpty()) {
                    throw new IllegalArgumentException(
                            "foreach Agent target not found: " + targetId);
                }
                if ("agent".equals(targetType)) {
                    requireReadableAgent(targetId, principal);
                }
                if ("workflow".equals(targetType)) {
                    requirePublished(targetId, principal);
                }
            }
            if (publishing
                    && (node.type() == WorkflowNodeType.SKILL_INVOKE
                            || node.type() == WorkflowNodeType.MCP_INVOKE)) {
                String agentId =
                        String.valueOf(node.config().getOrDefault("agent_id", "")).trim();
                var target = agentRegistry.findPublished(agentId).orElse(null);
                if (target == null || node.refId().isBlank()) {
                    throw new IllegalArgumentException(
                            node.type().value()
                                    + " requires ref_id and config.agent_id: "
                                    + node.nodeId());
                }
                requireReadableAgent(agentId, principal);
                List<String> refs =
                        node.type() == WorkflowNodeType.SKILL_INVOKE
                                ? target.skillRefs()
                                : target.mcpRefs();
                if (!refs.contains(node.refId())) {
                    throw new IllegalArgumentException(
                            "Target Agent does not expose "
                                    + node.refId()
                                    + ": "
                                    + node.nodeId());
                }
            }
            if (publishing
                    && (node.type() == WorkflowNodeType.DATABASE_QUERY
                            || node.type() == WorkflowNodeType.DATABASE_WRITE)) {
                WorkflowJdbcSecurity.validateUrl(
                        String.valueOf(node.config().getOrDefault("jdbc_url", "")));
                String sql = String.valueOf(node.config().getOrDefault("sql", ""));
                if (sql.isBlank() || sql.contains("{{input}}") || sql.contains("${input}")) {
                    throw new IllegalArgumentException(
                            "Database node requires parameterized config.sql: " + node.nodeId());
                }
                WorkflowJdbcSecurity.credential(
                        String.valueOf(node.config().getOrDefault("username", "")));
                WorkflowJdbcSecurity.credential(
                        String.valueOf(node.config().getOrDefault("password", "")));
                if (node.type() == WorkflowNodeType.DATABASE_WRITE
                        && String.valueOf(
                                        node.config().getOrDefault("idempotency_key", ""))
                                .isBlank()) {
                    throw new IllegalArgumentException(
                            "database.write requires config.idempotency_key: "
                                    + node.nodeId());
                }
                if (node.type() == WorkflowNodeType.DATABASE_WRITE
                        && !sql.contains("{{idempotency_key}}")) {
                    throw new IllegalArgumentException(
                            "database.write config.sql must bind {{idempotency_key}} to a"
                                    + " uniqueness-protected column: "
                                    + node.nodeId());
                }
                if (node.type() == WorkflowNodeType.DATABASE_WRITE
                        && sql.indexOf("{{idempotency_key}}")
                                != sql.lastIndexOf("{{idempotency_key}}")) {
                    throw new IllegalArgumentException(
                            "database.write config.sql must contain exactly one"
                                    + " {{idempotency_key}} token: "
                                    + node.nodeId());
                }
            }
        }
        if (publishing && asset.nodes().isEmpty()) {
            throw new IllegalArgumentException("A published workflow requires at least one node");
        }
        if (publishing) {
            long inputCount = asset.nodes().stream().filter(node -> node.type() == WorkflowNodeType.INPUT).count();
            long outputCount = asset.nodes().stream().filter(node -> node.type() == WorkflowNodeType.OUTPUT).count();
            if (inputCount != 1 || outputCount != 1) {
                throw new IllegalArgumentException(
                        "A published workflow requires exactly one workflow.input and one workflow.output node");
            }
            String inputNodeId = asset.nodes().stream()
                    .filter(node -> node.type() == WorkflowNodeType.INPUT)
                    .map(WorkflowNode::nodeId)
                    .findFirst()
                    .orElse("");
            String outputNodeId = asset.nodes().stream()
                    .filter(node -> node.type() == WorkflowNodeType.OUTPUT)
                    .map(WorkflowNode::nodeId)
                    .findFirst()
                    .orElse("");
            boolean inputConnected = asset.edges().stream()
                    .anyMatch(edge -> edge != null && edge.from() != null
                            && inputNodeId.equals(edge.from().nodeId()));
            boolean outputConnected = asset.edges().stream()
                    .anyMatch(edge -> edge != null && edge.to() != null
                            && outputNodeId.equals(edge.to().nodeId()));
            if (!inputConnected || !outputConnected) {
                throw new IllegalArgumentException(
                        "Published workflow boundaries must be connected by explicit edges");
            }
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

    private void requireReadableAgent(
            String agentId, PlatformAuthService.Principal principal) {
        if (principal == null || assetAccess == null) return;
        assetAccess.requireReadable("AGENT", agentId, principal);
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
                            // The canvas keeps its layout position outside the runtime node
                            // model. Accept that UI shape on create as well as the persisted
                            // config.canvas_position shape used by later saves.
                            Map<String, Object> normalized = new LinkedHashMap<>();
                            raw.forEach((key, item) -> normalized.put(String.valueOf(key), item));
                            Object position = normalized.remove("position");
                            Object canvasPosition = normalized.remove("canvas_position");
                            if (position != null || canvasPosition != null) {
                                Map<String, Object> config =
                                        objectMap(normalized.get("config"), new LinkedHashMap<>());
                                if (!config.containsKey("canvas_position")) {
                                    config.put(
                                            "canvas_position",
                                            objectMap(
                                                    position == null ? canvasPosition : position,
                                                    Map.of()));
                                }
                                normalized.put("config", config);
                            }
                            try {
                                return objectMapper.convertValue(normalized, WorkflowNode.class);
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
        Integer activeVersion = activePublishedVersions.get(asset.workflowId());
        row.put("active_published_version", activeVersion == null ? 0 : activeVersion);
        row.put(
                "published_version_count",
                publishedVersions.getOrDefault(asset.workflowId(), Map.of()).size());
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
            loadPublishedVersions(connection);
            loadPublications(connection);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load workflows", e);
        }
        migrateLegacyPublishedAssets();
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
            loadPublishedVersionsFile();
            migrateLegacyPublishedAssets();
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
            persistPublishedVersionsFile();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist workflows: " + path, e);
        }
    }

    private Path workflowFile() {
        return storage.cacheRoot().resolve("workflows.json");
    }

    private Path workflowVersionsFile() {
        return storage.cacheRoot().resolve("workflow-versions.json");
    }

    private void loadPublishedVersions(Connection connection) throws Exception {
        String sql =
                "SELECT workflow_id, version, payload FROM "
                        + SQLITE_VERSION_TABLE
                        + " ORDER BY workflow_id, version";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                WorkflowAsset revision = fromMap(mapFromJson(resultSet.getString("payload")));
                if (revision != null) {
                    publishedVersions
                            .computeIfAbsent(
                                    resultSet.getString("workflow_id"),
                                    ignored -> new ConcurrentHashMap<>())
                            .put(resultSet.getInt("version"), revision);
                }
            }
        }
    }

    private void loadPublications(Connection connection) throws Exception {
        String sql =
                "SELECT workflow_id, active_version FROM " + SQLITE_PUBLICATION_TABLE;
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String workflowId = resultSet.getString("workflow_id");
                int version = resultSet.getInt("active_version");
                if (publishedVersions.getOrDefault(workflowId, Map.of()).containsKey(version)) {
                    activePublishedVersions.put(workflowId, version);
                }
            }
        }
    }

    private void migrateLegacyPublishedAssets() {
        for (WorkflowAsset asset : workflows.values()) {
            if (!"PUBLISHED".equals(asset.status())
                    || publishedVersions
                            .getOrDefault(asset.workflowId(), Map.of())
                            .containsKey(asset.version())) {
                continue;
            }
            publishedVersions
                    .computeIfAbsent(asset.workflowId(), ignored -> new ConcurrentHashMap<>())
                    .put(asset.version(), asset);
            activePublishedVersions.put(asset.workflowId(), asset.version());
            persistPublishedState(asset);
        }
    }

    private void persistPublishedState(WorkflowAsset published) {
        if (!storage.isSqliteEnabled()) {
            persistFile();
            return;
        }
        String revisionSql =
                "INSERT INTO "
                        + SQLITE_VERSION_TABLE
                        + " (workflow_id,version,payload,published_at) VALUES (?,?,?,?)";
        String publicationSql =
                "INSERT INTO "
                        + SQLITE_PUBLICATION_TABLE
                        + " (workflow_id,active_version,updated_at) VALUES (?,?,?)"
                        + " ON CONFLICT(workflow_id) DO UPDATE SET active_version=excluded.active_version,"
                        + " updated_at=excluded.updated_at";
        String currentSql =
                "INSERT INTO "
                        + SQLITE_TABLE
                        + " (workflow_id,payload,updated_at) VALUES (?,?,?)"
                        + " ON CONFLICT(workflow_id) DO UPDATE SET payload=excluded.payload,"
                        + " updated_at=excluded.updated_at";
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(revisionSql)) {
                statement.setString(1, published.workflowId());
                statement.setInt(2, published.version());
                statement.setString(3, objectMapper.writeValueAsString(toMap(published)));
                statement.setString(4, published.publishedAt());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(publicationSql)) {
                statement.setString(1, published.workflowId());
                statement.setInt(2, published.version());
                statement.setString(3, published.updatedAt());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(currentSql)) {
                statement.setString(1, published.workflowId());
                statement.setString(2, objectMapper.writeValueAsString(toMap(published)));
                statement.setString(3, published.updatedAt());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Failed to publish Workflow revision: "
                            + published.workflowId()
                            + "@"
                            + published.version(),
                    error);
        }
    }

    private void persistUnpublishedState(WorkflowAsset draft) {
        if (!storage.isSqliteEnabled()) {
            persistFile();
            return;
        }
        String currentSql =
                "INSERT INTO "
                        + SQLITE_TABLE
                        + " (workflow_id,payload,updated_at) VALUES (?,?,?)"
                        + " ON CONFLICT(workflow_id) DO UPDATE SET payload=excluded.payload,"
                        + " updated_at=excluded.updated_at";
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            deleteByWorkflowId(connection, SQLITE_PUBLICATION_TABLE, draft.workflowId());
            try (PreparedStatement statement = connection.prepareStatement(currentSql)) {
                statement.setString(1, draft.workflowId());
                statement.setString(2, objectMapper.writeValueAsString(toMap(draft)));
                statement.setString(3, draft.updatedAt());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Failed to unpublish Workflow: " + draft.workflowId(), error);
        }
    }

    private void loadPublishedVersionsFile() throws Exception {
        Path path = workflowVersionsFile();
        if (!Files.exists(path)) return;
        List<Map<String, Object>> rows =
                objectMapper.readValue(path.toFile(), new TypeReference<>() {});
        for (Map<String, Object> row : rows) {
            WorkflowAsset revision = fromMap(row);
            if (revision == null) continue;
            publishedVersions
                    .computeIfAbsent(revision.workflowId(), ignored -> new ConcurrentHashMap<>())
                    .put(revision.version(), revision);
            if (Boolean.TRUE.equals(row.get("active"))) {
                activePublishedVersions.put(revision.workflowId(), revision.version());
            }
        }
    }

    private void persistPublishedVersionsFile() throws Exception {
        Path path = workflowVersionsFile();
        Files.createDirectories(path.getParent());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<Integer, WorkflowAsset> revisions : publishedVersions.values()) {
            for (WorkflowAsset revision : revisions.values()) {
                Map<String, Object> row = toMap(revision);
                row.put(
                        "active",
                        activePublishedVersions.getOrDefault(revision.workflowId(), -1)
                                == revision.version());
                rows.add(row);
            }
        }
        rows.sort(
                Comparator.comparing((Map<String, Object> row) -> String.valueOf(row.get("workflow_id")))
                        .thenComparingInt(row -> integer(row, "version", 1)));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
    }

    private static void deleteByWorkflowId(
            Connection connection, String table, String workflowId) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM " + table + " WHERE workflow_id = ?")) {
            statement.setString(1, workflowId);
            statement.executeUpdate();
        }
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
                string(map, "published_at", ""),
                string(map, "owner_type", "SYSTEM"),
                string(map, "owner_id", "platform"),
                string(map, "org_id", "platform"),
                string(map, "created_by", "platform_admin"),
                string(map, "visibility", "PUBLIC"));
    }

    private boolean canRead(WorkflowAsset asset, PlatformAuthService.Principal principal) {
        if (principal == null) return false;
        if ("PLATFORM_ADMIN".equals(principal.role())) return true;
        if ("PUBLIC".equals(asset.visibility())) return true;
        if ("ORGANIZATION".equals(asset.visibility())
                && asset.orgId().equals(principal.orgId())) return true;
        return "PRIVATE".equals(asset.visibility()) && asset.ownerId().equals(principal.userId());
    }

    private boolean canWrite(WorkflowAsset asset, PlatformAuthService.Principal principal) {
        if (principal == null) return false;
        if ("PLATFORM_ADMIN".equals(principal.role())) return true;
        if ("ORGANIZATION".equals(asset.ownerType())
                && "ORG_ADMIN".equals(principal.role())
                && asset.orgId().equals(principal.orgId())) return true;
        return asset.ownerId().equals(principal.userId());
    }

    private void requireReadable(WorkflowAsset asset, PlatformAuthService.Principal principal) {
        // A null principal is retained for internal runtime callers and legacy tests.
        // HTTP controllers must resolve and require a session before calling this service.
        if (principal == null) return;
        if (!canRead(asset, principal)) {
            throw new PlatformAuthService.AuthException(404, "Workflow 不存在");
        }
    }

    private void requireWritable(WorkflowAsset asset, PlatformAuthService.Principal principal) {
        // A null principal is the explicit compatibility path for internal runtime calls.
        if (principal == null) return;
        PlatformRolePolicy.requireBuilder(principal);
        if (!canWrite(asset, principal)) {
            throw new PlatformAuthService.AuthException(403, "没有权限修改该 Workflow");
        }
    }

    private String ownerType(PlatformAuthService.Principal principal) {
        return principal == null ? "SYSTEM" : "USER";
    }

    private String ownerId(PlatformAuthService.Principal principal) {
        return principal == null ? "platform" : principal.userId();
    }

    private String orgId(PlatformAuthService.Principal principal) {
        return principal == null ? "platform" : principal.orgId();
    }

    private String createdBy(PlatformAuthService.Principal principal) {
        return principal == null ? "platform_admin" : principal.userId();
    }

    private String visibility(
            PlatformAuthService.Principal principal, Map<String, Object> payload) {
        if (principal == null) return "PUBLIC";
        String requested = string(payload, "visibility", "PRIVATE").toUpperCase();
        if ("PUBLIC".equals(requested)
                && !"PLATFORM_ADMIN".equals(principal.role())) return "PRIVATE";
        if ("ORGANIZATION".equals(requested)
                && !List.of("PLATFORM_ADMIN", "ORG_ADMIN").contains(principal.role())) return "PRIVATE";
        return List.of("PRIVATE", "ORGANIZATION", "PUBLIC").contains(requested) ? requested : "PRIVATE";
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
            return new LinkedHashMap<>(fallback);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
