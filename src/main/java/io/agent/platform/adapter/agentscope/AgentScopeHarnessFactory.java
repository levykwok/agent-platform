/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.adapter.agentscope;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.RuntimeToolGovernance;
import io.agent.platform.web.PlatformCompatibilityState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeHarnessFactory {

    private static final String PLATFORM_MEMORY_FLUSH_PROMPT =
            """
            You are the agent platform memory extraction assistant. Analyze the conversation \
            below and extract durable memories that help future conversations.

            Output ONLY a markdown bullet list. Each bullet must be concise and self-contained. \
            If there is nothing worth remembering, respond with exactly: NO_REPLY

            Extraction rules:
            - When the user explicitly asks to remember something ("记住", "以后记得", \
            "帮我记下", "remember", "note this"), extract it as a memory unless it is a \
            credential, secret, illegal instruction, or highly sensitive private data.
            - For opinions, insults, emotions, or subjective claims, do NOT rewrite them as \
            objective facts. Store them as the user's stated view, preference, or expression.
            - Do not moralize, verify, or reject subjective user statements during extraction. \
            Preserve the user's intent while making the memory safe and clearly attributed.
            - Extract preferences, personal information shared by the user, project context, \
            technical decisions, rationale, commitments, deadlines, and action items.
            - Ignore routine greetings, transient status updates, and tool-call mechanics.

            Examples:
            - User says "记住 我喜欢中文回答" -> "- User prefers Chinese answers."
            - User says "记住 王申强是傻逼" -> "- User expressed a strong negative opinion about 王申强."
            - User says "记住我的 API key 是 ..." -> NO_REPLY

            Write target:
            - Your output will be appended to today's daily memory ledger \
            (memory/YYYY-MM-DD.md), not directly to MEMORY.md.
            - MEMORY.md and today's earlier ledger entries may be shown as read-only context. \
            Do not duplicate memories already covered there.
            """;

    private final AgentCapabilityAssembler capabilityAssembler;
    private final PlatformCompatibilityState platformState;
    private final PlatformStorageLayer storage;
    private final RuntimeToolGovernance toolGovernance;
    private final Environment environment;
    private static final String DEFAULT_AGENT_ID = "unknown_agent";

    public AgentScopeHarnessFactory(
            AgentCapabilityAssembler capabilityAssembler,
            PlatformCompatibilityState platformState,
            PlatformStorageLayer storage,
            RuntimeToolGovernance toolGovernance,
            Environment environment) {
        this.capabilityAssembler = capabilityAssembler;
        this.platformState = platformState;
        this.storage = storage;
        this.toolGovernance = toolGovernance;
        this.environment = environment;
    }

    public HarnessAgent create(AgentDefinition definition) {
        return create(definition, "", "");
    }

    public HarnessAgent create(AgentDefinition definition, String tenantId, String userId) {
        ensureWorkspace(definition);
        Toolkit toolkit = new Toolkit();
        capabilityAssembler.applyToolsAndMcps(toolkit, definition, tenantId, userId);
        // MCP clients are registered synchronously, but their model-facing schemas are
        // materialized through getToolSchemas(). Snapshot schemas (not only the name index) so
        // configured MCP tools are not mislabeled later as Harness auto-injected built-ins.
        Set<String> configuredToolNames = new LinkedHashSet<>();
        toolkit.getToolSchemas().forEach(schema -> configuredToolNames.add(schema.getName()));
        List<AgentSkillRepository> skillRepositories =
                capabilityAssembler.buildSkillRepositories(definition, tenantId, userId);
        AgentExecutionPolicy policy = AgentExecutionPolicy.from(definition);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(definition.name())
                        .sysPrompt(definition.systemPrompt())
                        .model(resolveModel(definition))
                        .workspace(definition.workspace())
                        .stateStore(stateStore(definition))
                        .toolkit(toolkit)
                        .maxIters(policy.maxIters())
                        .asyncToolTimeout(Duration.ofMillis(Math.min(policy.timeoutMs(), 60_000L)))
                        .skillRepositories(skillRepositories)
                        .memory(
                                MemoryConfig.builder()
                                        .flushPrompt(PLATFORM_MEMORY_FLUSH_PROMPT)
                                        .build())
                        .compaction(CompactionConfig.builder().triggerMessages(10).build())
                        .enablePendingToolRecovery(true)
                        .middleware(
                                new LlmCallAuditMiddleware(
                                        platformState,
                                        safe(definition.agentId(), DEFAULT_AGENT_ID),
                                        definition.model()))
                        .middleware(
                                new RuntimeGuardMiddleware(
                                        platformState,
                                        safe(definition.agentId(), DEFAULT_AGENT_ID),
                                        policy.maxToolCalls(),
                                        Duration.ofMillis(policy.timeoutMs())))
                        // Platform orchestration owns Supervisor/Router/Workflow execution. Never
                        // expose Harness' implicit general-purpose child agent or task tools.
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.DONT_ASK)
                                        .build());
        if (!policy.memoryTools()) {
            builder.disableMemoryTools();
        }
        boolean filesystemAllowed = policy.fileRead() || policy.fileWrite();
        if (!filesystemAllowed) {
            builder.disableFilesystemTools();
        }
        boolean sandboxedShell = policy.shell() && skillSandboxEnabled();
        if (!sandboxedShell) {
            builder.disableShellTool();
        }
        if (skillSandboxEnabled() && (filesystemAllowed || policy.shell())) {
            // Harness registers its built-in `execute` tool only for a sandbox-backed filesystem.
            // This keeps Skill scripts out of the platform host process and projects the Skill
            // files into the isolated /workspace path before every agent call.
            builder.filesystem(skillSandbox());
        } else {
            // Even when all file tools are hidden, pin Harness' context filesystem to this
            // agent's workspace. This prevents the library fallback to ${user.dir}.
            builder.filesystem(
                    new LocalFilesystemSpec()
                            .project(definition.workspace())
                            .projectWritable(false)
                            .inheritEnv(false)
                            .isolationScope(IsolationScope.USER));
        }
        HarnessAgent agent = builder.build();
        enforceFinalToolkit(agent.getToolkit(), definition, policy);
        recordManifest(
                agent.getToolkit(), definition, tenantId, userId, configuredToolNames, policy);
        return agent;
    }

    private boolean skillSandboxEnabled() {
        return environment.getProperty("agent.platform.sandbox.enabled", Boolean.class, false);
    }

    private DockerFilesystemSpec skillSandbox() {
        long memoryBytes =
                environment.getProperty(
                        "agent.platform.sandbox.memory-size-bytes",
                        Long.class,
                        512L * 1024 * 1024);
        long cpuCount =
                environment.getProperty("agent.platform.sandbox.cpu-count", Long.class, 1L);
        String image =
                environment.getProperty("agent.platform.sandbox.image", "python:3.12-slim");
        DockerFilesystemSpec spec =
                new DockerFilesystemSpec()
                        .image(image)
                        .workspaceRoot("/workspace")
                        .memorySizeBytes(memoryBytes)
                        .cpuCount(cpuCount)
                        // DockerFilesystemSpec uses no network by default; make that boundary
                        // explicit.
                        .network("none")
                        .additionalRunArgs(
                                "--cap-drop=ALL",
                                "--security-opt=no-new-privileges",
                                "--pids-limit=64");
        spec.isolationScope(IsolationScope.SESSION);
        return spec;
    }

    private JsonFileAgentStateStore stateStore(AgentDefinition definition) {
        Path root = storage.agentStateDirectory(definition.agentId()).normalize().toAbsolutePath();
        return new JsonFileAgentStateStore(root);
    }

    private String resolveModel(AgentDefinition definition) {
        String policyModel = resolveModelPolicy(definition.modelPolicy());
        if (!policyModel.isBlank()) {
            return policyModel;
        }
        String model = definition.model() == null ? "" : definition.model().trim();
        if (!model.isBlank()) {
            return model;
        }
        String defaultModel = platformState.defaultChatModelId();
        if (defaultModel == null || defaultModel.isBlank()) {
            throw new IllegalStateException(
                    "No chat model configured. Bind the qa model slot or set an agent model.");
        }
        return defaultModel;
    }

    public String resolveVisionModel(AgentDefinition definition) {
        Map<String, Object> modelPolicy = definition.modelPolicy();
        if (modelPolicy != null && modelPolicy.containsKey("vlm")) {
            String value = String.valueOf(modelPolicy.getOrDefault("vlm", "")).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        String defaultModel = platformState.defaultVlmModelId();
        if (!defaultModel.isBlank()) {
            return defaultModel;
        }
        throw new IllegalStateException(
                "No VLM model configured. Bind the vlm slot or set agent model_policy.vlm.");
    }

    private String resolveModelPolicy(Map<String, Object> modelPolicy) {
        if (modelPolicy == null || modelPolicy.isEmpty()) {
            return "";
        }
        for (String key : List.of("qa", "chat", "default", "primary")) {
            if (modelPolicy.containsKey(key)) {
                String value = String.valueOf(modelPolicy.getOrDefault(key, "")).trim();
                if (!value.isBlank()) {
                    return value;
                }
                String defaultModel = platformState.defaultChatModelId();
                return defaultModel == null ? "" : defaultModel;
            }
        }
        return "";
    }

    private static String safe(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? fallback : normalized;
    }

    private void enforceFinalToolkit(
            Toolkit toolkit, AgentDefinition definition, AgentExecutionPolicy policy) {
        Set<String> names = Set.copyOf(toolkit.getToolNames());
        for (String name : names) {
            boolean forbiddenBuiltin =
                    isSubagentTool(name)
                            || isTaskTool(name)
                            || (!policy.memoryTools() && isMemoryTool(name))
                            || (!policy.fileRead() && isFileReadTool(name))
                            || (!policy.fileWrite() && isFileWriteTool(name))
                            || (!(policy.shell() && skillSandboxEnabled()) && "execute".equals(name));
            if (forbiddenBuiltin
                    || !toolGovernance.isAllowed(definition.agentId(), name, true)) {
                toolkit.removeTool(name);
            }
        }
    }

    private void recordManifest(
            Toolkit toolkit,
            AgentDefinition definition,
            String tenantId,
            String userId,
            Set<String> configuredToolNames,
            AgentExecutionPolicy policy) {
        List<Map<String, Object>> rows = new ArrayList<>();
        toolkit.getToolSchemas().forEach(
                schema -> {
                    String name = schema.getName();
                    boolean configured = configuredToolNames.contains(name);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tool_id", name);
                    row.put("name", name);
                    row.put("description", safe(schema.getDescription(), name));
                    row.put("parameter_schema", schema.getParameters());
                    row.put("source", configured ? "configured" : builtinSource(name));
                    row.put("auto_injected", !configured);
                    row.put("risk", risk(name));
                    row.put("side_effects", isFileWriteTool(name) || "execute".equals(name));
                    row.put("network", looksNetworked(name));
                    row.put("subagent", isSubagentTool(name));
                    row.put("runtime_enabled", true);
                    rows.add(row);
                });
        rows.sort(
                java.util.Comparator.comparing(
                        row -> String.valueOf(row.getOrDefault("tool_id", ""))));
        toolGovernance.recordManifest(definition.agentId(), tenantId, userId, rows);
    }

    private static boolean isFileReadTool(String name) {
        return Set.of("read_file", "grep_files", "glob_files", "list_files").contains(name);
    }

    private static boolean isFileWriteTool(String name) {
        return Set.of("write_file", "edit_file").contains(name);
    }

    private static boolean isMemoryTool(String name) {
        return name != null && (name.startsWith("memory_") || name.startsWith("session_"));
    }

    private static boolean isSubagentTool(String name) {
        return name != null
                && (name.startsWith("agent_")
                        || name.startsWith("task_")
                        || "agent_generate".equals(name));
    }

    private static boolean isTaskTool(String name) {
        return "wait_async_results".equals(name);
    }

    private static String builtinSource(String name) {
        if (isFileReadTool(name) || isFileWriteTool(name)) return "agentscope_filesystem";
        if ("execute".equals(name)) return "agentscope_shell";
        if (isMemoryTool(name)) return "agentscope_memory";
        if (isSubagentTool(name)) return "agentscope_subagent";
        if (isTaskTool(name)) return "agentscope_async_task";
        if (name != null && name.contains("skill")) return "agentscope_skill";
        return "agentscope_builtin";
    }

    private static String risk(String name) {
        if ("execute".equals(name) || isSubagentTool(name) || isTaskTool(name)) return "high";
        if (isFileWriteTool(name) || looksNetworked(name)) return "medium_high";
        if (isFileReadTool(name) || isMemoryTool(name)) return "medium";
        return "low";
    }

    private static boolean looksNetworked(String name) {
        if (name == null) return false;
        String normalized = name.toLowerCase();
        return normalized.contains("http")
                || normalized.contains("fetch")
                || normalized.contains("web")
                || normalized.contains("search")
                || normalized.contains("url");
    }

    private void ensureWorkspace(AgentDefinition definition) {
        try {
            Path workspace = definition.workspace();
            storage.ensureDirectory(workspace);
            storage.ensureDirectory(workspace.resolve("knowledge"));
            storage.ensureDirectory(workspace.resolve("skills"));
            storage.ensureDirectory(workspace.resolve("subagents"));
            Path agentsFile = workspace.resolve("AGENTS.md");
            if (Files.notExists(agentsFile)) {
                Files.writeString(
                        agentsFile,
                        "# " + definition.name() + "\n\n" + definition.systemPrompt() + "\n");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to initialize workspace for agent " + definition.agentId(), e);
        }
    }
}
