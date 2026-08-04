/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.adapter.agentscope;

import com.company.platform.control.AgentDefinition;
import com.company.platform.control.AgentDefinitionRegistry;
import com.company.platform.control.PlatformStorageLayer;
import com.company.platform.control.SubagentBinding;
import com.company.platform.web.PlatformCompatibilityState;
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
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeHarnessFactory {

    private static final String PLATFORM_MEMORY_FLUSH_PROMPT =
            """
            You are the company platform memory extraction assistant. Analyze the conversation \
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

    private final AgentDefinitionRegistry registry;
    private final AgentCapabilityAssembler capabilityAssembler;
    private final PlatformCompatibilityState platformState;
    private final PlatformStorageLayer storage;
    private final Environment environment;
    private static final String DEFAULT_AGENT_ID = "unknown_agent";

    public AgentScopeHarnessFactory(
            AgentDefinitionRegistry registry,
            AgentCapabilityAssembler capabilityAssembler,
            PlatformCompatibilityState platformState,
            PlatformStorageLayer storage,
            Environment environment) {
        this.registry = registry;
        this.capabilityAssembler = capabilityAssembler;
        this.platformState = platformState;
        this.storage = storage;
        this.environment = environment;
    }

    public HarnessAgent create(AgentDefinition definition) {
        ensureWorkspace(definition);
        Toolkit toolkit = new Toolkit();
        capabilityAssembler.applyToolsAndMcps(toolkit, definition);
        List<AgentSkillRepository> skillRepositories =
                capabilityAssembler.buildSkillRepositories(definition);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(definition.name())
                        .sysPrompt(definition.systemPrompt())
                        .model(resolveModel(definition))
                        .workspace(definition.workspace())
                        .stateStore(stateStore(definition))
                        .toolkit(toolkit)
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
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build());
        if (skillSandboxEnabled()) {
            // Harness registers its built-in `execute` tool only for a sandbox-backed filesystem.
            // This keeps Skill scripts out of the platform host process and projects the Skill
            // files into the isolated /workspace path before every agent call.
            builder.filesystem(skillSandbox());
        }
        for (SubagentBinding binding : definition.orchestration().subagents()) {
            builder.subagent(toSubagentDeclaration(binding));
        }
        return builder.build();
    }

    private boolean skillSandboxEnabled() {
        return environment.getProperty("company.platform.sandbox.enabled", Boolean.class, false);
    }

    private DockerFilesystemSpec skillSandbox() {
        long memoryBytes =
                environment.getProperty(
                        "company.platform.sandbox.memory-size-bytes",
                        Long.class,
                        512L * 1024 * 1024);
        long cpuCount =
                environment.getProperty("company.platform.sandbox.cpu-count", Long.class, 1L);
        String image =
                environment.getProperty("company.platform.sandbox.image", "python:3.12-slim");
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

    private SubagentDeclaration toSubagentDeclaration(SubagentBinding binding) {
        AgentDefinition target =
                registry.findPublished(binding.targetAgentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Subagent target not found: "
                                                        + binding.targetAgentId()));
        ensureWorkspace(target);
        return SubagentDeclaration.builder()
                .name(binding.bindingId())
                .description(binding.description())
                .workspace(target.workspace())
                .model(resolveModel(target))
                .exposeToUser(binding.exposeToUser())
                .tools(binding.toolRefs())
                .build();
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
