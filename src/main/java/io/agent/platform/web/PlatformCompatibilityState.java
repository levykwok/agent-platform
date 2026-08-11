/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.McpRegistry;
import io.agent.platform.control.McpSpec;
import io.agent.platform.control.ModelConfigRegistry;
import io.agent.platform.control.ModelProviderRegistry;
import io.agent.platform.control.ModelProviderSpec;
import io.agent.platform.control.ModelSpec;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.control.PlatformArtifactStore;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.RouteRule;
import io.agent.platform.control.SkillRegistry;
import io.agent.platform.control.SkillSpec;
import io.agent.platform.control.SubagentBinding;
import io.agent.platform.control.ToolRegistry;
import io.agent.platform.control.ToolSpec;
import io.agent.platform.control.WorkflowNode;
import io.agent.platform.control.WorkflowNodeType;
import io.agent.platform.control.WorkflowEdge;
import io.agent.platform.control.WorkflowEndpoint;
import io.agent.platform.control.WorkflowPort;
import io.agent.platform.control.WorkflowStep;
import io.agent.platform.control.WorkflowTransition;
import io.agent.platform.control.YamlAgentDefinitionRegistry;
import io.agent.platform.runtime.AgentEventEnvelope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PlatformCompatibilityState {

    private static final Logger log = LoggerFactory.getLogger(PlatformCompatibilityState.class);
    private static final String MCP_DISCOVERY_CACHE_VERSION = "1";
    private static final String SQLITE_DOMAINS_TABLE = "platform_domains";
    private static final String SQLITE_SKILL_PACKAGES_TABLE = "platform_skill_packages";
    private static final String SQLITE_MODEL_SLOT_TABLE = "platform_model_slots";
    private static final String SQLITE_MODEL_ALIAS_TABLE = "platform_model_aliases";
    private static final String SQLITE_MCP_DISCOVERY_TABLE = "platform_mcp_discovered_tools";
    private static final String SQLITE_AUDIT_EVENTS_TABLE = "platform_audit_events";
    private static final String SQLITE_PROBE_RUNS_TABLE = "platform_probe_runs";
    private static final String SQLITE_MIGRATION_HISTORY_TABLE = "platform_migration_history";
    private static final String SQLITE_MEMORY_TABLE = "platform_memories";
    private static final String SQLITE_RUNS_TABLE = "platform_agent_runs";
    private static final String SQLITE_RUN_STEPS_TABLE = "platform_agent_run_steps";
    private static final String SQLITE_RUN_EVENTS_TABLE = "platform_agent_run_events";
    private static final String SQLITE_WAITINGS_TABLE = "platform_agent_waitings";
    private static final String MEMORY_BLOCK_START = "<!-- agent-platform-memory:start -->";
    private static final String MEMORY_BLOCK_END = "<!-- agent-platform-memory:end -->";

    private final AgentDefinitionRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final SkillRegistry skillRegistry;
    private final ModelConfigRegistry modelRegistry;
    private final ModelProviderRegistry providerRegistry;
    private final PlatformWorkspaceSessionStore workspaceSessionStore;
    private final McpToolDiscoveryService mcpToolDiscoveryService;
    private final SkillSandboxSmokeTestService skillSandboxSmokeTestService;
    private final PlatformArtifactStore artifactStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlatformStorageLayer storage;
    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<String, List<Map<String, Object>>> attachments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> attachmentsById = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> runs = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> runEvents = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> runSteps = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> waitings = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> providers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> modelRows = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> slotBindings = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> aliases = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> mcpServers = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> mcpToolsCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> mcpProbeCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> toolBindings = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> toolSchemaSnapshots =
            new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> knowledgeDocs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> collections = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> memories = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> skillPackages = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> domains = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> audit = new ArrayList<>();

    @Value("${agent.platform.mcp.discovery.enabled:true}")
    private boolean mcpDiscoveryEnabled;

    // Keep the small constructor used by the persistence tests source-compatible.
    PlatformCompatibilityState(
            AgentDefinitionRegistry agentRegistry,
            ToolRegistry toolRegistry,
            McpRegistry mcpRegistry,
            SkillRegistry skillRegistry,
            ModelConfigRegistry modelRegistry,
            ModelProviderRegistry providerRegistry,
            PlatformWorkspaceSessionStore workspaceSessionStore,
            McpToolDiscoveryService mcpToolDiscoveryService,
            SkillSandboxSmokeTestService skillSandboxSmokeTestService,
            PlatformStorageLayer storage) {
        this(
                agentRegistry,
                toolRegistry,
                mcpRegistry,
                skillRegistry,
                modelRegistry,
                providerRegistry,
                workspaceSessionStore,
                mcpToolDiscoveryService,
                skillSandboxSmokeTestService,
                new PlatformArtifactStore(storage),
                storage);
    }

    @Autowired
    public PlatformCompatibilityState(
            AgentDefinitionRegistry agentRegistry,
            ToolRegistry toolRegistry,
            McpRegistry mcpRegistry,
            SkillRegistry skillRegistry,
            ModelConfigRegistry modelRegistry,
            ModelProviderRegistry providerRegistry,
            PlatformWorkspaceSessionStore workspaceSessionStore,
            McpToolDiscoveryService mcpToolDiscoveryService,
            SkillSandboxSmokeTestService skillSandboxSmokeTestService,
            PlatformArtifactStore artifactStore,
            PlatformStorageLayer storage) {
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.modelRegistry = modelRegistry;
        this.providerRegistry = providerRegistry;
        this.workspaceSessionStore = workspaceSessionStore;
        this.mcpToolDiscoveryService = mcpToolDiscoveryService;
        this.skillSandboxSmokeTestService = skillSandboxSmokeTestService;
        this.artifactStore = artifactStore;
        this.storage = storage;
        seedModels();
        seedMcps();
        if (storage.isSqliteEnabled()) {
            initCompatibilitySqliteSchema();
        }
    }

    @PostConstruct
    private void init() {
        loadMcpDiscoveryCache();
        applyCachedDiscoveryMetadata();
        loadSkillPackages();
        loadSkillFiles();
        loadToolFiles();
        loadDomains();
        loadModelSlots();
        loadMemories();
        loadAudit();
        loadRunState();
        seedPlatformDomain();
    }

    @Scheduled(
            fixedDelayString = "${agent.platform.mcp.discovery.interval-ms:120000}",
            initialDelayString = "${agent.platform.mcp.discovery.initial-delay-ms:5000}")
    public void refreshMcpDiscoveries() {
        if (!mcpDiscoveryEnabled) {
            return;
        }
        if (mcpServers.isEmpty()) {
            return;
        }
        mcpServers.values().forEach(this::refreshMcpDiscoveryForServer);
        persistMcpDiscoveryCache();
    }

    public List<Map<String, Object>> cachedMcpTools(String serverId) {
        List<Map<String, Object>> cached = mcpToolsCache.get(serverId);
        if (cached == null) {
            return List.of();
        }
        return cached.stream().map(Map::copyOf).toList();
    }

    public List<Map<String, Object>> agents() {
        return agentRegistry.allPublished().stream().map(this::agentRow).toList();
    }

    public List<Map<String, Object>> domains() {
        seedPlatformDomain();
        return domains.values().stream()
                .sorted(Comparator.comparing(row -> String.valueOf(row.getOrDefault("domain", ""))))
                .map(pkg -> (Map<String, Object>) new LinkedHashMap<String, Object>(pkg))
                .toList();
    }

    public Map<String, Object> upsertDomain(Map<String, Object> payload) {
        String domain = string(payload, "domain", "").trim();
        if (domain.isBlank()) {
            throw new IllegalArgumentException("Domain is required.");
        }
        if (!domain.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException(
                    "Domain must be 2-64 chars and only contain letters, numbers, _ or -.");
        }
        Map<String, Object> row =
                new LinkedHashMap<>(domains.getOrDefault(domain, Map.of("domain", domain)));
        row.put("domain", domain);
        row.put(
                "display_name",
                string(payload, "display_name", string(row, "display_name", domain)));
        row.put("description", string(payload, "description", string(row, "description", "")));
        row.put("org_id", string(payload, "org_id", string(row, "org_id", domain)));
        row.put("status", string(payload, "status", string(row, "status", "active")));
        row.putIfAbsent("created_at", Instant.now().toString());
        row.put("updated_at", Instant.now().toString());
        domains.put(domain, row);
        persistDomains();
        return new LinkedHashMap<>(row);
    }

    public Map<String, Object> agentSpec(String agentId) {
        AgentDefinition definition = agentRegistry.findPublished(agentId).orElse(null);
        if (definition == null) {
            return Map.of();
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("agent_id", definition.agentId());
        config.put("name", definition.name());
        config.put("description", definition.systemPrompt());
        config.put("domain", "platform");
        config.put("enabled", definition.enabled());
        config.put("skill_scope", Map.of("include", definition.skillRefs()));
        config.put("tool_scope", Map.of("include", definition.toolRefs()));
        config.put("mcp_scope", Map.of("include", definition.mcpRefs()));
        if (!definition.modelPolicy().isEmpty()) {
            config.put("model_policy", definition.modelPolicy());
        } else if (!modelPolicyValue(definition.model()).isBlank()) {
            config.put("model_policy", Map.of("qa", modelPolicyValue(definition.model())));
        } else {
            config.put("model_policy", Map.of());
        }
        config.put(
                "prompt_policy",
                Map.of(
                        "role",
                        definition.systemPrompt(),
                        "planner_rules",
                        List.of(),
                        "require_structured_plan",
                        true));

        Map<String, Object> flows = new LinkedHashMap<>();
        flows.put("default", "agentscope_runtime");
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("flows", flows);
        workflow.put("orchestration", definition.orchestration());
        return Map.of("agent_id", agentId, "config_json", config, "workflow_json", workflow);
    }

    public Map<String, Object> upsertAgent(Map<String, Object> payload) {
        String agentId = string(payload, "agent_id", "agent_" + sequence.getAndIncrement());
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("agent_id", agentId);
        row.putIfAbsent("display_name", string(payload, "display_name", agentId));
        row.putIfAbsent("domain", "platform");
        row.putIfAbsent("source", "custom");
        row.putIfAbsent("enabled", true);
        return row;
    }

    public void deleteAgentSpec(String agentId) {
        agentRegistry.delete(agentId);
    }

    public Map<String, Object> saveAgentSpec(String agentId, Map<String, Object> payload) {
        Map<String, Object> config = childMap(payload, "config_json");
        Map<String, Object> workflow = childMap(payload, "workflow_json");
        AgentDefinition existing = agentRegistry.findPublished(agentId).orElse(null);
        String name = string(config, "name", existing == null ? agentId : existing.name());
        String systemPrompt =
                string(
                        childMap(config, "prompt_policy"),
                        "role",
                        string(
                                config,
                                "description",
                                existing == null ? "" : existing.systemPrompt()));
        Map<String, Object> modelPolicy =
                config.containsKey("model_policy")
                        ? new LinkedHashMap<>(childMap(config, "model_policy"))
                        : Map.of();
        String model = firstModel(config, "");
        boolean enabled = bool(config.get("enabled"), existing == null || existing.enabled());
        List<String> toolRefs = stringList(childMap(config, "tool_scope").get("include"));
        if (toolRefs.isEmpty() && existing != null) {
            toolRefs = existing.toolRefs();
        }
        List<String> skillRefs = stringList(childMap(config, "skill_scope").get("include"));
        if (!config.containsKey("skill_scope") && existing != null) {
            skillRefs = existing.skillRefs();
        }
        List<String> mcpRefs = stringList(childMap(config, "mcp_scope").get("include"));
        if (!config.containsKey("mcp_scope") && existing != null) {
            mcpRefs = existing.mcpRefs();
        }
        OrchestrationPolicy orchestration =
                orchestration(
                        workflow.get("orchestration"),
                        existing == null ? OrchestrationPolicy.single() : existing.orchestration());
        String workspace =
                existing == null
                        ? externalWorkspace(storage.agentDefinitionWorkspace(agentId))
                        : externalWorkspace(existing.workspace());
        AgentDefinition saved =
                agentRegistry.upsert(
                        new YamlAgentDefinitionRegistry.AgentConfig(
                                agentId,
                                existing == null ? "v1" : existing.version(),
                                name,
                                model,
                                modelPolicy,
                                systemPrompt,
                                enabled,
                                workspace,
                                toolRefs,
                                mcpRefs,
                                skillRefs,
                                orchestration));
        return agentRow(saved);
    }

    private String firstModel(Map<String, Object> config, String fallback) {
        Map<String, Object> policy = childMap(config, "model_policy");
        for (String key : List.of("qa", "chat", "default", "primary")) {
            if (policy.containsKey(key)) {
                String resolved = resolveAgentModelPolicy(key, policy.get(key));
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }
        }
        return fallback;
    }

    private String resolveAgentModelPolicy(String slotKey, Object configured) {
        String value = configured == null ? "" : String.valueOf(configured).trim();
        if (value.isBlank()) {
            return "";
        }
        String aliasModel = modelIdForAlias(slotKey, value);
        if (!aliasModel.isBlank()) {
            return aliasModel;
        }
        return value;
    }

    private String modelPolicyValue(String model) {
        return model == null ? "" : model;
    }

    private String modelIdForAlias(String slotKey, String aliasName) {
        String normalizedSlot = normalizedChatSlot(slotKey);
        return aliases.values().stream()
                .filter(row -> aliasName.equals(String.valueOf(row.getOrDefault("alias_name", ""))))
                .filter(
                        row -> {
                            String target =
                                    normalizedChatSlot(
                                            String.valueOf(
                                                    row.getOrDefault("target_slot_key", "qa")));
                            return target.isBlank() || target.equals(normalizedSlot);
                        })
                .map(row -> String.valueOf(row.getOrDefault("model_id", "")).trim())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String modelIdForSlot(String slotKey) {
        String normalizedSlot = normalizedChatSlot(slotKey);
        Map<String, Object> binding = slotBindings.get(normalizedSlot);
        if (binding == null && !"qa".equals(normalizedSlot)) {
            binding = slotBindings.get("qa");
        }
        if (binding == null) {
            return "";
        }
        return String.valueOf(binding.getOrDefault("model_id", "")).trim();
    }

    public String defaultChatModelId() {
        String slotModel = modelIdForSlot("qa");
        if (!slotModel.isBlank()) {
            return slotModel;
        }
        return defaultModelForSlot("chat");
    }

    public String defaultVlmModelId() {
        String slotModel = modelIdForSlot("vlm");
        if (!slotModel.isBlank()) {
            return slotModel;
        }
        return modelRows.values().stream()
                .filter(row -> "active".equals(String.valueOf(row.getOrDefault("status", ""))))
                .filter(row -> "chat".equals(String.valueOf(row.getOrDefault("model_kind", ""))))
                .filter(
                        row ->
                                stringList(row.get("capabilities")).stream()
                                        .anyMatch("vision"::equalsIgnoreCase))
                .map(row -> String.valueOf(row.getOrDefault("model_id", "")).trim())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String normalizedChatSlot(String slotKey) {
        String key = slotKey == null ? "" : slotKey.trim();
        if (key.equals("chat") || key.equals("default") || key.equals("primary")) {
            return "qa";
        }
        return key;
    }

    private OrchestrationPolicy orchestration(Object value, OrchestrationPolicy fallback) {
        if (value instanceof OrchestrationPolicy policy) {
            return policy;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return fallback;
        }
        Map<String, Object> map = normalize(raw);
        return new OrchestrationPolicy(
                mode(string(map, "mode", fallback.mode().name())),
                subagents(map.get("subagents")),
                routes(map.get("routes")),
                workflowSteps(map.get("workflow")),
                workflowNodes(map.get("nodes")),
                workflowEdges(map.get("edges")));
    }

    private OrchestrationMode mode(String value) {
        try {
            return OrchestrationMode.valueOf(value.toUpperCase());
        } catch (RuntimeException e) {
            return OrchestrationMode.SINGLE;
        }
    }

    private List<SubagentBinding> subagents(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::subagent)
                .toList();
    }

    private SubagentBinding subagent(Map<?, ?> raw) {
        Map<String, Object> map = normalize(raw);
        return new SubagentBinding(
                stringAny(map, "bindingId", "binding_id", "id"),
                stringAny(map, "targetAgentId", "target_agent_id", "agent_id"),
                string(map, "role", ""),
                string(map, "description", ""),
                Boolean.TRUE.equals(map.get("exposeToUser"))
                        || Boolean.TRUE.equals(map.get("expose_to_user")),
                stringList(
                        map.get("toolRefs") == null ? map.get("tool_refs") : map.get("toolRefs")));
    }

    private List<RouteRule> routes(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::route)
                .toList();
    }

    private RouteRule route(Map<?, ?> raw) {
        Map<String, Object> map = normalize(raw);
        return new RouteRule(
                stringAny(map, "ruleId", "rule_id", "id"),
                stringAny(map, "targetAgentId", "target_agent_id", "agent_id"),
                string(map, "contains", ""),
                stringList(map.get("keywords")),
                Boolean.TRUE.equals(map.get("defaultRoute"))
                        || Boolean.TRUE.equals(map.get("default_route")));
    }

    private List<WorkflowStep> workflowSteps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::workflowStep)
                .toList();
    }

    private WorkflowStep workflowStep(Map<?, ?> raw) {
        Map<String, Object> map = normalize(raw);
        return new WorkflowStep(
                stringAny(map, "stepId", "step_id", "id"),
                stringAny(map, "agentId", "agent_id", "targetAgentId"),
                string(map, "instruction", ""),
                numberLong(map.get("timeoutMs"), map.get("timeout_ms")),
                numberInt(map.get("maxRetries"), map.get("max_retries")),
                workflowFailurePolicy(map),
                workflowTransitions(map.get("transitions")));
    }

    private List<WorkflowNode> workflowNodes(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::workflowNode)
                .toList();
    }

    private WorkflowNode workflowNode(Map<?, ?> raw) {
        Map<String, Object> map = normalize(raw);
        return new WorkflowNode(
                stringAny(map, "nodeId", "node_id", "stepId", "step_id", "id"),
                WorkflowNodeType.fromValue(stringAny(map, "type", "nodeType", "node_type")),
                stringAny(map, "refId", "ref_id", "agentId", "agent_id", "targetAgentId"),
                string(map, "instruction", ""),
                objectMap(map.get("config")),
                objectMap(
                        map.get("inputMapping") == null
                                ? map.get("input_mapping")
                                : map.get("inputMapping")),
                objectMap(
                        map.get("outputSchema") == null
                                ? map.get("output_schema")
                                : map.get("outputSchema")),
                numberLong(map.get("timeoutMs"), map.get("timeout_ms")),
                numberInt(map.get("maxRetries"), map.get("max_retries")),
                workflowFailurePolicy(map),
                workflowTransitions(map.get("transitions")),
                workflowPorts(map.get("inputPorts") == null ? map.get("input_ports") : map.get("inputPorts")),
                workflowPorts(map.get("outputPorts") == null ? map.get("output_ports") : map.get("outputPorts")));
    }

    private List<WorkflowPort> workflowPorts(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(Map.class::cast).map(this::workflowPort).toList();
    }

    private WorkflowPort workflowPort(Map<?, ?> raw) {
        Map<String, Object> map = normalize(raw);
        return new WorkflowPort(
                stringAny(map, "portId", "port_id", "id"),
                string(map, "direction", "input"),
                stringAny(map, "contractRef", "contract_ref"),
                objectMap(map.get("schema")),
                Boolean.TRUE.equals(map.get("required")),
                string(map, "cardinality", "one"),
                string(map, "description", ""));
    }

    private List<WorkflowEdge> workflowEdges(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(Map.class::cast).map(this::workflowEdge).toList();
    }

    private WorkflowEdge workflowEdge(Map<?, ?> raw) {
        Map<String, Object> map = normalize(raw);
        return new WorkflowEdge(
                stringAny(map, "edgeId", "edge_id", "id"),
                workflowEndpoint(map.get("from")),
                workflowEndpoint(map.get("to")),
                string(map, "kind", "data"),
                objectMap(map.get("binding")),
                objectMap(map.get("condition")),
                Boolean.TRUE.equals(map.get("defaultEdge")) || Boolean.TRUE.equals(map.get("default_edge")));
    }

    private WorkflowEndpoint workflowEndpoint(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> map = normalize(raw);
        return new WorkflowEndpoint(stringAny(map, "nodeId", "node_id"), stringAny(map, "portId", "port_id"));
    }

    private List<WorkflowTransition> workflowTransitions(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::workflowTransition)
                .toList();
    }

    private WorkflowTransition workflowTransition(Map<?, ?> raw) {
        Map<String, Object> map = normalize(raw);
        return new WorkflowTransition(
                string(map, "when", ""),
                stringAny(map, "nextStepId", "next_step_id", "next"),
                Boolean.TRUE.equals(map.get("defaultTransition"))
                        || Boolean.TRUE.equals(map.get("default_transition")));
    }

    private WorkflowStep.FailurePolicy workflowFailurePolicy(Map<String, Object> map) {
        String value = stringAny(map, "failurePolicy", "failure_policy");
        if (value.isBlank()) {
            return null;
        }
        try {
            return WorkflowStep.FailurePolicy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return WorkflowStep.FailurePolicy.FAIL_FAST;
        }
    }

    private Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((k, v) -> map.put(String.valueOf(k), v));
        return map;
    }

    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? normalize(map) : Map.of();
    }

    private String stringAny(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private Long numberLong(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer numberInt(Object primary, Object fallback) {
        Long value = numberLong(primary, fallback);
        return value == null ? null : value.intValue();
    }

    private Map<String, Object> childMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Map.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private String externalWorkspace(Path workspace) {
        if (agentRegistry instanceof YamlAgentDefinitionRegistry yamlRegistry) {
            return yamlRegistry.externalWorkspace(workspace);
        }
        return workspace == null ? null : workspace.toString();
    }

    public List<Map<String, Object>> tools() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ToolSpec spec : toolRegistry.all()) {
            rows.add(
                    row(
                            "tool_id",
                            spec.toolId(),
                            "name",
                            spec.toolId(),
                            "display_name",
                            spec.toolId(),
                            "description",
                            spec.description(),
                            "source_type",
                            spec.type(),
                            "category",
                            "standard",
                            "domain",
                            "platform",
                            "binding_status",
                            spec.enabled() ? "enabled" : "disabled",
                            "binding_visibility",
                            "discoverable",
                            "risk_level",
                            "python".equals(spec.type()) ? "high" : "low",
                            "side_effect",
                            "python".equals(spec.type()) ? "code_exec" : "read_only",
                            "parameter_schema",
                            spec.parameterSchema(),
                            "parameter_names",
                            parameterNames(spec.parameterSchema()),
                            "required",
                            requiredNames(spec.parameterSchema()),
                            "timeout_ms",
                            spec.timeoutMs()));
        }
        for (Map<String, Object> server : mcpServers.values()) {
            for (Map<String, Object> tool : mcpTools(server)) {
                rows.add(tool);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> skills() {
        return skillRegistry.all().stream()
                .map(
                        spec ->
                                row(
                                        "skill_id",
                                        spec.skillId(),
                                        "name",
                                        spec.skillId(),
                                        "display_name",
                                        spec.skillId(),
                                        "description",
                                        spec.description(),
                                        "domain",
                                        sourceDomain(spec.source()),
                                        "source",
                                        spec.source(),
                                        "scope",
                                        spec.scope(),
                                        "enabled",
                                        spec.enabled(),
                                        "type",
                                        spec.type(),
                                        "location",
                                        spec.location(),
                                        "writable",
                                        spec.writable(),
                                        "version",
                                        "v1"))
                .toList();
    }

    public Map<String, Object> upsertSkill(Map<String, Object> payload) {
        if (bool(payload.get("create_file"), false)
                || (!payload.containsKey("location") && payload.containsKey("content"))) {
            return createWorkspaceSkill(payload);
        }
        String skillId =
                string(
                        payload,
                        "skill_id",
                        string(payload, "skillId", string(payload, "name", "")));
        String type = string(payload, "type", "filesystem");
        String location =
                string(payload, "location", string(payload, "path", string(payload, "source", "")));
        String source = string(payload, "source", string(payload, "domain", "platform"));
        String scope = string(payload, "scope", "agent");
        boolean writable = bool(payload.get("writable"), false);
        boolean enabled = bool(payload.get("enabled"), true);
        String description = string(payload, "description", "");
        SkillSpec spec =
                new SkillSpec(
                        skillId, type, location, source, scope, writable, description, enabled);
        skillRegistry.upsert(spec);
        return skillRow(skillRegistry.find(spec.skillId()).orElse(spec));
    }

    private Map<String, Object> createWorkspaceSkill(Map<String, Object> payload) {
        String skillId =
                string(
                        payload,
                        "skill_id",
                        string(payload, "skillId", string(payload, "name", "")));
        if (skillId.isBlank()) {
            throw new IllegalArgumentException("Skill id cannot be blank");
        }
        String name = string(payload, "name", skillId);
        String description = string(payload, "description", "");
        if (description.isBlank()) {
            throw new IllegalArgumentException("Skill description cannot be blank");
        }
        String content = string(payload, "content", "");
        if (content.isBlank()) {
            throw new IllegalArgumentException("Skill content cannot be blank");
        }
        String source = string(payload, "source", string(payload, "domain", "platform"));
        String scope = string(payload, "scope", "platform");
        boolean enabled = bool(payload.get("enabled"), true);
        Path skillDir = storage.skillDirectory(skillId).normalize();
        Path skillFile = skillDir.resolve("SKILL.md");
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillFile, skillMarkdown(name, description, content));
            writeSkillScripts(skillDir, payload.get("scripts"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write skill file: " + skillFile, e);
        }
        SkillSpec spec =
                new SkillSpec(
                        skillId,
                        "filesystem",
                        "skills",
                        source,
                        scope,
                        false,
                        description,
                        enabled);
        skillRegistry.upsert(spec);
        syncSkillFiles(skillId, skillDir);
        return skillRow(spec);
    }

    private void writeSkillScripts(Path skillDir, Object scriptsValue) throws IOException {
        if (!(scriptsValue instanceof List<?> scripts) || scripts.isEmpty()) {
            return;
        }
        Path scriptsDir = skillDir.resolve("scripts").normalize();
        if (!scriptsDir.startsWith(skillDir)) {
            throw new IllegalArgumentException("Invalid scripts directory.");
        }
        Files.createDirectories(scriptsDir);
        for (Object item : scripts) {
            Map<String, Object> script = asStringKeyedMap(item);
            String name = string(script, "name", string(script, "path", ""));
            String content = string(script, "content", "");
            if (name.isBlank() || content.isBlank()) {
                continue;
            }
            String normalized = normalizeRelativePath(name);
            if (normalized.contains("/")) {
                normalized = fileName(normalized);
            }
            Path target = scriptsDir.resolve(normalized).normalize();
            if (!target.startsWith(scriptsDir)) {
                throw new IllegalArgumentException("Invalid script path: " + name);
            }
            Files.writeString(target, content);
        }
    }

    private String skillMarkdown(String name, String description, String content) {
        return "---\n"
                + "name: "
                + yamlScalar(name)
                + "\n"
                + "description: "
                + yamlScalar(description)
                + "\n"
                + "---\n\n"
                + content.strip()
                + "\n";
    }

    private String yamlScalar(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public Map<String, Object> setSkillEnabled(String skillId, boolean enabled) {
        SkillSpec existing =
                skillRegistry
                        .find(skillId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Unknown skill: " + skillId));
        SkillSpec updated =
                new SkillSpec(
                        existing.skillId(),
                        existing.type(),
                        existing.location(),
                        existing.source(),
                        existing.scope(),
                        existing.writable(),
                        existing.description(),
                        enabled);
        skillRegistry.upsert(updated);
        return skillRow(updated);
    }

    public Map<String, Object> testSkill(String skillId) {
        SkillSpec spec =
                skillRegistry
                        .find(skillId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Unknown skill: " + skillId));
        Map<String, Object> result = new LinkedHashMap<>(skillRow(spec));
        result.put("ok", spec.enabled());
        result.put("stage", "load");
        if (!spec.enabled()) {
            result.put("error", "Skill is disabled.");
            return result;
        }
        if (!"classpath".equals(spec.type())) {
            Path path = resolveSkillPath(spec);
            result.put("resolved_path", path.toString());
            result.put("exists", Files.exists(path));
            result.put("directory", Files.isDirectory(path));
            if (!Files.exists(path)) {
                result.put("ok", false);
                result.put("error", "Skill location does not exist.");
            }
        }
        if (Boolean.TRUE.equals(result.get("ok"))) {
            result.putAll(skillSandboxSmokeTestService.execute(spec));
        }
        return result;
    }

    public Map<String, Object> skillDetail(String skillId) {
        SkillSpec spec =
                skillRegistry
                        .find(skillId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Unknown skill: " + skillId));
        Map<String, Object> detail = new LinkedHashMap<>(skillRow(spec));
        if (!"classpath".equals(spec.type())) {
            Path path = resolveSkillDetailPath(spec);
            detail.put("resolved_path", path.toString());
            detail.put("exists", Files.exists(path));
            detail.put("directory", Files.isDirectory(path));
            List<String> files = skillFiles(path);
            String skillMarkdown = readSkillMarkdown(path);
            detail.put("files", files);
            detail.put("skill_markdown", skillMarkdown);
            detail.put("analysis", analyzeSkillFiles(files, skillMarkdown));
        } else {
            detail.put("files", List.of());
            detail.put("skill_markdown", "");
            detail.put("analysis", analyzeSkillFiles(List.of(), ""));
        }
        return detail;
    }

    public Map<String, Object> skillFileContent(String skillId, String relativePath) {
        SkillSpec spec =
                skillRegistry
                        .find(skillId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Unknown skill: " + skillId));
        Path root = resolveSkillDetailPath(spec);
        Path file = resolveSafeFile(root, relativePath);
        return row(
                "skill_id",
                skillId,
                "path",
                normalizeRelativePath(relativePath),
                "content",
                readTextFile(file),
                "size",
                fileSize(file));
    }

    public Map<String, Object> updateSkillFile(
            String skillId, String relativePath, Map<String, Object> payload) {
        SkillSpec spec =
                skillRegistry
                        .find(skillId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Unknown skill: " + skillId));
        if ("classpath".equals(spec.type())) {
            throw new IllegalArgumentException("Classpath skill files are read-only.");
        }
        Path root = resolveSkillDetailPath(spec);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Skill directory not found: " + skillId);
        }
        String normalized = normalizeRelativePath(relativePath);
        Path file = root.resolve(normalized).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Invalid skill file path: " + relativePath);
        }
        String content = string(payload, "content", "");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write skill file: " + normalized, e);
        }
        try {
            artifactStore.saveSkillFile(skillId, normalized, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist skill file: " + normalized, e);
        }
        if ("SKILL.md".equalsIgnoreCase(fileName(normalized))) {
            Map<String, String> meta = parseSkillMarkdownMeta(content);
            String description = meta.getOrDefault("description", spec.description());
            skillRegistry.upsert(
                    new SkillSpec(
                            spec.skillId(),
                            spec.type(),
                            spec.location(),
                            spec.source(),
                            spec.scope(),
                            spec.writable(),
                            description,
                            spec.enabled()));
        }
        Map<String, Object> detail = skillDetail(skillId);
        detail.put("saved_path", normalized);
        return detail;
    }

    public void deleteSkill(String skillId) {
        SkillSpec spec = skillRegistry.find(skillId).orElse(null);
        skillRegistry.delete(skillId);
        removeSkillFromAgents(skillId);
        if (spec != null && "filesystem".equals(spec.type()) && "skills".equals(spec.location())) {
            Path skillDir = storage.skillDirectory(skillId);
            if (skillDir.startsWith(storage.skillsRoot())) {
                deleteDirectory(skillDir);
            }
        }
        artifactStore.deleteSkillFiles(skillId);
        audit("skill.deleted", skillId, row("skill_id", skillId));
    }

    private List<String> skillFiles(Path root) {
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String readSkillMarkdown(Path root) {
        Path skillFile = root.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
            return "";
        }
        try {
            return Files.readString(skillFile);
        } catch (IOException e) {
            return "";
        }
    }

    private static Map<String, String> parseSkillMarkdownMeta(String content) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (content == null || !content.startsWith("---")) {
            return meta;
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return meta;
        }
        String header = content.substring(3, end);
        for (String line : header.split("\\R")) {
            int split = line.indexOf(':');
            if (split <= 0) {
                continue;
            }
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            meta.put(key, value);
        }
        return meta;
    }

    private static Map<String, Object> analyzeSkillFiles(List<String> files, String skillMarkdown) {
        List<String> normalized =
                files == null
                        ? List.of()
                        : files.stream()
                                .filter(path -> path != null && !path.isBlank())
                                .map(path -> path.replace('\\', '/'))
                                .sorted()
                                .toList();
        List<String> scripts =
                normalized.stream().filter(PlatformCompatibilityState::isScriptFile).toList();
        List<String> examples =
                normalized.stream().filter(PlatformCompatibilityState::isExampleFile).toList();
        List<String> docs =
                normalized.stream().filter(PlatformCompatibilityState::isDocFile).toList();
        List<String> configs =
                normalized.stream().filter(PlatformCompatibilityState::isConfigFile).toList();
        List<String> entrypoints =
                normalized.stream().filter(PlatformCompatibilityState::isEntrypointFile).toList();
        return row(
                "file_count",
                normalized.size(),
                "has_skill_md",
                normalized.stream().anyMatch(path -> fileName(path).equalsIgnoreCase("SKILL.md")),
                "has_readme",
                normalized.stream().anyMatch(path -> fileName(path).equalsIgnoreCase("README.md")),
                "markdown_chars",
                skillMarkdown == null ? 0 : skillMarkdown.length(),
                "script_count",
                scripts.size(),
                "scripts",
                scripts,
                "example_count",
                examples.size(),
                "examples",
                examples,
                "doc_count",
                docs.size(),
                "docs",
                docs,
                "config_count",
                configs.size(),
                "configs",
                configs,
                "entrypoints",
                entrypoints);
    }

    private static boolean isScriptFile(String path) {
        String name = fileName(path).toLowerCase();
        return path.startsWith("scripts/")
                || path.contains("/scripts/")
                || name.endsWith(".py")
                || name.endsWith(".js")
                || name.endsWith(".mjs")
                || name.endsWith(".ts")
                || name.endsWith(".sh")
                || name.endsWith(".ps1")
                || name.endsWith(".bat");
    }

    private static boolean isExampleFile(String path) {
        String lower = path.toLowerCase();
        return lower.startsWith("examples/")
                || lower.contains("/examples/")
                || lower.startsWith("example/")
                || lower.contains("/example/")
                || fileName(lower).contains("example");
    }

    private static boolean isDocFile(String path) {
        String name = fileName(path).toLowerCase();
        return name.endsWith(".md") || name.endsWith(".mdx") || name.endsWith(".txt");
    }

    private static boolean isConfigFile(String path) {
        String name = fileName(path).toLowerCase();
        return name.endsWith(".json")
                || name.endsWith(".yaml")
                || name.endsWith(".yml")
                || name.endsWith(".toml")
                || name.endsWith(".properties")
                || name.endsWith(".xml");
    }

    private static boolean isEntrypointFile(String path) {
        String name = fileName(path).toLowerCase();
        return name.equals("skill.md")
                || name.equals("readme.md")
                || name.equals("package.json")
                || name.equals("pyproject.toml")
                || name.equals("pom.xml");
    }

    private static String fileName(String path) {
        int index = path == null ? -1 : path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : String.valueOf(path);
    }

    private Path resolveSafeFile(Path root, String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        Path file = root.resolve(normalized).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Skill file not found: " + relativePath);
        }
        return file;
    }

    private static String normalizeRelativePath(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/').trim();
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("../")) {
            throw new IllegalArgumentException("Invalid relative path: " + relativePath);
        }
        return normalized;
    }

    private static String readTextFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file: " + file, e);
        }
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private void removeSkillFromAgents(String skillId) {
        for (AgentDefinition definition : agentRegistry.allPublished()) {
            if (!definition.skillRefs().contains(skillId)) {
                continue;
            }
            List<String> next =
                    definition.skillRefs().stream().filter(ref -> !skillId.equals(ref)).toList();
            agentRegistry.upsert(
                    new YamlAgentDefinitionRegistry.AgentConfig(
                            definition.agentId(),
                            definition.version(),
                            definition.name(),
                            definition.model(),
                            definition.modelPolicy(),
                            definition.systemPrompt(),
                            definition.enabled(),
                            externalWorkspace(definition.workspace()),
                            definition.toolRefs(),
                            definition.mcpRefs(),
                            next,
                            definition.orchestration()));
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    throw new IllegalStateException(
                                            "Failed to delete path: " + path, e);
                                }
                            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete directory: " + dir, e);
        }
    }

    public List<Map<String, Object>> skillPackages(String domain, String status) {
        return skillPackages.values().stream()
                .filter(
                        pkg ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(pkg.get("domain")))
                .filter(
                        pkg ->
                                status == null
                                        || status.isBlank()
                                        || status.equals(pkg.get("status")))
                .sorted(
                        Comparator.comparing(
                                        (Map<String, Object> pkg) ->
                                                String.valueOf(pkg.getOrDefault("created_at", "")))
                                .reversed())
                .map(pkg -> (Map<String, Object>) new LinkedHashMap<String, Object>(pkg))
                .toList();
    }

    public Map<String, Object> uploadSkillPackage(
            Path zipPath, String filename, String domain, Map<String, String> metadata) {
        String source = domain == null || domain.isBlank() ? "platform" : domain;
        Map<String, String> meta = metadata == null ? Map.of() : metadata;
        Map<String, Object> row = new LinkedHashMap<>();
        String id = "pkg_" + Instant.now().toEpochMilli();
        try {
            AgentSkill skill = SkillUtil.createFromZip(zipPath, source);
            String upstreamName = String.valueOf(skill.getMetadata().get("name"));
            String upstreamDescription = String.valueOf(skill.getMetadata().get("description"));
            String skillName = firstNonBlank(meta.get("skill_id"), upstreamName);
            String displayName = firstNonBlank(meta.get("name"), skillName);
            String version = firstNonBlank(meta.get("version"), "v1");
            String description = firstNonBlank(meta.get("description"), upstreamDescription);
            Path packageDir = skillPackagesDir();
            Files.createDirectories(packageDir);
            Path stored = packageDir.resolve(id + ".zip");
            Files.copy(zipPath, stored, StandardCopyOption.REPLACE_EXISTING);
            row.put("id", id);
            row.put("skill_id", skillName);
            row.put("name", displayName);
            row.put("version", version);
            row.put("domain", source);
            row.put("source", source);
            row.put("source_note", firstNonBlank(meta.get("source_note"), ""));
            row.put("upstream_skill_name", upstreamName);
            row.put("filename", filename);
            row.put("zip_path", stored.toString());
            row.put("description", description);
            row.put("status", "validated");
            row.put("created_at", Instant.now().toString());
            row.put("validation_errors", List.of());
        } catch (Exception e) {
            row.put("id", id);
            row.put(
                    "skill_id",
                    firstNonBlank(
                            meta.get("skill_id"),
                            filename == null ? id : filename.replaceAll("\\.zip$", "")));
            row.put("name", firstNonBlank(meta.get("name"), row.get("skill_id")));
            row.put("version", firstNonBlank(meta.get("version"), "v1"));
            row.put("domain", source);
            row.put("source", source);
            row.put("source_note", firstNonBlank(meta.get("source_note"), ""));
            row.put("filename", filename);
            row.put("description", firstNonBlank(meta.get("description"), ""));
            row.put("status", "rejected");
            row.put("created_at", Instant.now().toString());
            row.put("validation_errors", List.of(e.getMessage()));
        }
        if ("validated".equals(row.get("status"))) {
            try {
                Path stored = storage.resolveRelativeToWorkspace(String.valueOf(row.get("zip_path")));
                byte[] content = Files.readAllBytes(stored);
                artifactStore.saveSkillPackage(id, String.valueOf(row.get("filename")), content);
                row.put("zip_path", storage.toWorkspaceRelative(stored));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to persist skill package artifact: " + id, e);
            }
        }
        skillPackages.put(id, row);
        persistSkillPackages();
        return Map.copyOf(row);
    }

    public Map<String, Object> previewSkillPackage(String id) {
        Map<String, Object> pkg = packageById(id);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.putAll(new LinkedHashMap<>(pkg));
        Object zipPath = pkg.get("zip_path");
        if (zipPath == null || String.valueOf(zipPath).isBlank()) {
            preview.put("files", List.of());
            preview.put("skill_markdown", "");
            preview.put("preview_error", "No package archive is available for preview.");
            return preview;
        }
        Path archive = ensureSkillPackageArchive(pkg);
        List<String> files = new ArrayList<>();
        String skillMarkdown = "";
        try (InputStream input = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                files.add(name);
                if (name.endsWith("/SKILL.md") || "SKILL.md".equals(name)) {
                    skillMarkdown =
                            new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            preview.put("files", files);
            preview.put("skill_markdown", skillMarkdown);
            preview.put("file_count", files.size());
            preview.put("analysis", analyzeSkillFiles(files, skillMarkdown));
            return preview;
        } catch (Exception e) {
            preview.put("files", files);
            preview.put("skill_markdown", "");
            preview.put("analysis", analyzeSkillFiles(files, ""));
            preview.put("preview_error", e.getMessage());
            return preview;
        }
    }

    public Map<String, Object> previewSkillPackageFile(String id, String filePath) {
        Map<String, Object> pkg = packageById(id);
        Object zipPath = pkg.get("zip_path");
        if (zipPath == null || String.valueOf(zipPath).isBlank()) {
            throw new IllegalArgumentException("No package archive is available for preview.");
        }
        String normalized = normalizeRelativePath(filePath);
        Path archive = ensureSkillPackageArchive(pkg);
        try (InputStream input = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.equals(normalized)) {
                    return row(
                            "id",
                            id,
                            "path",
                            normalized,
                            "content",
                            new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                            "size",
                            entry.getSize());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read package file: " + normalized, e);
        }
        throw new IllegalArgumentException("Package file not found: " + normalized);
    }

    public Map<String, Object> uploadSkillPackageDirectory(
            Path directory, String folderName, String domain, Map<String, String> metadata) {
        Path zipPath = null;
        try {
            zipPath = Files.createTempFile("skill-package-folder-", ".zip");
            zipSkillDirectory(directory, zipPath, folderName);
            return uploadSkillPackage(
                    zipPath, folderName == null ? "skill-folder" : folderName, domain, metadata);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to package skill folder: " + directory, e);
        } finally {
            if (zipPath != null) {
                try {
                    Files.deleteIfExists(zipPath);
                } catch (IOException e) {
                    log.warn(
                            "Failed to delete temp skill folder zip {}: {}",
                            zipPath,
                            e.getMessage());
                }
            }
        }
    }

    public Map<String, Object> publishSkillPackage(String id, List<String> permissions) {
        Map<String, Object> pkg = packageById(id);
        if (!"validated".equals(pkg.get("status")) && !"published".equals(pkg.get("status"))) {
            throw new IllegalStateException("Skill package is not publishable: " + id);
        }
        Path zipPath = ensureSkillPackageArchive(pkg);
        String skillId = String.valueOf(pkg.get("skill_id"));
        Path target = storage.skillDirectory(skillId).normalize();
        if (Files.exists(target)) {
            deleteDirectory(target);
        }
        unzipSingleRoot(zipPath, target);
        pkg.put("status", "published");
        pkg.put("published_at", Instant.now().toString());
        pkg.put("granted_permissions", permissions);
        skillRegistry.upsert(
                new SkillSpec(
                        skillId,
                        "filesystem",
                        "skills",
                        String.valueOf(pkg.getOrDefault("source", "platform")),
                        "platform",
                        false,
                        String.valueOf(pkg.getOrDefault("description", "")),
                        true));
        persistSkillPackages();
        return new LinkedHashMap<>(pkg);
    }

    public Map<String, Object> rejectSkillPackage(String id, String reason) {
        Map<String, Object> pkg = packageById(id);
        pkg.put("status", "rejected");
        pkg.put("reject_reason", reason == null ? "" : reason);
        pkg.put("rejected_at", Instant.now().toString());
        persistSkillPackages();
        return new LinkedHashMap<>(pkg);
    }

    public Map<String, Object> updateSkillPackagePermissions(String id, List<String> permissions) {
        Map<String, Object> pkg = packageById(id);
        pkg.put("granted_permissions", permissions);
        persistSkillPackages();
        return new LinkedHashMap<>(pkg);
    }

    public void deleteSkillPackage(String id) {
        Map<String, Object> removed = skillPackages.remove(id);
        if (removed != null) {
            Object zipPath = removed.get("zip_path");
            if (zipPath != null) {
                try {
                    Files.deleteIfExists(
                            storage.resolveRelativeToWorkspace(String.valueOf(zipPath)));
                } catch (IOException e) {
                    log.warn("Failed to delete skill package zip {}: {}", zipPath, e.getMessage());
                }
            }
            artifactStore.deleteSkillPackage(id);
            persistSkillPackages();
        }
    }

    private Map<String, Object> skillRow(SkillSpec spec) {
        return row(
                "skill_id",
                spec.skillId(),
                "name",
                spec.skillId(),
                "display_name",
                spec.skillId(),
                "description",
                spec.description(),
                "domain",
                sourceDomain(spec.source()),
                "source",
                spec.source(),
                "scope",
                spec.scope(),
                "enabled",
                spec.enabled(),
                "type",
                spec.type(),
                "location",
                spec.location(),
                "writable",
                spec.writable(),
                "version",
                "v1");
    }

    private Path resolveSkillPath(SkillSpec spec) {
        String location = spec.location();
        if (location == null || location.isBlank()) {
            return storage.skillDirectory(spec.skillId());
        }
        return storage.resolveRelativeToWorkspace(location);
    }

    private Path resolveSkillDetailPath(SkillSpec spec) {
        Path path = resolveSkillPath(spec);
        if ("skills".equals(spec.location())) {
            Path candidate = path.resolve(spec.skillId()).normalize();
            if (candidate.startsWith(path)) {
                return candidate;
            }
        }
        return path;
    }

    private Map<String, Object> packageById(String id) {
        Map<String, Object> pkg = skillPackages.get(id);
        if (pkg == null) {
            throw new IllegalArgumentException("Skill package not found: " + id);
        }
        return pkg;
    }

    private static String firstNonBlank(Object first, Object fallback) {
        String value = first == null ? "" : String.valueOf(first).trim();
        if (!value.isBlank()) {
            return value;
        }
        return fallback == null ? "" : String.valueOf(fallback).trim();
    }

    private Path mcpDiscoveryCachePath() {
        return storage.mcpDiscoveryCachePath();
    }

    private Path skillPackagesFile() {
        return storage.skillPackagesFile();
    }

    private Path skillPackagesDir() {
        return storage.skillPackagesDir();
    }

    private Path domainsFile() {
        return storage.domainsFile();
    }

    private Path modelSlotsFile() {
        return storage.modelSlotsFile();
    }

    private boolean isSqliteEnabled() {
        return storage.isSqliteEnabled();
    }

    private void seedPlatformDomain() {
        domains.computeIfAbsent(
                "platform",
                domain ->
                        row(
                                "domain",
                                "platform",
                                "display_name",
                                "平台",
                                "description",
                                "平台默认业务域",
                                "org_id",
                                "platform",
                                "status",
                                "active",
                                "created_at",
                                Instant.now().toString()));
    }

    private void loadDomains() {
        if (!isSqliteEnabled()) {
            loadDomainsFromFile();
            return;
        }
        domains.clear();
        loadDomainsFromSqlite();
    }

    private void persistDomains() {
        if (isSqliteEnabled()) {
            upsertDomainRows();
            return;
        }
        Path path = domainsFile();
        try {
            Files.createDirectories(path.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", "1");
            root.put("domains", domains().stream().map(LinkedHashMap::new).toList());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist domains: " + path, e);
        }
    }

    private void loadSkillPackages() {
        if (!isSqliteEnabled()) {
            loadSkillPackagesFromFile();
            return;
        }
        skillPackages.clear();
        loadSkillPackagesFromSqlite();
    }

    /** Restore persisted skill files into the workspace and migrate legacy files into SQLite. */
    private void loadSkillFiles() {
        for (SkillSpec spec : skillRegistry.all()) {
            if ("classpath".equals(spec.type())) {
                continue;
            }
            Path root = resolveSkillDetailPath(spec);
            Map<String, byte[]> persisted = artifactStore.loadSkillFiles(spec.skillId());
            if (!persisted.isEmpty()) {
                persisted.forEach((relativePath, content) -> writePersistedSkillFile(root, relativePath, content));
            } else if (Files.isDirectory(root)) {
                syncSkillFiles(spec.skillId(), root);
            }
        }
    }

    private void writePersistedSkillFile(Path root, String relativePath, byte[] content) {
        if (relativePath == null || relativePath.isBlank() || content == null) {
            return;
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            log.warn("Skip persisted skill file outside skill root: {}", relativePath);
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to restore skill file: " + relativePath, e);
        }
    }

    private void syncSkillFiles(String skillId, Path root) {
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(
                    file -> {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        try {
                            artifactStore.saveSkillFile(skillId, relative, Files.readAllBytes(file));
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to persist skill file: " + file, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan skill files: " + root, e);
        }
    }

    /** Restore Python tool scripts into the workspace and migrate legacy scripts into SQLite. */
    private void loadToolFiles() {
        for (ToolSpec spec : toolRegistry.all()) {
            if (!"python".equalsIgnoreCase(spec.type()) || spec.toolId() == null) {
                continue;
            }
            Path toolRoot = storage.toolCodeDirectory(spec.toolId()).normalize();
            Path currentScript = toolRoot.resolve("tool.py").normalize();
            Map<String, byte[]> persisted = artifactStore.loadToolFiles(spec.toolId());
            try {
                if (!persisted.isEmpty()) {
                    persisted.forEach(
                            (relativePath, content) -> writePersistedToolFile(toolRoot, relativePath, content));
                } else {
                    Path legacy = storage.resolveRelativeToWorkspace(spec.scriptPath());
                    if (Files.isRegularFile(legacy)) {
                        artifactStore.saveToolFile(spec.toolId(), "tool.py", Files.readAllBytes(legacy));
                        if (!legacy.equals(currentScript)) {
                            Files.createDirectories(toolRoot);
                            Files.copy(legacy, currentScript, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else if (Files.isRegularFile(currentScript)) {
                        artifactStore.saveToolFile(spec.toolId(), "tool.py", Files.readAllBytes(currentScript));
                    }
                }
                if (Files.isRegularFile(currentScript)) {
                    String normalizedPath = storage.toWorkspaceRelative(currentScript);
                    if (!normalizedPath.equals(spec.scriptPath())) {
                        toolRegistry.upsert(
                                new ToolSpec(
                                        spec.toolId(),
                                        spec.type(),
                                        spec.className(),
                                        spec.description(),
                                        spec.enabled(),
                                        normalizedPath,
                                        spec.parameterSchema(),
                                        spec.timeoutMs()));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to restore tool script: " + spec.toolId(), e);
            }
        }
    }

    private void writePersistedToolFile(Path root, String relativePath, byte[] content) {
        if (relativePath == null || relativePath.isBlank() || content == null) {
            return;
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            log.warn("Skip persisted tool file outside tool root: {}", relativePath);
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to restore tool file: " + relativePath, e);
        }
    }

    private void persistSkillPackages() {
        if (isSqliteEnabled()) {
            upsertSkillPackages();
            return;
        }
        Path path = skillPackagesFile();
        try {
            Files.createDirectories(path.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", "1");
            root.put("packages", skillPackages.values().stream().map(LinkedHashMap::new).toList());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist skill packages: " + path, e);
        }
    }

    private void loadModelSlots() {
        if (isSqliteEnabled()) {
            slotBindings.clear();
            aliases.clear();
            loadSlotBindingsFromSqlite();
            loadModelAliasesFromSqlite();
            return;
        }
        slotBindings.clear();
        aliases.clear();
        loadModelSlotsFromFile();
    }

    private void persistModelSlots() {
        if (isSqliteEnabled()) {
            upsertModelSlots();
            return;
        }
        Path path = modelSlotsFile();
        try {
            Files.createDirectories(path.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", "1");
            root.put(
                    "slot_bindings",
                    slotBindings.values().stream().map(LinkedHashMap::new).toList());
            root.put("aliases", aliases.values().stream().map(LinkedHashMap::new).toList());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist model slots: " + path, e);
        }
    }

    private void loadDomainsFromFile() {
        Path path = domainsFile();
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, Object> root =
                    objectMapper.readValue(
                            path.toFile(), new TypeReference<Map<String, Object>>() {});
            Object items = root.get("domains");
            if (items instanceof List<?> list) {
                domains.clear();
                for (Object item : list) {
                    Map<String, Object> row = asStringKeyedMap(item);
                    String domain = String.valueOf(row.getOrDefault("domain", ""));
                    if (!domain.isBlank()) {
                        domains.put(domain, row);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load domains {}: {}", path, e.getMessage());
        }
    }

    private void loadSkillPackagesFromFile() {
        Path path = skillPackagesFile();
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, Object> root =
                    objectMapper.readValue(
                            path.toFile(), new TypeReference<Map<String, Object>>() {});
            Object packages = root.get("packages");
            if (packages instanceof List<?> list) {
                skillPackages.clear();
                for (Object item : list) {
                    Map<String, Object> row = asStringKeyedMap(item);
                    String id = String.valueOf(row.getOrDefault("id", ""));
                    if (!id.isBlank()) {
                        skillPackages.put(id, row);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load skill package cache {}: {}", path, e.getMessage());
        }
    }

    private void loadModelSlotsFromFile() {
        Path path = modelSlotsFile();
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, Object> root =
                    objectMapper.readValue(
                            path.toFile(), new TypeReference<Map<String, Object>>() {});
            Object bindings = root.get("slot_bindings");
            if (bindings instanceof List<?> list) {
                slotBindings.clear();
                for (Object item : list) {
                    Map<String, Object> row = asStringKeyedMap(item);
                    String slotKey = String.valueOf(row.getOrDefault("slot_key", ""));
                    if (!slotKey.isBlank()) {
                        slotBindings.put(slotKey, row);
                    }
                }
            }
            Object aliasRows = root.get("aliases");
            if (aliasRows instanceof List<?> list) {
                aliases.clear();
                for (Object item : list) {
                    Map<String, Object> row = asStringKeyedMap(item);
                    String id = String.valueOf(row.getOrDefault("id", ""));
                    if (!id.isBlank()) {
                        aliases.put(id, row);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load model slot config {}: {}", path, e.getMessage());
        }
    }

    private boolean loadDomainsFromSqlite() {
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT domain_id, payload FROM "
                                        + SQLITE_DOMAINS_TABLE
                                        + " ORDER BY domain_id")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String payload = resultSet.getString("payload");
                    Map<String, Object> row = mapFromJson(payload);
                    if (row.isEmpty()) {
                        continue;
                    }
                    String domain = string(row, "domain", resultSet.getString("domain_id"));
                    if (!domain.isBlank()) {
                        domains.put(domain, row);
                    }
                }
                return !domains.isEmpty();
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to load domains from sqlite {}: {}",
                    SQLITE_DOMAINS_TABLE,
                    e.getMessage());
            return false;
        }
    }

    private void upsertDomainRows() {
        String deleteSql = "DELETE FROM " + SQLITE_DOMAINS_TABLE;
        String upsertSql =
                "INSERT INTO "
                        + SQLITE_DOMAINS_TABLE
                        + " (domain_id, payload, updated_at) VALUES (?, ?, ?) ON"
                        + " CONFLICT(domain_id) DO UPDATE SET payload = excluded.payload,"
                        + " updated_at = excluded.updated_at";
        upsertRows(deleteSql, upsertSql, domains.values(), "domain");
    }

    private boolean loadSkillPackagesFromSqlite() {
        List<Map<String, Object>> loaded = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT package_id, payload FROM "
                                        + SQLITE_SKILL_PACKAGES_TABLE
                                        + " ORDER BY package_id")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String payload = resultSet.getString("payload");
                    Map<String, Object> row = mapFromJson(payload);
                    if (row.isEmpty()) {
                        continue;
                    }
                    String id = string(row, "id", resultSet.getString("package_id"));
                    if (!id.isBlank()) {
                        loaded.add(row);
                    }
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to load skill packages from sqlite {}: {}",
                    SQLITE_SKILL_PACKAGES_TABLE,
                    e.getMessage());
            return false;
        }
        boolean migrated = false;
        for (Map<String, Object> row : loaded) {
            String id = string(row, "id", "");
            skillPackages.put(id, row);
            Path archive = ensureSkillPackageArchive(row);
            if (archive != null
                    && !storage.toWorkspaceRelative(archive)
                            .equals(String.valueOf(row.get("zip_path")))) {
                row.put("zip_path", storage.toWorkspaceRelative(archive));
                migrated = true;
            }
        }
        if (migrated) {
            persistSkillPackages();
        }
        return !skillPackages.isEmpty();
    }

    private void upsertSkillPackages() {
        String deleteSql = "DELETE FROM " + SQLITE_SKILL_PACKAGES_TABLE;
        String upsertSql =
                "INSERT INTO "
                        + SQLITE_SKILL_PACKAGES_TABLE
                        + " (package_id, payload, updated_at) VALUES (?, ?, ?) ON"
                        + " CONFLICT(package_id) DO UPDATE SET payload = excluded.payload,"
                        + " updated_at = excluded.updated_at";
        upsertRows(deleteSql, upsertSql, skillPackages.values(), "id");
    }

    private void loadSlotBindingsFromSqlite() {
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT slot_key, payload FROM "
                                        + SQLITE_MODEL_SLOT_TABLE
                                        + " ORDER BY slot_key")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String payload = resultSet.getString("payload");
                    Map<String, Object> row = mapFromJson(payload);
                    if (row.isEmpty()) {
                        continue;
                    }
                    String slotKey = string(row, "slot_key", resultSet.getString("slot_key"));
                    if (!slotKey.isBlank()) {
                        slotBindings.put(slotKey, row);
                    }
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to load model slot bindings from sqlite {}: {}",
                    SQLITE_MODEL_SLOT_TABLE,
                    e.getMessage());
        }
    }

    private void loadModelAliasesFromSqlite() {
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT alias_id, payload FROM "
                                        + SQLITE_MODEL_ALIAS_TABLE
                                        + " ORDER BY alias_id")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String payload = resultSet.getString("payload");
                    Map<String, Object> row = mapFromJson(payload);
                    if (row.isEmpty()) {
                        continue;
                    }
                    String id = string(row, "id", resultSet.getString("alias_id"));
                    if (!id.isBlank()) {
                        aliases.put(id, row);
                    }
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to load model aliases from sqlite {}: {}",
                    SQLITE_MODEL_ALIAS_TABLE,
                    e.getMessage());
        }
    }

    private void upsertModelSlots() {
        upsertRows(
                "DELETE FROM " + SQLITE_MODEL_SLOT_TABLE,
                "INSERT INTO "
                        + SQLITE_MODEL_SLOT_TABLE
                        + " (slot_key, payload, updated_at) VALUES (?, ?, ?) ON CONFLICT(slot_key)"
                        + " DO UPDATE SET payload = excluded.payload, updated_at ="
                        + " excluded.updated_at",
                slotBindings.values(),
                "slot_key");
        upsertRows(
                "DELETE FROM " + SQLITE_MODEL_ALIAS_TABLE,
                "INSERT INTO "
                        + SQLITE_MODEL_ALIAS_TABLE
                        + " (alias_id, payload, updated_at) VALUES (?, ?, ?) ON CONFLICT(alias_id)"
                        + " DO UPDATE SET payload = excluded.payload, updated_at ="
                        + " excluded.updated_at",
                aliases.values(),
                "id");
    }

    private void upsertRows(
            String deleteSql,
            String upsertSql,
            Iterable<Map<String, Object>> rows,
            String keyField) {
        try (Connection connection = storage.connection();
                Statement deleteStatement = connection.createStatement();
                PreparedStatement upsertStatement = connection.prepareStatement(upsertSql)) {
            connection.setAutoCommit(false);
            try {
                deleteStatement.executeUpdate(deleteSql);
                String now = Instant.now().toString();
                for (Map<String, Object> row : rows) {
                    String key = string(row, keyField, "");
                    if (key.isBlank()) {
                        continue;
                    }
                    upsertStatement.setString(1, key);
                    upsertStatement.setString(2, objectMapper.writeValueAsString(row));
                    upsertStatement.setString(3, now);
                    upsertStatement.addBatch();
                }
                upsertStatement.executeBatch();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist rows to sqlite", e);
        }
    }

    private Map<String, Object> mapFromJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse sqlite persistence row: {}", e.getMessage());
            return Map.of();
        }
    }

    private void initCompatibilitySqliteSchema() {
        try {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_DOMAINS_TABLE
                            + " (domain_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT"
                            + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_SKILL_PACKAGES_TABLE
                            + " (package_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at"
                            + " TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_MODEL_SLOT_TABLE
                            + " (slot_key TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT"
                            + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_MODEL_ALIAS_TABLE
                            + " (alias_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT"
                            + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_MCP_DISCOVERY_TABLE
                            + " (server_id TEXT PRIMARY KEY, probe_payload TEXT NOT NULL,"
                            + " tools_payload TEXT NOT NULL, updated_at TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_AUDIT_EVENTS_TABLE
                            + " (id INTEGER PRIMARY KEY AUTOINCREMENT, event_type TEXT NOT NULL,"
                            + " target_id TEXT, payload TEXT NOT NULL, actor TEXT, created_at TEXT"
                            + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_PROBE_RUNS_TABLE
                            + " (id INTEGER PRIMARY KEY AUTOINCREMENT, target_type TEXT NOT NULL,"
                            + " target_id TEXT NOT NULL, ok INTEGER NOT NULL, stage TEXT, payload"
                            + " TEXT NOT NULL, created_at TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_MIGRATION_HISTORY_TABLE
                            + " (migration_key TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT"
                            + " NOT NULL, status TEXT NOT NULL, message TEXT, created_at TEXT NOT"
                            + " NULL, updated_at TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_MEMORY_TABLE
                            + " (memory_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT"
                            + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_RUNS_TABLE
                            + " (run_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT"
                            + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_RUN_STEPS_TABLE
                            + " (run_id TEXT NOT NULL, step_id TEXT NOT NULL, payload TEXT NOT NULL,"
                            + " updated_at TEXT NOT NULL, PRIMARY KEY (run_id, step_id))",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_RUN_EVENTS_TABLE
                            + " (event_id INTEGER PRIMARY KEY, run_id TEXT NOT NULL, event_type"
                            + " TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS idx_platform_agent_run_events_run_id ON "
                            + SQLITE_RUN_EVENTS_TABLE
                            + " (run_id, event_id)",
                    "CREATE TABLE IF NOT EXISTS "
                            + SQLITE_WAITINGS_TABLE
                            + " (waiting_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, payload TEXT"
                            + " NOT NULL, updated_at TEXT NOT NULL)");
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Failed to init sqlite compatibility schema", e);
        }
    }

    private void unzipSingleRoot(Path zipPath, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            try (InputStream input = Files.newInputStream(zipPath);
                    ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                String root = null;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.contains("../")) {
                        throw new IllegalArgumentException("Invalid zip entry: " + entry.getName());
                    }
                    int slash = name.indexOf('/');
                    if (slash <= 0) {
                        throw new IllegalArgumentException(
                                "Zip entries must have one root folder.");
                    }
                    String entryRoot = name.substring(0, slash);
                    root = root == null ? entryRoot : root;
                    if (!root.equals(entryRoot)) {
                        throw new IllegalArgumentException(
                                "Zip entries must share one root folder.");
                    }
                    String rel = name.substring(slash + 1);
                    Path out = targetDir.resolve(rel).normalize();
                    if (!out.startsWith(targetDir)) {
                        throw new IllegalArgumentException("Zip entry escapes target dir: " + name);
                    }
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to publish skill package: " + zipPath, e);
        }
    }

    private void zipSkillDirectory(Path directory, Path zipPath, String fallbackName)
            throws IOException {
        Path root = skillFolderRoot(directory);
        String rootName = root.getFileName() == null ? "" : root.getFileName().toString();
        if (rootName.isBlank()) {
            rootName = fallbackName == null || fallbackName.isBlank() ? "skill" : fallbackName;
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath));
                var stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                Path rel = root.relativize(path);
                String relName = rel.toString().replace('\\', '/');
                if (relName.isBlank() || relName.startsWith("/") || relName.contains("../")) {
                    throw new IllegalArgumentException("Invalid skill folder entry: " + relName);
                }
                zip.putNextEntry(new ZipEntry(rootName + "/" + relName));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private Path skillFolderRoot(Path directory) throws IOException {
        Path directSkill = directory.resolve("SKILL.md");
        if (Files.isRegularFile(directSkill)) {
            return directory;
        }
        try (var children = Files.list(directory)) {
            List<Path> dirs = children.filter(Files::isDirectory).toList();
            if (dirs.size() == 1 && Files.isRegularFile(dirs.get(0).resolve("SKILL.md"))) {
                return dirs.get(0);
            }
        }
        throw new IllegalArgumentException("Skill folder must contain SKILL.md at its root.");
    }

    public List<Map<String, Object>> mcpServers() {
        return sorted(mcpServers.values(), "id");
    }

    public List<Map<String, Object>> mcpBoundAgents(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        return agentRegistry.allPublished().stream()
                .filter(definition -> definition.mcpRefs().contains(serverId))
                .map(
                        definition ->
                                row(
                                        "agent_id",
                                        definition.agentId(),
                                        "name",
                                        definition.name(),
                                        "display_name",
                                        definition.name(),
                                        "enabled",
                                        true))
                .toList();
    }

    public Map<String, Object> upsertMcpServer(String id, Map<String, Object> payload) {
        String serverId =
                id == null || id.isBlank() ? String.valueOf(sequence.getAndIncrement()) : id;
        Map<String, Object> existing =
                new LinkedHashMap<>(mcpServers.getOrDefault(serverId, Map.of()));
        Map<String, Object> row = new LinkedHashMap<>(existing);
        row.put("id", serverId);
        row.put("name", string(payload, "name", string(row, "name", "mcp-" + serverId)));
        row.put(
                "transport",
                string(payload, "transport", string(row, "transport", "streamable-http")));
        row.put("command", string(payload, "command", string(row, "command", "")));
        if (payload.containsKey("args")) {
            row.put("args", stringList(payload.get("args")));
        } else {
            row.putIfAbsent("args", List.of());
        }
        row.put(
                "endpoint",
                string(payload, "endpoint", string(payload, "url", string(row, "endpoint", ""))));
        row.put("description", string(payload, "description", string(row, "description", "")));
        row.put(
                "timeout_ms",
                number(payload.get("timeout_ms"), number(row.get("timeout_ms"), 5000)));
        row.put(
                "tool_filter",
                payload.getOrDefault("tool_filter", row.getOrDefault("tool_filter", List.of())));
        row.put("enabled", payload.getOrDefault("enabled", row.getOrDefault("enabled", true)));
        row.put(
                "has_auth",
                payload.containsKey("auth_header") || Boolean.TRUE.equals(row.get("has_auth")));
        row.put(
                "metadata",
                row(
                        "health_status",
                        "unknown",
                        "last_tool_count",
                        cachedToolCount(serverId),
                        "last_discovered_at",
                        Instant.now().toString()));
        mcpServers.put(serverId, row);
        mcpRegistry.upsert(toMcpSpec(row));
        mcpToolsCache.remove(serverId);
        mcpProbeCache.remove(serverId);
        audit("mcp.server.saved", serverId, row);
        return row;
    }

    public void deleteMcpServer(String id) {
        mcpServers.remove(id);
        mcpToolsCache.remove(id);
        mcpProbeCache.remove(id);
        mcpRegistry.delete(id);
        persistMcpDiscoveryCache();
        audit("mcp.server.deleted", id, row("id", id));
    }

    public Map<String, Object> providerUpsert(String id, Map<String, Object> payload) {
        String providerId =
                id == null || id.isBlank()
                        ? string(payload, "provider_id", "provider_" + sequence.getAndIncrement())
                        : id;
        Map<String, Object> row =
                new LinkedHashMap<>(
                        providers.getOrDefault(providerId, Map.of("provider_id", providerId)));
        row.putAll(payload);
        row.put("provider_id", providerId);
        row.putIfAbsent("display_name", providerId);
        row.putIfAbsent("provider_type", "openai-compatible");
        row.putIfAbsent("status", "active");
        providers.put(providerId, row);
        providerRegistry.upsert(toProviderSpec(row));
        audit("model.provider.saved", providerId, row);
        return row;
    }

    public Map<String, Object> modelUpsert(String id, Map<String, Object> payload) {
        String modelId =
                id == null || id.isBlank()
                        ? string(payload, "model_id", "model_" + sequence.getAndIncrement())
                        : id;
        Map<String, Object> row =
                new LinkedHashMap<>(modelRows.getOrDefault(modelId, Map.of("model_id", modelId)));
        row.putAll(payload);
        row.put("model_id", modelId);
        row.putIfAbsent("display_name", modelId);
        row.putIfAbsent("provider_id", "openai-compatible");
        row.putIfAbsent("model_name", modelId);
        row.putIfAbsent("model_kind", "chat");
        row.putIfAbsent("provider_call_type", "generate");
        row.put("kind", string(row, "model_kind", string(row, "kind", "chat")));
        row.putIfAbsent("status", "active");
        modelRows.put(modelId, row);
        modelRegistry.upsert(toModelSpec(row));
        audit("model.saved", modelId, row);
        return row;
    }

    public void deleteProvider(String providerId) {
        providers.remove(providerId);
        providerRegistry.delete(providerId);
        audit("model.provider.deleted", providerId, row("provider_id", providerId));
    }

    public void deleteModel(String modelId) {
        modelRows.remove(modelId);
        modelRegistry.delete(modelId);
        slotBindings.values().removeIf(row -> modelId.equals(String.valueOf(row.get("model_id"))));
        aliases.values().removeIf(row -> modelId.equals(String.valueOf(row.get("model_id"))));
        persistModelSlots();
        audit("model.deleted", modelId, row("model_id", modelId));
    }

    public Map<String, Object> newSession(Map<String, Object> payload, String orgId) {
        return workspaceSessionStore.create(payload, orgId);
    }

    public Map<String, Object> createRun(String agentId, String query, String userId) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Map<String, Object> run =
                row(
                        "run_id",
                        runId,
                        "agent_id",
                        agentId,
                        "query",
                        query,
                        "status",
                        "running",
                        "user_id",
                        userId,
                        "trace_id",
                        "trace_" + runId,
                        "created_at",
                        now.toString(),
                        "started_at",
                        now.toString(),
                        "finished_at",
                        "",
                        "spec_key",
                        "agentscope_runtime");
        List<Map<String, Object>> steps =
                List.of(
                        row(
                                "step_id",
                                "receive",
                                "step_type",
                                "receive_input",
                                "status",
                                "succeeded",
                                "duration_ms",
                                1),
                        row(
                                "step_id",
                                "respond",
                                "step_type",
                                "agentscope_runtime",
                                "status",
                                "running",
                                "duration_ms",
                                0));
        List<Map<String, Object>> events =
                List.of(event(runId, "run.started", row("stage", "agentscope_runtime")));
        runs.put(runId, run);
        runSteps.put(runId, new ArrayList<>(steps));
        runEvents.put(runId, new ArrayList<>(events));
        persistRun(run);
        steps.forEach(step -> persistRunStep(runId, step));
        events.forEach(this::persistRunEvent);
        return run;
    }

    public Map<String, Object> finishRun(String runId, String answer) {
        Map<String, Object> output =
                row(
                        "result",
                        row(
                                "answer",
                                answer,
                                "text",
                                answer,
                                "route",
                                "agentscope",
                                "citations",
                                List.of()));
        Map<String, Object> run =
                new LinkedHashMap<>(runs.getOrDefault(runId, row("run_id", runId)));
        run.put("status", "succeeded");
        run.put("finished_at", Instant.now().toString());
        run.put("output_ref", output);
        runs.put(runId, run);
        persistRun(run);
        markStep(runId, "respond", "succeeded", null);
        appendRunEvent(runId, "run.succeeded", output);
        return run;
    }

    public Map<String, Object> failRun(String runId, Throwable error) {
        String message = error == null ? "unknown error" : error.getMessage();
        Map<String, Object> run =
                new LinkedHashMap<>(runs.getOrDefault(runId, row("run_id", runId)));
        run.put("status", "failed");
        run.put("finished_at", Instant.now().toString());
        run.put("error", row("message", message == null || message.isBlank() ? "执行失败" : message));
        runs.put(runId, run);
        persistRun(run);
        markStep(runId, "respond", "failed", message);
        appendRunEvent(runId, "run.failed", row("error", message));
        return run;
    }

    private void markStep(String runId, String stepId, String status, String summary) {
        List<Map<String, Object>> rows = runSteps.get(runId);
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (stepId.equals(row.get("step_id"))) {
                row.put("status", status);
                row.put("duration_ms", number(row.get("duration_ms"), 0));
                if (summary != null && !summary.isBlank()) {
                    row.put("summary", summary);
                }
                persistRunStep(runId, row);
            }
        }
    }

    public Map<String, Object> attach(String sessionId, String filename, String orgId) {
        String id = "att_" + sequence.getAndIncrement();
        Map<String, Object> item =
                row(
                        "attachment_id",
                        id,
                        "id",
                        id,
                        "session_id",
                        sessionId,
                        "filename",
                        filename,
                        "file_name",
                        filename,
                        "org_id",
                        orgId,
                        "status",
                        "ready",
                        "parse_status",
                        "parsed",
                        "created_at",
                        Instant.now().toString());
        attachments.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(item);
        attachmentsById.put(id, item);
        return item;
    }

    public Map<String, Object> attachDocument(
            String sessionId, Map<String, Object> document, String orgId) {
        String id = "att_" + sequence.getAndIncrement();
        String parseStatus = String.valueOf(document.getOrDefault("parse_status", "failed"));
        Map<String, Object> item =
                row(
                        "attachment_id",
                        id,
                        "id",
                        id,
                        "session_id",
                        sessionId,
                        "doc_id",
                        document.get("doc_id"),
                        "version_id",
                        document.getOrDefault("version_id", "v1"),
                        "filename",
                        document.get("filename"),
                        "file_name",
                        document.get("filename"),
                        "org_id",
                        orgId,
                        "status",
                        "parsed".equals(parseStatus) ? "ready" : "error",
                        "parse_status",
                        parseStatus,
                        "parse_message",
                        document.getOrDefault("parse_message", ""),
                        "scope",
                        "session",
                        "created_at",
                        Instant.now().toString());
        attachments.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(item);
        attachmentsById.put(id, item);
        return item;
    }

    public Map<String, Object> document(String filename, String domain, String orgId) {
        String docId = "doc_" + sequence.getAndIncrement();
        Map<String, Object> doc =
                row(
                        "doc_id",
                        docId,
                        "id",
                        docId,
                        "version_id",
                        "v1",
                        "filename",
                        filename,
                        "title",
                        filename,
                        "domain",
                        domain,
                        "org_id",
                        orgId,
                        "doc_type",
                        "file",
                        "status",
                        "parsed",
                        "parse_status",
                        "parsed",
                        "block_count",
                        1,
                        "raw_available",
                        false,
                        "preview_available",
                        false,
                        "created_at",
                        Instant.now().toString());
        knowledgeDocs.put(docId, doc);
        return doc;
    }

    public Map<String, Object> memory(Map<String, Object> payload) {
        String id = String.valueOf(sequence.getAndIncrement());
        Map<String, Object> item = new LinkedHashMap<>(payload);
        item.put("id", Long.parseLong(id));
        item.putIfAbsent("domain", "platform");
        item.putIfAbsent("scope", "user");
        item.putIfAbsent("memory_type", "preference");
        item.putIfAbsent("status", "active");
        item.putIfAbsent("confidence", 1);
        item.putIfAbsent("created_at", Instant.now().toString());
        item.put("updated_at", Instant.now().toString());
        memories.put(id, item);
        persistMemory(item);
        return item;
    }

    public List<Map<String, Object>> sessions(String domain) {
        return sessions(domain, "platform_knowledge_agent");
    }

    public List<Map<String, Object>> sessions(String domain, String agentId) {
        return workspaceSessionStore.list(agentId, "platform").stream()
                .filter(
                        row ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(row.get("domain")))
                .toList();
    }

    public Map<String, Object> session(String id) {
        return session(id, "platform_knowledge_agent");
    }

    public Map<String, Object> session(String id, String agentId) {
        return workspaceSessionStore.get(agentId, id, "platform");
    }

    public void appendSessionMessage(
            String agentId, String sessionId, String userId, String role, String content) {
        workspaceSessionStore.appendMessage(agentId, sessionId, userId, role, content);
    }

    public void deleteSession(String sessionId) {
        deleteSession(sessionId, "platform_knowledge_agent");
    }

    public void deleteSession(String sessionId, String agentId) {
        workspaceSessionStore.delete(agentId, sessionId);
        attachments.remove(sessionId);
    }

    public List<Map<String, Object>> attachments(String sessionId) {
        return attachments.getOrDefault(sessionId, List.of());
    }

    public Map<String, Object> attachment(String id) {
        return attachmentsById.getOrDefault(id, Map.of("attachment_id", id, "status", "ready"));
    }

    public void deleteAttachment(String id) {
        Map<String, Object> item = attachmentsById.remove(id);
        if (item == null) {
            return;
        }
        String sessionId = String.valueOf(item.get("session_id"));
        attachments.computeIfPresent(
                sessionId,
                (ignored, rows) -> {
                    rows.removeIf(row -> id.equals(row.get("attachment_id")));
                    return rows;
                });
    }

    public List<Map<String, Object>> runs(String agentId, String status, int limit) {
        return runs.values().stream()
                .filter(
                        row ->
                                agentId == null
                                        || agentId.isBlank()
                                        || agentId.equals(row.get("agent_id")))
                .filter(
                        row ->
                                status == null
                                        || status.isBlank()
                                        || status.equals(row.get("status")))
                .sorted(
                        Comparator.comparing(
                                        (Map<String, Object> row) ->
                                                String.valueOf(row.get("created_at")))
                                .reversed())
                .limit(limit)
                .toList();
    }

    public Map<String, Object> run(String runId) {
        return runs.getOrDefault(runId, Map.of("run_id", runId, "status", "unknown"));
    }

    public List<Map<String, Object>> runSteps(String runId) {
        return runSteps.getOrDefault(runId, List.of());
    }

    public List<Map<String, Object>> runEvents(String runId) {
        return runEvents.getOrDefault(runId, List.of());
    }

    public void appendRunEventFromEnvelope(String runId, AgentEventEnvelope event) {
        if (runId == null || runId.isBlank() || event == null) {
            return;
        }
        Map<String, Object> payload =
                event.payload() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(event.payload());
        payload.putIfAbsent("runtime", true);
        payload.putIfAbsent("step", safeEventStep(String.valueOf(event.type())));
        payload.putIfAbsent("type", event.type());
        payload.putIfAbsent("agent_id", event.source());
        payload.putIfAbsent("source", event.source());
        normalizeToolSkillPayload(payload);
        if (isWaitingEvent(event.type(), payload)) {
            String waitingId =
                    string(
                            payload,
                            "waiting_id",
                            "wait_" + UUID.randomUUID().toString().replace("-", ""));
            payload.put("waiting_id", waitingId);
            payload.put("run_id", runId);
            payload.putIfAbsent("status", "waiting");
            payload.putIfAbsent("created_at", Instant.now().toString());
            payload.put("updated_at", Instant.now().toString());
            waitings.put(runId, payload);
            updateRunStatus(runId, "waiting");
            persistWaiting(payload);
        }
        appendRunEvent(
                runId,
                event.type() == null || event.type().isBlank()
                        ? "agent_event"
                        : event.type().toLowerCase(),
                payload);
    }

    private static boolean isWaitingEvent(String eventType, Map<String, Object> payload) {
        String type = eventType == null ? "" : eventType.toLowerCase();
        String status = string(payload, "status", "").toLowerCase();
        return type.contains("waiting")
                || type.contains("wait_user")
                || "waiting".equals(status)
                || "wait_user_input".equals(status);
    }

    public void appendRunEvent(String runId, String eventType, Map<String, Object> payload) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        Map<String, Object> normalized =
                payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        normalized.putIfAbsent("runtime", true);
        Map<String, Object> item = event(runId, eventType, normalized);
        runEvents.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(item);
        persistRunEvent(item);
    }

    public Map<String, Object> waiting(String runId) {
        return waitings.get(runId);
    }

    public Map<String, Object> createWaiting(String runId, Map<String, Object> payload) {
        Map<String, Object> input = payload == null ? Map.of() : payload;
        String waitingId =
                string(input, "waiting_id", "wait_" + UUID.randomUUID().toString().replace("-", ""));
        Map<String, Object> item = new LinkedHashMap<>(input);
        item.put("waiting_id", waitingId);
        item.put("run_id", runId);
        item.putIfAbsent("status", "waiting");
        item.putIfAbsent("created_at", Instant.now().toString());
        item.put("updated_at", Instant.now().toString());
        waitings.put(runId, item);
        updateRunStatus(runId, "waiting");
        persistWaiting(item);
        appendRunEvent(runId, "run.waiting", item);
        return item;
    }

    public Map<String, Object> resumeWaiting(
            String runId, String waitingId, Map<String, Object> payload) {
        return updateWaiting(runId, waitingId, "resumed", payload);
    }

    public Map<String, Object> rejectWaiting(
            String runId, String waitingId, Map<String, Object> payload) {
        return updateWaiting(runId, waitingId, "rejected", payload);
    }

    private Map<String, Object> updateWaiting(
            String runId, String waitingId, String status, Map<String, Object> payload) {
        Map<String, Object> existing = waitings.get(runId);
        if (existing == null
                || !waitingId.equals(String.valueOf(existing.getOrDefault("waiting_id", "")))) {
            return row("run_id", runId, "waiting_id", waitingId, "status", "not_found");
        }
        Map<String, Object> item = new LinkedHashMap<>(existing);
        if (payload != null) {
            item.putAll(payload);
        }
        item.put("run_id", runId);
        item.put("waiting_id", waitingId);
        item.put("status", status);
        item.put("updated_at", Instant.now().toString());
        waitings.put(runId, item);
        updateRunStatus(runId, "resumed".equals(status) ? "running" : "rejected");
        persistWaiting(item);
        appendRunEvent(runId, "run.waiting." + status, item);
        return item;
    }

    private void updateRunStatus(String runId, String status) {
        Map<String, Object> existing = runs.get(runId);
        if (existing == null) {
            return;
        }
        Map<String, Object> updated = new LinkedHashMap<>(existing);
        updated.put("status", status);
        if ("rejected".equals(status)) {
            updated.put("finished_at", Instant.now().toString());
        } else if ("running".equals(status)) {
            updated.put("finished_at", "");
        }
        runs.put(runId, updated);
        persistRun(updated);
    }

    public List<Map<String, Object>> providers() {
        return sorted(providers.values(), "provider_id");
    }

    public Map<String, Object> provider(String providerId) {
        return providers.getOrDefault(providerId, Map.of());
    }

    public List<Map<String, Object>> modelRows() {
        return sorted(modelRows.values(), "model_id");
    }

    public Map<String, Object> modelRow(String modelId) {
        return modelRows.getOrDefault(modelId, Map.of());
    }

    public List<Map<String, Object>> slots() {
        return List.of(
                row(
                        "slot_key",
                        "qa",
                        "display_name",
                        "问答模型",
                        "model_kind",
                        "chat",
                        "provider_call_type",
                        "generate",
                        "required_capabilities",
                        List.of(),
                        "is_custom",
                        false),
                row(
                        "slot_key",
                        "embedding",
                        "display_name",
                        "向量模型",
                        "model_kind",
                        "embedding",
                        "provider_call_type",
                        "embed",
                        "required_capabilities",
                        List.of(),
                        "is_custom",
                        false),
                row(
                        "slot_key",
                        "vlm",
                        "display_name",
                        "视觉理解模型",
                        "model_kind",
                        "chat",
                        "provider_call_type",
                        "generate",
                        "required_capabilities",
                        List.of("vision"),
                        "is_custom",
                        false));
    }

    public Map<String, Object> bindSlot(String slotKey, Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("slot_key", slotKey);
        row.putIfAbsent("scope", "platform");
        row.putIfAbsent("org_id", "");
        slotBindings.put(slotKey, row);
        audit("model.slot.bound", slotKey, row);
        persistModelSlots();
        return row;
    }

    public void clearSlot(String slotKey) {
        slotBindings.remove(slotKey);
        audit("model.slot.cleared", slotKey, row("slot_key", slotKey));
        persistModelSlots();
    }

    public List<Map<String, Object>> slotBindings() {
        return sorted(slotBindings.values(), "slot_key");
    }

    public Map<String, Object> alias(Map<String, Object> payload) {
        String id = String.valueOf(sequence.getAndIncrement());
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("id", id);
        aliases.put(id, row);
        audit("model.alias.saved", id, row);
        persistModelSlots();
        return row;
    }

    public List<Map<String, Object>> aliases() {
        return sorted(aliases.values(), "alias_name");
    }

    public void deleteAlias(String id) {
        Map<String, Object> removed = aliases.remove(id);
        audit("model.alias.deleted", id, removed == null ? row("id", id) : removed);
        persistModelSlots();
    }

    public List<Map<String, Object>> docs(String domain) {
        return knowledgeDocs.values().stream()
                .filter(
                        row ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(row.get("domain")))
                .toList();
    }

    public List<Map<String, Object>> collections(String domain) {
        return collections.values().stream()
                .filter(
                        row ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(row.get("domain")))
                .toList();
    }

    public Map<String, Object> collection(Map<String, Object> payload, String orgId) {
        String id = "col_" + sequence.getAndIncrement();
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("collection_id", id);
        row.put("org_id", orgId);
        row.putIfAbsent("items", new ArrayList<>());
        row.putIfAbsent("item_count", 0);
        collections.put(id, row);
        return row;
    }

    public List<Map<String, Object>> memories(String domain, String status) {
        if (isSqliteEnabled()) {
            loadMemories();
        }
        return memories.values().stream()
                .filter(
                        row ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(row.get("domain")))
                .filter(
                        row ->
                                status == null
                                        || status.isBlank()
                                        || status.equals(row.get("status")))
                .toList();
    }

    public long activeMemoryCount(String domain) {
        return memories(domain == null || domain.isBlank() ? "platform" : domain, "active").stream()
                .filter(row -> !string(row, "content", "").isBlank())
                .count();
    }

    public List<Map<String, Object>> dailyMemories(String agentId) {
        return dailyMemories(agentId, "platform_platform_admin");
    }

    public List<Map<String, Object>> dailyMemories(String agentId, String userKey) {
        String resolvedAgentId = agentId == null || agentId.isBlank() ? "researcher" : agentId;
        AgentDefinition definition = agentRegistry.findPublished(resolvedAgentId).orElse(null);
        if (definition == null) {
            return List.of();
        }
        Path workspace = agentUserWorkspace(definition, userKey);
        String scopedUser = memoryUserKey(userKey);
        Path dir = workspace.resolve("memory");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.reverseOrder())
                    .map(path -> dailyMemoryRow(definition, workspace, path, scopedUser))
                    .toList();
        } catch (Exception e) {
            log.warn("List daily memories for {} failed: {}", resolvedAgentId, e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> agentMemoryOverview(String agentId) {
        return agentMemoryOverview(agentId, "platform_platform_admin");
    }

    public Map<String, Object> agentMemoryOverview(String agentId, String userKey) {
        String resolvedAgentId = agentId == null || agentId.isBlank() ? "researcher" : agentId;
        AgentDefinition definition = agentRegistry.findPublished(resolvedAgentId).orElse(null);
        if (definition == null) {
            return row(
                    "agent_id",
                    resolvedAgentId,
                    "memory_md",
                    "",
                    "memory_path",
                    "",
                    "memory_scope",
                    "agent_user",
                    "storage",
                    "filesystem",
                    "managed_by",
                    "agentscope",
                    "platform_projection",
                    true,
                    "daily",
                    List.of());
        }
        Path workspace = agentUserWorkspace(definition, userKey);
        Path memoryFile = workspace.resolve("MEMORY.md");
        String memoryMd = "";
        try {
            memoryMd = Files.exists(memoryFile) ? Files.readString(memoryFile) : "";
        } catch (Exception e) {
            log.warn("Read MEMORY.md for {} failed: {}", resolvedAgentId, e.getMessage());
        }
        return row(
                "agent_id",
                definition.agentId(),
                "memory_md",
                memoryMd,
                "memory_path",
                definition.workspace().relativize(memoryFile).toString().replace('\\', '/'),
                "memory_scope",
                "agent_user",
                "storage",
                "filesystem",
                "managed_by",
                "agentscope",
                "platform_projection",
                true,
                "daily",
                dailyMemories(definition.agentId(), userKey));
    }

    private Path agentUserWorkspace(AgentDefinition definition, String userKey) {
        return definition.workspace().resolve(memoryUserKey(userKey));
    }

    private String memoryUserKey(String userKey) {
        String cleaned = userKey == null ? "" : userKey.replaceAll("[^A-Za-z0-9._-]", "_").strip();
        return cleaned.isBlank() ? "platform_platform_admin" : cleaned;
    }

    public boolean deleteAgentWorkspaceMemoryEntry(
            String agentId, String userKey, String content) {
        AgentDefinition definition =
                agentRegistry
                        .findPublished(agentId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentId));
        String target = content == null ? "" : content.strip();
        if (target.isBlank()) {
            throw new IllegalArgumentException("Memory entry content is required.");
        }
        Path memoryFile = agentUserWorkspace(definition, userKey).resolve("MEMORY.md");
        if (!Files.isRegularFile(memoryFile)) {
            return false;
        }
        try {
            boolean inManagedBlock = false;
            boolean removed = false;
            StringBuilder updated = new StringBuilder();
            for (String line : Files.readString(memoryFile).split("\\R", -1)) {
                String trimmed = line.strip();
                if (MEMORY_BLOCK_START.equals(trimmed)) {
                    inManagedBlock = true;
                }
                boolean matchesEntry = trimmed.equals("- " + target);
                if (!inManagedBlock && matchesEntry && !removed) {
                    removed = true;
                    continue;
                }
                updated.append(line).append("\n");
                if (MEMORY_BLOCK_END.equals(trimmed)) {
                    inManagedBlock = false;
                }
            }
            if (removed) {
                Files.writeString(memoryFile, updated.toString().replaceFirst("\\n+$", "\n"));
            }
            return removed;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update agent workspace memory.", e);
        }
    }

    private Map<String, Object> dailyMemoryRow(
            AgentDefinition definition, Path workspace, Path path, String scopedUser) {
        try {
            String content = Files.readString(path);
            return row(
                    "agent_id",
                    definition.agentId(),
                    "date",
                    path.getFileName().toString().replaceFirst("\\.md$", ""),
                    "path",
                    definition.workspace().relativize(path).toString().replace('\\', '/'),
                    "line_count",
                    content.isBlank() ? 0 : content.lines().count(),
                    "source",
                    "agentscope_daily_ledger",
                    "storage",
                    "filesystem",
                    "scoped_user",
                    scopedUser,
                    "content",
                    content);
        } catch (Exception e) {
            return row(
                    "agent_id",
                    definition.agentId(),
                    "date",
                    path.getFileName().toString().replaceFirst("\\.md$", ""),
                    "path",
                    definition.workspace().relativize(path).toString().replace('\\', '/'),
                    "line_count",
                    0,
                    "source",
                    "agentscope_daily_ledger",
                    "storage",
                    "filesystem",
                    "scoped_user",
                    scopedUser,
                    "content",
                    "",
                    "error",
                    e.getMessage());
        }
    }

    public Map<String, Object> updateMemory(String id, Map<String, Object> payload) {
        Map<String, Object> item =
                new LinkedHashMap<>(memories.getOrDefault(id, Map.of("id", Long.parseLong(id))));
        item.putAll(payload);
        item.putIfAbsent("domain", "platform");
        item.putIfAbsent("scope", "user");
        item.putIfAbsent("memory_type", "preference");
        item.putIfAbsent("status", "active");
        item.putIfAbsent("confidence", 1);
        item.putIfAbsent("created_at", Instant.now().toString());
        item.put("updated_at", Instant.now().toString());
        memories.put(id, item);
        persistMemory(item);
        return item;
    }

    public Map<String, Object> confirmMemory(String id, Map<String, Object> payload) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", "active");
        patch.put("confirmed_at", Instant.now().toString());
        patch.put("confirmed_by", string(payload, "actor", "platform_admin"));
        String updateContent = string(payload, "update_content", "");
        if (!updateContent.isBlank()) {
            patch.put("content", updateContent);
        }
        String comment = string(payload, "comment", "");
        if (!comment.isBlank()) {
            patch.put("confirm_comment", comment);
        }
        Map<String, Object> item = updateMemory(id, patch);
        audit("memory_confirm", id, row("memory_id", id, "comment", comment));
        return item;
    }

    public Map<String, Object> rejectMemory(String id, Map<String, Object> payload) {
        String comment = string(payload, "comment", string(payload, "reason", ""));
        Map<String, Object> item =
                updateMemory(
                        id,
                        row(
                                "status",
                                "rejected",
                                "rejected_at",
                                Instant.now().toString(),
                                "rejected_by",
                                string(payload, "actor", "platform_admin"),
                                "reject_reason",
                                comment));
        audit("memory_reject", id, row("memory_id", id, "reason", comment));
        return item;
    }

    public Map<String, Object> mergeMemory(String id, Map<String, Object> payload) {
        String targetId = string(payload, "target_memory_id", string(payload, "target_id", ""));
        if (targetId.isBlank() || targetId.equals(id)) {
            throw new IllegalArgumentException("target_memory_id is required and must differ.");
        }
        Map<String, Object> source =
                new LinkedHashMap<>(memories.getOrDefault(id, Map.of("id", Long.parseLong(id))));
        Map<String, Object> target =
                new LinkedHashMap<>(
                        memories.getOrDefault(targetId, Map.of("id", Long.parseLong(targetId))));
        if (string(target, "content", "").isBlank()) {
            throw new IllegalArgumentException("Target memory not found: " + targetId);
        }
        String updateContent = string(payload, "update_content", "");
        if (!updateContent.isBlank()) {
            target.put("content", updateContent);
        }
        List<Object> mergedFrom = new ArrayList<>();
        Object existingMergedFrom = target.get("merged_from");
        if (existingMergedFrom instanceof List<?> list) {
            mergedFrom.addAll(list);
        }
        if (!mergedFrom.contains(id)) {
            mergedFrom.add(id);
        }
        target.put("merged_from", mergedFrom);
        target.put("updated_at", Instant.now().toString());
        memories.put(targetId, target);
        persistMemory(target);

        String comment = string(payload, "comment", "");
        source.put("status", "merged");
        source.put("merged_into", targetId);
        source.put("merged_at", Instant.now().toString());
        source.put("merged_by", string(payload, "actor", "platform_admin"));
        if (!comment.isBlank()) {
            source.put("merge_comment", comment);
        }
        source.put("updated_at", Instant.now().toString());
        memories.put(id, source);
        persistMemory(source);
        audit(
                "memory_merge",
                id,
                row("memory_id", id, "target_memory_id", targetId, "comment", comment));
        return source;
    }

    public void deleteMemory(String id) {
        deleteMemory(id, "platform_platform_admin");
    }

    /**
     * Deletes a platform long-term memory and immediately refreshes every published
     * agent's managed MEMORY.md block for the active user. This keeps the runtime
     * prompt source of truth aligned with the Memory Management UI.
     */
    public void deleteMemory(String id, String userKey) {
        memories.remove(id);
        if (isSqliteEnabled()) {
            try (Connection connection = storage.connection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "DELETE FROM " + SQLITE_MEMORY_TABLE + " WHERE memory_id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            } catch (Exception e) {
                log.warn("Delete memory {} from sqlite failed: {}", id, e.getMessage());
            }
        }
        for (AgentDefinition definition : agentRegistry.allPublished()) {
            projectMemoriesToAgentWorkspace(definition, userKey);
        }
    }

    public void projectMemoriesToAgentWorkspace(AgentDefinition definition) {
        projectMemoriesToAgentWorkspace(definition, "platform_platform_admin");
    }

    public void projectMemoriesToAgentWorkspace(AgentDefinition definition, String userKey) {
        List<Map<String, Object>> active =
                memories("platform", "active").stream()
                        .filter(row -> !string(row, "content", "").isBlank())
                        .toList();
        StringBuilder block = new StringBuilder();
        block.append(MEMORY_BLOCK_START).append("\n");
        block.append("# Platform Managed Memory\n\n");
        for (Map<String, Object> row : active) {
            String type = string(row, "memory_type", "memory");
            String content = string(row, "content", "").replace("\r", "").strip();
            if (!content.isBlank()) {
                block.append("- [")
                        .append(type)
                        .append("] ")
                        .append(content.replace("\n", " "))
                        .append("\n");
            }
        }
        block.append(MEMORY_BLOCK_END).append("\n");
        Path memoryFile = agentUserWorkspace(definition, userKey).resolve("MEMORY.md");
        try {
            Files.createDirectories(memoryFile.getParent());
            String existing = Files.exists(memoryFile) ? Files.readString(memoryFile) : "";
            Files.writeString(memoryFile, replaceManagedMemoryBlock(existing, block.toString()));
        } catch (Exception e) {
            log.warn("Project platform memories to {} failed: {}", memoryFile, e.getMessage());
        }
    }

    public void importAgentWorkspaceMemories(AgentDefinition definition) {
        importAgentWorkspaceMemories(definition, "platform_platform_admin", "", "");
    }

    public void importAgentWorkspaceMemories(AgentDefinition definition, String userKey) {
        importAgentWorkspaceMemories(definition, userKey, "", "");
    }

    public void importAgentWorkspaceMemories(
            AgentDefinition definition,
            String userKey,
            String sourceSessionId,
            String sourceRunId) {
        String scopedUser = memoryUserKey(userKey);
        String resolvedRunId =
                sourceRunId == null || sourceRunId.isBlank()
                        ? UUID.randomUUID().toString()
                        : sourceRunId;
        Path memoryFile = agentUserWorkspace(definition, scopedUser).resolve("MEMORY.md");
        if (!Files.exists(memoryFile)) {
            return;
        }
        try {
            String text = removeManagedMemoryBlock(Files.readString(memoryFile));
            Set<String> existing = new LinkedHashSet<>();
            for (Map<String, Object> row : memories("platform", null)) {
                existing.add(string(row, "content", "").strip());
            }
            for (String line : text.split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("- ")) {
                    continue;
                }
                String content = trimmed.substring(2).strip();
                if (content.isBlank() || existing.contains(content)) {
                    continue;
                }
                memory(
                        row(
                                "domain",
                                "platform",
                                "scope",
                                "user",
                                "memory_type",
                                "fact",
                                "status",
                                "pending_confirm",
                                "confidence",
                                0.7,
                                "source",
                                "agentscope_memory_save",
                                "agent_id",
                                definition.agentId(),
                                "source_agent_id",
                                definition.agentId(),
                                "source_user_id",
                                scopedUser,
                                "source_session_id",
                                sourceSessionId,
                                "source_run_id",
                                resolvedRunId,
                                "content",
                                content));
                existing.add(content);
            }
        } catch (Exception e) {
            log.warn(
                    "Import agent workspace memories from {} failed: {}",
                    memoryFile,
                    e.getMessage());
        }
    }

    public List<Map<String, Object>> audit() {
        if (isSqliteEnabled()) {
            loadAudit();
        }
        return List.copyOf(audit);
    }

    public void appendAuditEvent(String event, String targetId, Map<String, Object> payload) {
        if (event == null || event.isBlank() || payload == null) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("recorded_at", Instant.now().toString());
        audit(event, targetId, row);
    }

    public Map<String, Object> probe(Map<String, Object> payload) {
        String endpoint = string(payload, "endpoint", "");
        return row(
                "ok",
                true,
                "stage",
                "compat",
                "server_name",
                endpoint.isBlank() ? "compat-mcp" : endpoint,
                "server_version",
                "compat",
                "tool_count",
                2,
                "tools",
                List.of("echo", "health"));
    }

    public Map<String, Object> probeMcpServer(String id) {
        Map<String, Object> server = mcpServers.get(id);
        if (server == null) {
            return row(
                    "probe",
                    row("ok", false, "stage", "lookup", "error", "MCP server not found: " + id));
        }
        Map<String, Object> probe = probe(server);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
                "health_status", Boolean.TRUE.equals(probe.get("ok")) ? "healthy" : "unhealthy");
        metadata.put("last_tool_count", probe.getOrDefault("tool_count", 0));
        metadata.put("server_name", probe.getOrDefault("server_name", ""));
        metadata.put("server_version", probe.getOrDefault("server_version", ""));
        metadata.put("last_discovered_at", Instant.now().toString());
        if (!Boolean.TRUE.equals(probe.get("ok"))) {
            metadata.put(
                    "last_error",
                    probe.getOrDefault("error", probe.getOrDefault("message", "unknown")));
        }
        server.put("metadata", metadata);
        mcpServers.put(id, server);
        recordProbeRun("mcp", id, probe);
        if (Boolean.TRUE.equals(probe.get("ok"))) {
            log.info(
                    "Manual MCP probe for {} succeeded with {} tools.",
                    id,
                    probe.getOrDefault("tool_count", 0));
        } else {
            log.warn(
                    "Manual MCP probe for {} failed: {}",
                    id,
                    probe.getOrDefault("error", probe.getOrDefault("message", "unknown")));
        }
        return row("probe", probe, "server", server);
    }

    public Map<String, Object> updateMcpProbe(String id, Map<String, Object> probe) {
        Map<String, Object> server = mcpServers.get(id);
        if (server == null) {
            return row(
                    "probe",
                    row("ok", false, "stage", "lookup", "error", "MCP server not found: " + id));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
                "health_status", Boolean.TRUE.equals(probe.get("ok")) ? "healthy" : "unhealthy");
        metadata.put("last_tool_count", probe.getOrDefault("tool_count", 0));
        metadata.put("server_name", probe.getOrDefault("server_name", ""));
        metadata.put("server_version", probe.getOrDefault("server_version", ""));
        metadata.put("last_discovered_at", Instant.now().toString());
        if (!Boolean.TRUE.equals(probe.get("ok"))) {
            metadata.put(
                    "last_error",
                    probe.getOrDefault("error", probe.getOrDefault("message", "unknown")));
        }
        server.put("metadata", metadata);
        mcpServers.put(id, server);
        recordProbeRun("mcp", id, probe);
        return row("probe", probe, "server", server);
    }

    public List<Map<String, Object>> mcpTools(Map<String, Object> server) {
        String serverId = String.valueOf(server.getOrDefault("id", "0"));
        List<Map<String, Object>> cached = mcpToolsCache.get(serverId);
        if (cached != null) {
            return cached.stream().map(Map::copyOf).toList();
        }
        List<Map<String, Object>> discovered = mcpToolDiscoveryService.discover(server);
        mcpToolsCache.put(serverId, discovered.stream().map(Map::copyOf).toList());
        mcpProbeCache.put(
                serverId,
                Map.of(
                        "ok",
                        !discovered.isEmpty(),
                        "stage",
                        "tools/list",
                        "tool_count",
                        discovered.size()));
        persistMcpDiscoveryCache();
        return discovered;
    }

    public Map<String, Object> syncMcpServerDiscovery(String serverId) {
        Map<String, Object> server = mcpServers.get(serverId);
        if (server == null) {
            return Map.of(
                    "ok", false, "stage", "lookup", "error", "MCP server not found: " + serverId);
        }
        refreshMcpDiscoveryForServer(server);
        Map<String, Object> result =
                mcpProbeCache.getOrDefault(serverId, Map.of("ok", false, "stage", "sync"));
        if (Boolean.TRUE.equals(result.get("ok"))) {
            log.info(
                    "Manual MCP discovery sync for {} completed with {} tools.",
                    serverId,
                    result.getOrDefault("tool_count", 0));
        } else {
            log.warn(
                    "Manual MCP discovery sync for {} failed: {}",
                    serverId,
                    result.getOrDefault("error", result.getOrDefault("message", "unknown")));
        }
        return result;
    }

    private void refreshMcpDiscoveryForServer(Map<String, Object> server) {
        String serverId = string(server, "id", "0");
        try {
            Map<String, Object> probe = mcpToolDiscoveryService.probe(server);
            List<Map<String, Object>> tools = mcpToolDiscoveryService.discover(server);
            List<Map<String, Object>> normalizedTools = tools.stream().map(Map::copyOf).toList();
            mcpToolsCache.put(serverId, normalizedTools);
            mcpProbeCache.put(serverId, probe);
            recordProbeRun("mcp", serverId, probe);
            Map<String, Object> metadata = metadataFromProbe(probe, normalizedTools.size());
            server.put("metadata", metadata);
            if (Boolean.TRUE.equals(probe.get("ok"))) {
                log.debug(
                        "Refreshed MCP discovery for {} with {} tools.",
                        serverId,
                        normalizedTools.size());
            } else {
                log.warn(
                        "Failed to refresh MCP discovery for {}: {}",
                        serverId,
                        probe.getOrDefault("error", probe.getOrDefault("message", "unknown")));
            }
            mcpServers.put(serverId, new LinkedHashMap<>(server));
            persistMcpDiscoveryCache();
        } catch (Exception e) {
            log.error("Unexpected error during MCP discovery refresh for {}.", serverId, e);
        }
    }

    private Map<String, Object> metadataFromProbe(Map<String, Object> probe, int toolCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
                "health_status", Boolean.TRUE.equals(probe.get("ok")) ? "healthy" : "unhealthy");
        metadata.put("last_tool_count", probe.getOrDefault("tool_count", toolCount));
        metadata.put("server_name", probe.getOrDefault("server_name", ""));
        metadata.put("server_version", probe.getOrDefault("server_version", ""));
        metadata.put("last_discovered_at", Instant.now().toString());
        if (!Boolean.TRUE.equals(probe.get("ok"))) {
            metadata.put("last_error", probe.getOrDefault("error", "unknown"));
        }
        return metadata;
    }

    private void applyCachedDiscoveryMetadata() {
        if (!mcpDiscoveryEnabled) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : mcpServers.entrySet()) {
            String serverId = entry.getKey();
            Map<String, Object> server = entry.getValue();
            Map<String, Object> probe = mcpProbeCache.get(serverId);
            List<Map<String, Object>> tools = mcpToolsCache.getOrDefault(serverId, List.of());
            if (probe == null) {
                continue;
            }
            server.put("metadata", metadataFromProbe(probe, tools.size()));
            mcpServers.put(serverId, server);
        }
    }

    private void loadMcpDiscoveryCache() {
        if (!mcpDiscoveryEnabled) {
            return;
        }
        if (isSqliteEnabled()) {
            loadMcpDiscoveryCacheFromSqlite();
            return;
        }
        Path path = mcpDiscoveryCachePath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, Object> root =
                    objectMapper.readValue(
                            path.toFile(), new TypeReference<Map<String, Object>>() {});
            Object version = root.get("version");
            if (MCP_DISCOVERY_CACHE_VERSION.equals(String.valueOf(version))) {
                Object servers = root.get("servers");
                if (servers instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String serverId = String.valueOf(entry.getKey());
                        if (!(entry.getValue() instanceof Map<?, ?> payload)) {
                            continue;
                        }
                        Object toolNode = payload.get("tools");
                        List<Map<String, Object>> tools =
                                listMapPayload(toolNode).stream()
                                        .map(PlatformCompatibilityState::asStringKeyedMap)
                                        .toList();
                        Object probeNode = payload.get("probe");
                        if (probeNode instanceof Map<?, ?> probeRaw) {
                            mcpProbeCache.put(serverId, asStringKeyedMap(probeRaw));
                        }
                        mcpToolsCache.put(serverId, tools);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Load MCP discovery cache failed: {}", e.getMessage());
        }
    }

    private void persistMcpDiscoveryCache() {
        if (!mcpDiscoveryEnabled) {
            return;
        }
        if (isSqliteEnabled()) {
            persistMcpDiscoveryCacheToSqlite();
            return;
        }
        Path path = mcpDiscoveryCachePath();
        try {
            Files.createDirectories(path.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", MCP_DISCOVERY_CACHE_VERSION);
            Map<String, Object> servers = new LinkedHashMap<>();
            Set<String> serverIds = new LinkedHashSet<>();
            serverIds.addAll(mcpToolsCache.keySet());
            serverIds.addAll(mcpProbeCache.keySet());
            for (String serverId : serverIds) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("probe", mcpProbeCache.getOrDefault(serverId, Map.of()));
                payload.put("tools", mcpToolsCache.getOrDefault(serverId, List.of()));
                servers.put(serverId, payload);
            }
            root.put("servers", servers);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
        } catch (Exception e) {
            log.warn("Persist MCP discovery cache failed: {}", e.getMessage());
        }
    }

    private boolean loadMcpDiscoveryCacheFromSqlite() {
        String sql =
                "SELECT server_id, probe_payload, tools_payload FROM "
                        + SQLITE_MCP_DISCOVERY_TABLE
                        + " ORDER BY server_id";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            boolean loaded = false;
            while (resultSet.next()) {
                String serverId = resultSet.getString("server_id");
                Map<String, Object> probe = mapFromJson(resultSet.getString("probe_payload"));
                List<Map<String, Object>> tools =
                        listMapPayload(
                                objectMapper.readValue(
                                        resultSet.getString("tools_payload"),
                                        new TypeReference<List<Object>>() {}));
                if (!probe.isEmpty()) {
                    mcpProbeCache.put(serverId, probe);
                }
                mcpToolsCache.put(serverId, tools);
                loaded = true;
            }
            return loaded;
        } catch (Exception e) {
            log.warn("Load MCP discovery cache from sqlite failed: {}", e.getMessage());
            return false;
        }
    }

    private void persistMcpDiscoveryCacheToSqlite() {
        String deleteSql = "DELETE FROM " + SQLITE_MCP_DISCOVERY_TABLE;
        String upsertSql =
                "INSERT INTO "
                        + SQLITE_MCP_DISCOVERY_TABLE
                        + " (server_id, probe_payload, tools_payload, updated_at) VALUES (?, ?, ?,"
                        + " ?) ON CONFLICT(server_id) DO UPDATE SET probe_payload ="
                        + " excluded.probe_payload, tools_payload = excluded.tools_payload,"
                        + " updated_at = excluded.updated_at";
        try (Connection connection = storage.connection();
                Statement deleteStatement = connection.createStatement();
                PreparedStatement upsertStatement = connection.prepareStatement(upsertSql)) {
            connection.setAutoCommit(false);
            try {
                deleteStatement.executeUpdate(deleteSql);
                String now = Instant.now().toString();
                Set<String> serverIds = new LinkedHashSet<>();
                serverIds.addAll(mcpToolsCache.keySet());
                serverIds.addAll(mcpProbeCache.keySet());
                for (String serverId : serverIds) {
                    upsertStatement.setString(1, serverId);
                    upsertStatement.setString(
                            2,
                            objectMapper.writeValueAsString(
                                    mcpProbeCache.getOrDefault(serverId, Map.of())));
                    upsertStatement.setString(
                            3,
                            objectMapper.writeValueAsString(
                                    mcpToolsCache.getOrDefault(serverId, List.of())));
                    upsertStatement.setString(4, now);
                    upsertStatement.addBatch();
                }
                upsertStatement.executeBatch();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.warn("Persist MCP discovery cache to sqlite failed: {}", e.getMessage());
        }
    }

    private static List<Map<String, Object>> listMapPayload(Object payload) {
        if (!(payload instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add(asStringKeyedMap(map));
            }
        }
        return rows;
    }

    private static Map<String, Object> asStringKeyedMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, v) -> map.put(String.valueOf(key), v));
        return map;
    }

    private int cachedToolCount(String serverId) {
        return mcpToolsCache.getOrDefault(serverId, List.of()).size();
    }

    public List<Map<String, Object>> enrichTools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> row = new LinkedHashMap<>(tool);
            String toolId = string(row, "tool_id", "");
            Map<String, Object> binding = toolBindings.get(toolId);
            if (binding != null) {
                row.putAll(binding);
            }
            rows.add(row);
            recordToolSchemaSnapshot(row);
        }
        return rows;
    }

    public Map<String, Object> saveToolBinding(String toolId, Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        row.put("tool_id", toolId);
        row.putIfAbsent("binding_status", "enabled");
        row.putIfAbsent("binding_visibility", "discoverable");
        row.put("updated_at", Instant.now().toString());
        toolBindings.put(toolId, row);
        audit("tool.binding.saved", toolId, row);
        return row;
    }

    public void recordToolSchemaSnapshot(Map<String, Object> tool) {
        String toolId = string(tool, "tool_id", "");
        if (toolId.isBlank()) {
            return;
        }
        Object schema = tool.getOrDefault("parameter_schema", Map.of());
        String checksum = Integer.toHexString(String.valueOf(schema).hashCode());
        List<Map<String, Object>> snapshots =
                toolSchemaSnapshots.computeIfAbsent(toolId, ignored -> new ArrayList<>());
        boolean exists =
                snapshots.stream()
                        .anyMatch(row -> checksum.equals(String.valueOf(row.get("checksum"))));
        if (!exists) {
            snapshots.add(
                    0,
                    row(
                            "version",
                            "v" + (snapshots.size() + 1),
                            "checksum",
                            checksum,
                            "discovered_at",
                            Instant.now().toString(),
                            "parameter_schema",
                            schema));
        }
    }

    public List<Map<String, Object>> toolSchemaSnapshots(String toolId) {
        return toolSchemaSnapshots.getOrDefault(toolId, List.of());
    }

    private List<String> parameterNames(Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties)) {
            return List.of();
        }
        return properties.keySet().stream().map(String::valueOf).toList();
    }

    private List<String> requiredNames(Map<String, Object> schema) {
        if (schema == null || !(schema.get("required") instanceof List<?> required)) {
            return List.of();
        }
        return required.stream().map(String::valueOf).toList();
    }

    private McpSpec toMcpSpec(Map<String, Object> row) {
        String transport = string(row, "transport", "streamable-http");
        String endpoint = string(row, "endpoint", "");
        String auth = string(row, "auth_header", "");
        Map<String, String> headers = auth.isBlank() ? Map.of() : Map.of("Authorization", auth);
        return new McpSpec(
                string(row, "id", string(row, "name", "mcp")),
                transport,
                string(row, "command", ""),
                stringList(row.get("args")),
                Map.of(),
                endpoint,
                headers,
                Map.of(),
                stringList(row.get("tool_filter")),
                java.time.Duration.ofMillis(number(row.get("timeout_ms"), 5000)),
                java.time.Duration.ofMillis(number(row.get("timeout_ms"), 5000)),
                Boolean.TRUE.equals(row.getOrDefault("enabled", true)));
    }

    private Map<String, Object> agentRow(AgentDefinition definition) {
        return row(
                "agent_id",
                definition.agentId(),
                "display_name",
                definition.name(),
                "name",
                definition.name(),
                "description",
                definition.systemPrompt(),
                "domain",
                "platform",
                "source",
                "builtin",
                "enabled",
                definition.enabled(),
                "model",
                definition.model(),
                "included_tools",
                definition.toolRefs(),
                "included_skills",
                definition.skillRefs(),
                "flow_bindings",
                Map.of("default", "agentscope_runtime"));
    }

    private Map<String, Object> providerRow(ModelProviderSpec spec) {
        return row(
                "provider_id",
                spec.providerId(),
                "display_name",
                spec.displayName(),
                "provider_type",
                spec.providerType(),
                "default_base_url",
                spec.defaultBaseUrl(),
                "endpoint_path",
                spec.endpointPath(),
                "secret_ref",
                spec.secretRef(),
                "timeout_ms",
                spec.timeoutMs(),
                "description",
                spec.description(),
                "status",
                spec.status());
    }

    private Map<String, Object> event(String runId, String eventType, Map<String, Object> payload) {
        return row(
                "event_id",
                sequence.getAndIncrement(),
                "run_id",
                runId,
                "event_type",
                eventType,
                "type",
                eventType,
                "payload",
                payload,
                "created_at",
                Instant.now().toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((k, v) -> map.put(String.valueOf(k), v));
            return map;
        }
        return Map.of();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        String text = asString(value);
        if (!text.isBlank()) {
            payload.putIfAbsent(key, text);
        }
    }

    private static void normalizeToolSkillPayload(Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        if (!payload.containsKey("tool_name") && !payload.containsKey("tool_id")) {
            Map<String, Object> tool = asMap(payload.get("tool"));
            putIfPresent(payload, "tool_id", tool.get("tool_id"));
            putIfPresent(payload, "tool_name", tool.get("tool_name"));
            putIfPresent(payload, "tool_name", tool.get("name"));
            putIfPresent(payload, "tool_type", tool.get("type"));
        }
        if (!payload.containsKey("tool_name") && !payload.containsKey("tool_id")) {
            putIfPresent(payload, "tool_name", payload.get("tool_call_name"));
            putIfPresent(payload, "tool_id", payload.get("tool_call_id"));
        }
        if (!payload.containsKey("tool_name") && payload.containsKey("tool")) {
            String rawTool = asString(payload.get("tool"));
            if (!rawTool.isBlank() && !rawTool.startsWith("{")) {
                payload.put("tool_name", rawTool);
            }
        }
        if (!payload.containsKey("tool_name")
                && payload.containsKey("tool_id")
                && !payload.containsKey("name")) {
            payload.put("tool_name", payload.get("tool_id"));
        }
        if (!payload.containsKey("skill_name") && !payload.containsKey("skill_id")) {
            Map<String, Object> skill = asMap(payload.get("skill"));
            putIfPresent(payload, "skill_id", skill.get("skill_id"));
            putIfPresent(payload, "skill_name", skill.get("skill_name"));
            putIfPresent(payload, "skill_name", skill.get("name"));
        }
        if (!payload.containsKey("skill_name") && !payload.containsKey("skill_id")) {
            putIfPresent(payload, "skill_name", payload.get("skill_call_name"));
        }
        if (!payload.containsKey("skill_name") && payload.containsKey("skill")) {
            String rawSkill = asString(payload.get("skill"));
            if (!rawSkill.isBlank() && !rawSkill.startsWith("{")) {
                payload.put("skill_name", rawSkill);
            }
        }
        if (!payload.containsKey("skill_name")
                && payload.containsKey("skill_id")
                && !payload.containsKey("skill_id_name")) {
            payload.put("skill_name", payload.get("skill_id"));
        }
        payload.putIfAbsent("status", payload.getOrDefault("status", "success"));
        payload.putIfAbsent(
                "sequence",
                payload.containsKey("sequence")
                        ? payload.get("sequence")
                        : String.valueOf(System.nanoTime()));
    }

    private static String safeEventStep(String type) {
        return type == null || type.isBlank() ? "agent_event" : String.valueOf(type).toLowerCase();
    }

    private void seedModels() {
        for (ModelProviderSpec spec : providerRegistry.all()) {
            providers.put(spec.providerId(), providerRow(spec));
        }
        for (ModelSpec spec : modelRegistry.all()) {
            providers.putIfAbsent(
                    spec.provider(),
                    row(
                            "provider_id",
                            spec.provider(),
                            "display_name",
                            spec.provider(),
                            "provider_type",
                            spec.provider(),
                            "default_base_url",
                            spec.baseUrl(),
                            "endpoint_path",
                            spec.endpointPath(),
                            "status",
                            "active"));
            modelRows.put(
                    spec.modelId(),
                    row(
                            "model_id",
                            spec.modelId(),
                            "display_name",
                            spec.modelId(),
                            "provider_id",
                            spec.provider(),
                            "model_name",
                            spec.model().isBlank() ? spec.modelId() : spec.model(),
                            "base_url",
                            spec.baseUrl(),
                            "model_kind",
                            spec.kind(),
                            "provider_call_type",
                            "embedding".equals(spec.kind()) ? "embed" : "generate",
                            "kind",
                            spec.kind(),
                            "capabilities",
                            spec.capabilities(),
                            "dimensions",
                            spec.dimensions(),
                            "status",
                            spec.enabled() ? "active" : "disabled",
                            "description",
                            spec.description()));
        }
        String defaultChatModel = defaultModelForSlot("chat");
        if (!defaultChatModel.isBlank()) {
            slotBindings.put(
                    "qa",
                    row(
                            "slot_key",
                            "qa",
                            "scope",
                            "platform",
                            "org_id",
                            "",
                            "model_id",
                            defaultChatModel));
        }
    }

    private String defaultModelForSlot(String kind) {
        return modelRows.values().stream()
                .filter(row -> "active".equals(String.valueOf(row.getOrDefault("status", ""))))
                .filter(row -> kind.equals(String.valueOf(row.getOrDefault("model_kind", ""))))
                .filter(
                        row ->
                                "chat".equals(kind)
                                        ? "generate"
                                                .equals(
                                                        String.valueOf(
                                                                row.getOrDefault(
                                                                        "provider_call_type", "")))
                                        : true)
                .map(row -> String.valueOf(row.getOrDefault("model_id", "")).trim())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseGet(
                        () ->
                                modelRows.values().stream()
                                        .filter(
                                                row ->
                                                        kind.equals(
                                                                String.valueOf(
                                                                        row.getOrDefault(
                                                                                "model_kind", ""))))
                                        .map(
                                                row ->
                                                        String.valueOf(
                                                                        row.getOrDefault(
                                                                                "model_id", ""))
                                                                .trim())
                                        .filter(value -> !value.isBlank())
                                        .findFirst()
                                        .orElse(""));
    }

    private void seedMcps() {
        for (McpSpec spec : mcpRegistry.all()) {
            mcpServers.put(
                    spec.mcpId(),
                    row(
                            "id",
                            spec.mcpId(),
                            "name",
                            spec.mcpId(),
                            "transport",
                            spec.transport(),
                            "command",
                            spec.command(),
                            "args",
                            spec.args(),
                            "endpoint",
                            spec.url() == null ? spec.command() : spec.url(),
                            "description",
                            "AgentScope MCP",
                            "timeout_ms",
                            spec.timeout() == null ? 5000 : spec.timeout().toMillis(),
                            "tool_filter",
                            spec.enableTools(),
                            "enabled",
                            spec.enabled(),
                            "metadata",
                            row("health_status", "unknown", "last_tool_count", 0)));
        }
    }

    private static List<Map<String, Object>> sorted(
            Iterable<Map<String, Object>> rows, String key) {
        List<Map<String, Object>> list = new ArrayList<>();
        rows.forEach(list::add);
        list.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault(key, ""))));
        return list;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    private static String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String string = String.valueOf(value);
        return string.isBlank() ? fallback : Boolean.parseBoolean(string);
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String sourceDomain(String source) {
        return source == null || source.isBlank() ? "platform" : source;
    }

    @SuppressWarnings("unchecked")
    private ModelSpec toModelSpec(Map<String, Object> row) {
        Map<String, Object> provider =
                providers.getOrDefault(string(row, "provider_id", ""), Map.of());
        String secretRef = firstText(row.get("secret_ref"), provider.get("secret_ref"));
        String apiKey = "";
        String apiKeyEnv = "";
        if (secretRef.startsWith("env:")) {
            apiKeyEnv = secretRef.substring(4);
        } else if (secretRef.matches("[A-Z][A-Z0-9_]*")) {
            apiKeyEnv = secretRef;
        } else {
            apiKey = secretRef;
        }
        return new ModelSpec(
                string(row, "model_id", ""),
                string(row, "model_kind", string(row, "kind", "chat")),
                "provider",
                firstText(row.get("provider_id"), provider.get("provider_type")),
                string(row, "model_name", string(row, "model_id", "")),
                "",
                "",
                apiKey,
                apiKeyEnv,
                firstText(row.get("base_url"), provider.get("default_base_url")),
                firstText(provider.get("endpoint_path"), row.get("endpoint_path")),
                "",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                (Map<String, String>) row.getOrDefault("extra_headers", Map.of()),
                (Map<String, Object>) row.getOrDefault("extra_body", Map.of()),
                Map.of(),
                row.get("timeout_ms") instanceof Number n ? n.longValue() : null,
                null,
                null,
                null,
                null,
                "",
                "",
                null,
                "",
                "",
                row.get("dimensions") instanceof Number d ? d.intValue() : null,
                string(row, "description", ""),
                "active".equals(string(row, "status", "active")),
                stringList(row.get("capabilities")));
    }

    private ModelProviderSpec toProviderSpec(Map<String, Object> row) {
        return new ModelProviderSpec(
                string(row, "provider_id", ""),
                string(row, "display_name", string(row, "provider_id", "")),
                string(row, "provider_type", "openai-compatible"),
                string(row, "default_base_url", ""),
                string(row, "endpoint_path", ""),
                string(row, "secret_ref", ""),
                row.get("timeout_ms") instanceof Number n ? n.longValue() : null,
                string(row, "description", ""),
                string(row, "status", "active"));
    }

    private static String firstText(Object first, Object second) {
        String value = first == null ? "" : String.valueOf(first).trim();
        if (!value.isBlank()) {
            return value;
        }
        return second == null ? "" : String.valueOf(second).trim();
    }

    private void audit(String event, String targetId, Map<String, Object> payload) {
        Map<String, Object> row =
                row(
                        "id",
                        sequence.getAndIncrement(),
                        "event_type",
                        event,
                        "target_id",
                        targetId,
                        "payload",
                        payload,
                        "actor",
                        "compat",
                        "created_at",
                        Instant.now().toString());
        audit.add(row);
        if (isSqliteEnabled()) {
            insertAudit(row);
        }
    }

    private void loadAudit() {
        if (!isSqliteEnabled()) {
            return;
        }
        String sql =
                "SELECT id, event_type, target_id, payload, actor, created_at FROM "
                        + SQLITE_AUDIT_EVENTS_TABLE
                        + " ORDER BY id DESC LIMIT 500";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(
                        row(
                                "id",
                                resultSet.getLong("id"),
                                "event_type",
                                resultSet.getString("event_type"),
                                "target_id",
                                resultSet.getString("target_id"),
                                "payload",
                                mapFromJson(resultSet.getString("payload")),
                                "actor",
                                resultSet.getString("actor"),
                                "created_at",
                                resultSet.getString("created_at")));
            }
            audit.clear();
            audit.addAll(rows);
        } catch (Exception e) {
            log.warn("Load audit events from sqlite failed: {}", e.getMessage());
        }
    }

    private void insertAudit(Map<String, Object> row) {
        String sql =
                "INSERT INTO "
                        + SQLITE_AUDIT_EVENTS_TABLE
                        + " (event_type, target_id, payload, actor, created_at) VALUES (?, ?, ?,"
                        + " ?, ?)";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, string(row, "event_type", ""));
            statement.setString(2, string(row, "target_id", ""));
            statement.setString(
                    3, objectMapper.writeValueAsString(row.getOrDefault("payload", Map.of())));
            statement.setString(4, string(row, "actor", "compat"));
            statement.setString(5, string(row, "created_at", Instant.now().toString()));
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("Insert audit event failed: {}", e.getMessage());
        }
    }

    /** Materialize a package from its SQLite blob, migrating legacy absolute paths when needed. */
    private Path ensureSkillPackageArchive(Map<String, Object> pkg) {
        String id = string(pkg, "id", "");
        if (id.isBlank()) {
            return null;
        }
        Path target = skillPackagesDir().resolve(id + ".zip").normalize();
        try {
            Files.createDirectories(target.getParent());
            var persisted = artifactStore.loadSkillPackage(id);
            if (persisted.isPresent()) {
                Files.write(target, persisted.get().content());
                pkg.put("zip_path", storage.toWorkspaceRelative(target));
                return target;
            }
            String legacyPath = string(pkg, "zip_path", "");
            if (!legacyPath.isBlank()) {
                Path legacy = storage.resolveRelativeToWorkspace(legacyPath);
                if (Files.isRegularFile(legacy)) {
                    byte[] content = Files.readAllBytes(legacy);
                    artifactStore.saveSkillPackage(id, string(pkg, "filename", id + ".zip"), content);
                    Files.write(target, content);
                    pkg.put("zip_path", storage.toWorkspaceRelative(target));
                    return target;
                }
            }
            return Files.isRegularFile(target) ? target : null;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to materialize skill package: " + id, e);
        }
    }

    private void loadRunState() {
        if (!isSqliteEnabled()) {
            return;
        }
        loadRuns();
        loadRunSteps();
        loadRunEvents();
        loadWaitings();
    }

    private void loadRuns() {
        String sql = "SELECT run_id, payload FROM " + SQLITE_RUNS_TABLE + " ORDER BY updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            runs.clear();
            while (resultSet.next()) {
                Map<String, Object> payload = mapFromJson(resultSet.getString("payload"));
                if (payload.isEmpty()) {
                    continue;
                }
                payload.putIfAbsent("run_id", resultSet.getString("run_id"));
                runs.put(resultSet.getString("run_id"), payload);
            }
        } catch (Exception e) {
            log.warn("Load agent runs from sqlite failed: {}", e.getMessage());
        }
    }

    private void loadRunSteps() {
        String sql =
                "SELECT run_id, step_id, payload FROM "
                        + SQLITE_RUN_STEPS_TABLE
                        + " ORDER BY run_id, step_id";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            runSteps.clear();
            while (resultSet.next()) {
                Map<String, Object> payload = mapFromJson(resultSet.getString("payload"));
                if (payload.isEmpty()) {
                    continue;
                }
                payload.putIfAbsent("step_id", resultSet.getString("step_id"));
                runSteps
                        .computeIfAbsent(resultSet.getString("run_id"), ignored -> new ArrayList<>())
                        .add(payload);
            }
        } catch (Exception e) {
            log.warn("Load agent run steps from sqlite failed: {}", e.getMessage());
        }
    }

    private void loadRunEvents() {
        String sql =
                "SELECT event_id, run_id, event_type, payload, created_at FROM "
                        + SQLITE_RUN_EVENTS_TABLE
                        + " ORDER BY event_id";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            runEvents.clear();
            while (resultSet.next()) {
                Map<String, Object> payload = mapFromJson(resultSet.getString("payload"));
                if (payload.isEmpty()) {
                    payload = new LinkedHashMap<>();
                }
                payload.putIfAbsent("event_id", resultSet.getLong("event_id"));
                payload.putIfAbsent("run_id", resultSet.getString("run_id"));
                payload.putIfAbsent("event_type", resultSet.getString("event_type"));
                payload.putIfAbsent("type", resultSet.getString("event_type"));
                payload.putIfAbsent("created_at", resultSet.getString("created_at"));
                runEvents
                        .computeIfAbsent(resultSet.getString("run_id"), ignored -> new ArrayList<>())
                        .add(payload);
                long eventId = resultSet.getLong("event_id");
                sequence.updateAndGet(current -> Math.max(current, eventId + 1));
            }
        } catch (Exception e) {
            log.warn("Load agent run events from sqlite failed: {}", e.getMessage());
        }
    }

    private void loadWaitings() {
        String sql =
                "SELECT waiting_id, run_id, payload FROM "
                        + SQLITE_WAITINGS_TABLE
                        + " ORDER BY updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            waitings.clear();
            while (resultSet.next()) {
                Map<String, Object> payload = mapFromJson(resultSet.getString("payload"));
                if (payload.isEmpty()) {
                    payload = new LinkedHashMap<>();
                }
                payload.putIfAbsent("waiting_id", resultSet.getString("waiting_id"));
                payload.putIfAbsent("run_id", resultSet.getString("run_id"));
                waitings.put(resultSet.getString("run_id"), payload);
            }
        } catch (Exception e) {
            log.warn("Load agent waitings from sqlite failed: {}", e.getMessage());
        }
    }

    private void persistRun(Map<String, Object> run) {
        if (!isSqliteEnabled()) {
            return;
        }
        String runId = string(run, "run_id", "");
        if (runId.isBlank()) {
            return;
        }
        String sql =
                "INSERT INTO "
                        + SQLITE_RUNS_TABLE
                        + " (run_id, payload, updated_at) VALUES (?, ?, ?) ON CONFLICT(run_id)"
                        + " DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, objectMapper.writeValueAsString(run));
            statement.setString(3, string(run, "finished_at", string(run, "started_at", Instant.now().toString())));
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("Persist agent run {} failed: {}", runId, e.getMessage());
        }
    }

    private void persistRunStep(String runId, Map<String, Object> step) {
        if (!isSqliteEnabled()) {
            return;
        }
        String stepId = string(step, "step_id", "");
        if (runId == null || runId.isBlank() || stepId.isBlank()) {
            return;
        }
        String sql =
                "INSERT INTO "
                        + SQLITE_RUN_STEPS_TABLE
                        + " (run_id, step_id, payload, updated_at) VALUES (?, ?, ?, ?) ON"
                        + " CONFLICT(run_id, step_id) DO UPDATE SET payload = excluded.payload,"
                        + " updated_at = excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, stepId);
            statement.setString(3, objectMapper.writeValueAsString(step));
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("Persist agent run step {}/{} failed: {}", runId, stepId, e.getMessage());
        }
    }

    private void persistRunEvent(Map<String, Object> event) {
        if (!isSqliteEnabled()) {
            return;
        }
        long eventId = number(event.get("event_id"), -1);
        String runId = string(event, "run_id", "");
        if (eventId < 0 || runId.isBlank()) {
            return;
        }
        String sql =
                "INSERT INTO "
                        + SQLITE_RUN_EVENTS_TABLE
                        + " (event_id, run_id, event_type, payload, created_at) VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT(event_id) DO UPDATE SET run_id = excluded.run_id,"
                        + " event_type = excluded.event_type, payload = excluded.payload,"
                        + " created_at = excluded.created_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, eventId);
            statement.setString(2, runId);
            statement.setString(3, string(event, "event_type", string(event, "type", "agent_event")));
            statement.setString(4, objectMapper.writeValueAsString(event));
            statement.setString(5, string(event, "created_at", Instant.now().toString()));
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("Persist agent run event {} failed: {}", eventId, e.getMessage());
        }
    }

    private void persistWaiting(Map<String, Object> waiting) {
        if (!isSqliteEnabled()) {
            return;
        }
        String waitingId = string(waiting, "waiting_id", "");
        String runId = string(waiting, "run_id", "");
        if (waitingId.isBlank() || runId.isBlank()) {
            return;
        }
        String sql =
                "INSERT INTO "
                        + SQLITE_WAITINGS_TABLE
                        + " (waiting_id, run_id, payload, updated_at) VALUES (?, ?, ?, ?) ON"
                        + " CONFLICT(waiting_id) DO UPDATE SET run_id = excluded.run_id,"
                        + " payload = excluded.payload, updated_at = excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, waitingId);
            statement.setString(2, runId);
            statement.setString(3, objectMapper.writeValueAsString(waiting));
            statement.setString(4, string(waiting, "updated_at", Instant.now().toString()));
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("Persist waiting {} failed: {}", waitingId, e.getMessage());
        }
    }

    private void loadMemories() {
        if (!isSqliteEnabled()) {
            return;
        }
        String sql =
                "SELECT memory_id, payload FROM " + SQLITE_MEMORY_TABLE + " ORDER BY memory_id";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
            long maxId = 0;
            while (resultSet.next()) {
                Map<String, Object> row = mapFromJson(resultSet.getString("payload"));
                if (row.isEmpty()) {
                    continue;
                }
                String id = string(row, "id", resultSet.getString("memory_id"));
                if (!id.isBlank()) {
                    rows.put(id, row);
                    try {
                        maxId = Math.max(maxId, Long.parseLong(id));
                    } catch (NumberFormatException ignored) {
                        // Non-numeric ids are allowed but do not affect the numeric sequence.
                    }
                }
            }
            memories.clear();
            memories.putAll(rows);
            if (maxId > 0) {
                long nextId = maxId + 1;
                sequence.updateAndGet(current -> Math.max(current, nextId));
            }
        } catch (Exception e) {
            log.warn("Load memories from sqlite failed: {}", e.getMessage());
        }
    }

    private void persistMemory(Map<String, Object> row) {
        if (!isSqliteEnabled()) {
            return;
        }
        String id = string(row, "id", "");
        if (id.isBlank()) {
            return;
        }
        String sql =
                "INSERT INTO "
                        + SQLITE_MEMORY_TABLE
                        + " (memory_id, payload, updated_at) VALUES (?, ?, ?) ON"
                        + " CONFLICT(memory_id) DO UPDATE SET payload = excluded.payload,"
                        + " updated_at = excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, objectMapper.writeValueAsString(row));
            statement.setString(3, string(row, "updated_at", Instant.now().toString()));
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("Persist memory {} failed: {}", id, e.getMessage());
        }
    }

    private static String replaceManagedMemoryBlock(String existing, String block) {
        String text = existing == null ? "" : existing;
        int start = text.indexOf(MEMORY_BLOCK_START);
        int end = text.indexOf(MEMORY_BLOCK_END);
        if (start >= 0 && end > start) {
            int afterEnd = end + MEMORY_BLOCK_END.length();
            return text.substring(0, start).stripTrailing()
                    + "\n\n"
                    + block.stripTrailing()
                    + "\n\n"
                    + text.substring(afterEnd).stripLeading();
        }
        if (text.isBlank()) {
            return block;
        }
        return block.stripTrailing() + "\n\n" + text.stripLeading();
    }

    private static String removeManagedMemoryBlock(String existing) {
        String text = existing == null ? "" : existing;
        int start = text.indexOf(MEMORY_BLOCK_START);
        int end = text.indexOf(MEMORY_BLOCK_END);
        if (start >= 0 && end > start) {
            int afterEnd = end + MEMORY_BLOCK_END.length();
            return text.substring(0, start) + text.substring(afterEnd);
        }
        return text;
    }

    private void recordProbeRun(String targetType, String targetId, Map<String, Object> payload) {
        if (!isSqliteEnabled()) {
            return;
        }
        String sql =
                "INSERT INTO "
                        + SQLITE_PROBE_RUNS_TABLE
                        + " (target_type, target_id, ok, stage, payload, created_at) VALUES (?, ?,"
                        + " ?, ?, ?, ?)";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetType);
            statement.setString(2, targetId);
            statement.setInt(3, Boolean.TRUE.equals(payload.get("ok")) ? 1 : 0);
            statement.setString(4, string(payload, "stage", ""));
            statement.setString(5, objectMapper.writeValueAsString(payload));
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("Insert probe run failed: {}", e.getMessage());
        }
    }

    private void recordMigration(
            String migrationKey, String source, String target, String status, String message) {
        if (!isSqliteEnabled()) {
            return;
        }
        String now = Instant.now().toString();
        String sql =
                "INSERT INTO "
                        + SQLITE_MIGRATION_HISTORY_TABLE
                        + " (migration_key, source, target, status, message, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(migration_key) DO"
                        + " UPDATE SET status = excluded.status, message = excluded.message,"
                        + " updated_at = excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, migrationKey);
            statement.setString(2, source);
            statement.setString(3, target);
            statement.setString(4, status);
            statement.setString(5, message);
            statement.setString(6, now);
            statement.setString(7, now);
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("Record migration history failed: {}", e.getMessage());
        }
    }
}
