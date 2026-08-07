/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.adapter.agentscope;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.McpRegistry;
import io.agent.platform.control.McpSpec;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.SkillRegistry;
import io.agent.platform.control.SkillSpec;
import io.agent.platform.control.ToolRegistry;
import io.agent.platform.control.ToolSpec;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.tool.WorkflowTool;
import io.agent.platform.web.WorkflowAssetService;
import io.agent.platform.tool.PythonScriptTool;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AgentCapabilityAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentCapabilityAssembler.class);

    private final ToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final SkillRegistry skillRegistry;
    private final Environment environment;
    private final PlatformStorageLayer storage;
    private final WorkflowAssetService workflowAssetService;
    private final ObjectProvider<AgentRuntime> runtimeProvider;

    public AgentCapabilityAssembler(
            ToolRegistry toolRegistry,
            McpRegistry mcpRegistry,
            SkillRegistry skillRegistry,
            Environment environment,
            PlatformStorageLayer storage,
            WorkflowAssetService workflowAssetService,
            ObjectProvider<AgentRuntime> runtimeProvider) {
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.environment = environment;
        this.storage = storage;
        this.workflowAssetService = workflowAssetService;
        this.runtimeProvider = runtimeProvider;
    }

    public void applyToolsAndMcps(Toolkit toolkit, AgentDefinition definition) {
        List<String> toolRefs = safeRefs(definition.toolRefs());
        applyWorkflowTools(toolkit, workflowToolRefs(toolRefs));
        applyTools(toolkit, javaToolRefs(toolRefs));
        applyMcps(toolkit, definition.mcpRefs(), toolRefs);
    }

    private void applyWorkflowTools(Toolkit toolkit, List<String> workflowRefs) {
        if (workflowRefs.isEmpty()) {
            return;
        }
        AgentRuntime runtime = runtimeProvider.getObject();
        for (String ref : workflowRefs) {
            String workflowId = ref.substring("workflow:".length()).trim();
            if (workflowId.isBlank()) {
                throw new IllegalStateException("Invalid workflow tool ref: " + ref);
            }
            toolkit.registration()
                    .agentTool(new WorkflowTool(workflowAssetService.requirePublished(workflowId), runtime))
                    .apply();
        }
    }

    public List<AgentSkillRepository> buildSkillRepositories(AgentDefinition definition) {
        List<AgentSkillRepository> repos = new ArrayList<>();
        for (String skillRef : definition.skillRefs()) {
            SkillSpec spec =
                    skillRegistry
                            .find(skillRef)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Unknown skill ref "
                                                            + skillRef
                                                            + " in agent "
                                                            + definition.agentId()));
            if (!spec.enabled()) {
                continue;
            }
            repos.add(skillRepo(definition, spec));
        }
        return repos;
    }

    private void applyTools(Toolkit toolkit, List<String> toolRefs) {
        Set<String> seen = new HashSet<>();
        for (String toolRef : safeRefs(toolRefs)) {
            ToolSpec spec =
                    toolRegistry
                            .find(toolRef)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Unknown tool ref "
                                                            + toolRef
                                                            + " in agent toolkit config"));
            if (!spec.enabled()) {
                continue;
            }
            if ("python".equalsIgnoreCase(spec.type())) {
                registerPythonTool(toolkit, spec);
                continue;
            }
            if (!"java".equalsIgnoreCase(spec.type())) {
                throw new IllegalStateException(
                        "Unsupported tool type '" + spec.type() + "' for tool " + spec.toolId());
            }
            if (spec.className() == null || spec.className().isBlank()) {
                throw new IllegalStateException("Missing className for tool spec " + spec.toolId());
            }
            if (!seen.add(spec.toolId())) {
                log.warn("Duplicate toolRef {} ignored", spec.toolId());
                continue;
            }
            try {
                Object tool =
                        Class.forName(spec.className()).getDeclaredConstructor().newInstance();
                toolkit.registration().tool(tool).apply();
                log.info("Registered Java tool {} from {}", spec.toolId(), spec.className());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to instantiate tool "
                                + spec.toolId()
                                + " ("
                                + spec.className()
                                + ")",
                        e);
            }
        }
    }

    private void registerPythonTool(Toolkit toolkit, ToolSpec spec) {
        Path scriptPath = resolveToolPath(spec.scriptPath());
        PythonScriptTool tool =
                new PythonScriptTool(
                        spec.toolId(),
                        spec.description(),
                        spec.parameterSchema(),
                        scriptPath,
                        Duration.ofMillis(spec.timeoutMs()),
                        environment.getProperty("AGENT_PLATFORM_PYTHON", "python"));
        toolkit.registration().agentTool(tool).apply();
        log.info("Registered Python script tool {} from {}", spec.toolId(), scriptPath);
    }

    private void applyMcps(Toolkit toolkit, List<String> mcpRefs, List<String> toolRefs) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        Map<String, List<String>> agentToolFilters = mcpToolRefs(toolRefs);
        for (String mcpRef : safeRefs(mcpRefs)) {
            McpSpec spec =
                    mcpRegistry
                            .find(mcpRef)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Unknown mcp ref "
                                                            + mcpRef
                                                            + " in agent refs"));
            if (!spec.enabled()) {
                continue;
            }
            McpServerConfig cfg =
                    toConfig(spec, agentToolFilters.getOrDefault(spec.mcpId(), List.of()));
            configs.put(spec.mcpId(), cfg);
            log.info("Prepared MCP server {} ({})", spec.mcpId(), spec.transport());
        }
        if (!configs.isEmpty()) {
            McpServerRegistrar.register(toolkit, configs);
        }
    }

    private McpServerConfig toConfig(McpSpec spec, List<String> agentToolFilter) {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setTransport(resolve(spec.transport()));
        cfg.setCommand(resolve(spec.command()));
        cfg.setArgs(spec.args());
        cfg.setEnv(spec.env());
        cfg.setUrl(resolve(spec.url()));
        cfg.setHeaders(spec.headers());
        cfg.setQueryParams(spec.queryParams());
        cfg.setEnableTools(resolveMcpToolFilter(spec.enableTools(), agentToolFilter));
        cfg.setTimeout(spec.timeout());
        cfg.setInitializationTimeout(spec.initializationTimeout());
        return cfg;
    }

    private List<String> javaToolRefs(List<String> toolRefs) {
        return safeRefs(toolRefs)
                .stream()
                .filter(ref -> !ref.startsWith("mcp:") && !ref.startsWith("workflow:"))
                .toList();
    }

    private List<String> workflowToolRefs(List<String> toolRefs) {
        return safeRefs(toolRefs).stream().filter(ref -> ref.startsWith("workflow:")).toList();
    }

    private Map<String, List<String>> mcpToolRefs(List<String> toolRefs) {
        Map<String, LinkedHashSet<String>> refs = new LinkedHashMap<>();
        for (String toolRef : safeRefs(toolRefs)) {
            if (!toolRef.startsWith("mcp:")) {
                continue;
            }
            String[] parts = toolRef.split(":", 3);
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
                log.warn("Invalid MCP tool ref {} ignored", toolRef);
                continue;
            }
            refs.computeIfAbsent(parts[1], ignored -> new LinkedHashSet<>()).add(parts[2]);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        refs.forEach((serverId, tools) -> result.put(serverId, List.copyOf(tools)));
        return result;
    }

    private List<String> resolveMcpToolFilter(
            List<String> serverToolFilter, List<String> agentToolFilter) {
        List<String> serverFilter = safeRefs(serverToolFilter);
        List<String> agentFilter = safeRefs(agentToolFilter);
        if (agentFilter.isEmpty()) {
            return serverFilter;
        }
        if (serverFilter.isEmpty()) {
            return agentFilter;
        }
        Set<String> allowedByServer = new LinkedHashSet<>(serverFilter);
        return agentFilter.stream().filter(allowedByServer::contains).distinct().toList();
    }

    private AgentSkillRepository skillRepo(AgentDefinition definition, SkillSpec spec) {
        return switch (spec.type()) {
            case "classpath" -> createClasspathRepo(spec);
            case "filesystem", "local", "agent", "platform" ->
                    createFilesystemRepo(definition, spec);
            default ->
                    throw new IllegalStateException(
                            "Unsupported skill repository type '"
                                    + spec.type()
                                    + "' for "
                                    + spec.skillId());
        };
    }

    private AgentSkillRepository createClasspathRepo(SkillSpec spec) {
        try {
            return new ClasspathSkillRepository(spec.location());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to create classpath skill repo for " + spec.skillId(), e);
        }
    }

    private AgentSkillRepository createFilesystemRepo(AgentDefinition definition, SkillSpec spec) {
        Path base = resolveSkillLocation(definition, spec);
        try {
            Files.createDirectories(base);
            return new FileSystemSkillRepository(base, spec.writable(), spec.source());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to create filesystem skill repo for " + spec.skillId() + " at " + base,
                    e);
        }
    }

    private Path resolveSkillLocation(AgentDefinition definition, SkillSpec spec) {
        String resolved = resolve(spec.location());
        if (resolved == null || resolved.isBlank()) {
            if ("platform".equals(spec.scope()) || "global".equals(spec.scope())) {
                return storage.skillsRoot();
            }
            return definition.workspace();
        }

        Path path = Path.of(resolved);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path normalized = path.normalize();
        if ("platform".equals(spec.scope()) || "global".equals(spec.scope())) {
            Path base = storage.skillsRoot();
            if (normalized.getNameCount() == 1
                    && "skills".equalsIgnoreCase(normalized.getFileName().toString())) {
                return base;
            }
            if (normalized.getNameCount() > 1
                    && "skills".equalsIgnoreCase(normalized.getName(0).toString())) {
                normalized = normalized.subpath(1, normalized.getNameCount());
            }
            if (normalized.getNameCount() == 0) {
                return base;
            }
            Path location = base.resolve(normalized).normalize();
            if (!location.startsWith(base)) {
                throw new IllegalStateException(
                        "Invalid relative skill path for spec: " + spec.skillId());
            }
            return location;
        } else {
            return definition.workspace().resolve(normalized).normalize();
        }
    }

    private List<String> safeRefs(List<String> refs) {
        return refs == null
                ? List.of()
                : refs.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    private Path resolveToolPath(String value) {
        String resolved = resolve(value);
        return storage.resolveRelativeToWorkspace(resolved);
    }

    private String resolve(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            return environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
