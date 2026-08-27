/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class YamlAgentDefinitionRegistry implements AgentDefinitionRegistry {

    private final Map<String, AgentDefinition> definitions = new ConcurrentHashMap<>();
    private final PlatformConfigStore configStore;
    private final Environment environment;
    private final PlatformStorageLayer storage;

    public YamlAgentDefinitionRegistry(
            PlatformConfigStore configStore,
            Environment environment,
            PlatformStorageLayer storage) {
        this.configStore = configStore;
        this.environment = environment;
        this.storage = storage;
    }

    @PostConstruct
    public void load() throws IOException {
        configStore.migrateLegacyAgentPipelineSchema();
        AgentsConfig config =
                configStore.read(PlatformConfigStore.ConfigFile.AGENTS, AgentsConfig.class);
        Map<String, AgentDefinition> loaded = new LinkedHashMap<>();
        for (AgentConfig agent : config.agents()) {
            AgentDefinition definition = toDefinition(agent);
            if (loaded.containsKey(definition.agentId())) {
                throw new IllegalStateException(
                        "Duplicate agentId in config: " + definition.agentId());
            }
            loaded.put(definition.agentId(), definition);
        }
        validate(loaded);
        definitions.clear();
        definitions.putAll(loaded);
    }

    @Override
    public List<AgentDefinition> allPublished() {
        return definitions.values().stream().toList();
    }

    @Override
    public Optional<AgentDefinition> findPublished(String agentId) {
        return Optional.ofNullable(definitions.get(agentId));
    }

    @Override
    public synchronized AgentDefinition upsert(AgentConfig agent) {
        try {
            AgentsConfig config =
                    configStore.read(PlatformConfigStore.ConfigFile.AGENTS, AgentsConfig.class);
            List<AgentConfig> agents =
                    new java.util.ArrayList<>(
                            config.agents().stream()
                                    .filter(existing -> !agent.agentId().equals(existing.agentId()))
                                    .toList());
            agents.add(agent);
            configStore.write(PlatformConfigStore.ConfigFile.AGENTS, new AgentsConfig(agents));
            load();
            return findPublished(agent.agentId())
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "Agent was not loaded after save: " + agent.agentId()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist agent: " + agent.agentId(), e);
        }
    }

    @Override
    public synchronized void delete(String agentId) {
        try {
            AgentsConfig config =
                    configStore.read(PlatformConfigStore.ConfigFile.AGENTS, AgentsConfig.class);
            List<AgentConfig> agents =
                    config.agents().stream()
                            .filter(existing -> !agentId.equals(existing.agentId()))
                            .toList();
            configStore.write(PlatformConfigStore.ConfigFile.AGENTS, new AgentsConfig(agents));
            load();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete agent: " + agentId, e);
        }
    }

    private AgentDefinition toDefinition(AgentConfig agent) {
        return new AgentDefinition(
                agent.agentId(),
                safe(agent.version(), "v1"),
                safe(resolve(agent.name()), agent.agentId()),
                safe(resolve(agent.model()), ""),
                agent.modelPolicy(),
                safe(resolve(agent.systemPrompt()), "You are a helpful assistant."),
                !Boolean.FALSE.equals(agent.enabled()),
                workspace(agent),
                agent.toolRefs(),
                agent.mcpRefs(),
                agent.skillRefs(),
                resolveOrchestration(agent.orchestration()));
    }

    private Path workspace(AgentConfig agent) {
        String resolved = resolve(agent.workspace());
        if (resolved == null || resolved.isBlank()) {
            return storage.agentDefinitionWorkspace(agent.agentId());
        }
        Path path = Path.of(resolved);
        return storage.resolveRelativeToWorkspace(path.toString());
    }

    public String externalWorkspace(Path workspace) {
        if (workspace == null) {
            return null;
        }
        String relative = storage.toWorkspaceRelative(workspace);
        return relative == null || relative.isBlank() ? workspace.toString() : relative;
    }

    private OrchestrationPolicy resolveOrchestration(OrchestrationPolicy policy) {
        if (policy == null) {
            return OrchestrationPolicy.single();
        }
        List<SubagentBinding> subagents =
                policy.subagents().stream()
                        .map(
                                s ->
                                        new SubagentBinding(
                                                resolve(s.bindingId()),
                                                resolve(s.targetAgentId()),
                                                resolve(s.role()),
                                                resolve(s.description()),
                                                s.exposeToUser(),
                                                s.toolRefs(),
                                                s.outputSchema(),
                                                s.timeoutMs(),
                                                s.maxRetries(),
                                                s.failurePolicy(),
                                                resolve(s.fallbackAgentId())))
                        .toList();
        List<RouteRule> routes =
                policy.routes().stream()
                        .map(
                                r ->
                                        new RouteRule(
                                                resolve(r.ruleId()),
                                                resolve(r.targetAgentId()),
                                                resolve(r.contains()),
                                                r.keywords().stream().map(this::resolve).toList(),
                                        r.defaultRoute()))
                        .toList();
        List<PipelineStep> pipeline =
                policy.pipeline().stream()
                        .map(
                                step ->
                                        new PipelineStep(
                                                resolve(step.stepId()),
                                                resolve(step.agentId()),
                                                resolve(step.instruction()),
                                                step.timeoutMs(),
                                                step.maxRetries(),
                                                step.failurePolicy(),
                                                step.transitions().stream()
                                                        .map(
                                                                transition ->
                                                                        new PipelineTransition(
                                                                                resolve(transition.when()),
                                                                                resolve(transition.nextStepId()),
                                                                                transition.defaultTransition()))
                                                        .toList(),
                                                resolve(step.parallelGroup())))
                        .toList();
        return new OrchestrationPolicy(
                policy.mode(),
                subagents,
                routes,
                pipeline,
                policy.maxSupervisorSteps(),
                policy.supervisorParallelEnabled(),
                policy.maxSupervisorParallelism(),
                policy.routerDisableThinking(),
                policy.supervisorDisableThinking(),
                policy.maxPipelineParallelism());
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolve(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            return environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Failed to resolve config placeholder: " + value, e);
        }
    }

    private void validate(Map<String, AgentDefinition> loaded) {
        for (Map.Entry<String, AgentDefinition> entry : loaded.entrySet()) {
            AgentDefinition definition = entry.getValue();
            if (definition.agentId() == null || definition.agentId().isBlank()) {
                throw new IllegalStateException("agentId cannot be blank");
            }
            OrchestrationPolicy orchestration = definition.orchestration();
            if (orchestration == null) {
                continue;
            }
            switch (orchestration.mode()) {
                case ROUTER -> validateRouter(definition, loaded);
                case SUPERVISOR, SINGLE -> validateSupervisor(definition, loaded);
                case PIPELINE -> validatePipeline(definition, loaded);
            }
        }
        OrchestrationCycleValidator.validate(loaded);
    }

    private void validateRouter(AgentDefinition definition, Map<String, AgentDefinition> loaded) {
        if (definition.orchestration().routes().isEmpty()) {
            throw new IllegalStateException(
                    "ROUTER agent requires at least one route: " + definition.agentId());
        }
        for (RouteRule route : definition.orchestration().routes()) {
            if (route.targetAgentId() == null || route.targetAgentId().isBlank()) {
                throw new IllegalStateException(
                        "Router route targetAgentId is blank for " + definition.agentId());
            }
            if (!route.defaultRoute()
                    && (route.contains() == null || route.contains().isBlank())
                    && route.keywords().isEmpty()) {
                throw new IllegalStateException(
                        "Router route requires contains or keywords for " + definition.agentId());
            }
            if (!loaded.containsKey(route.targetAgentId())) {
                throw new IllegalStateException(
                        "Router route target not found for "
                                + definition.agentId()
                                + ": "
                                + route.targetAgentId());
            }
        }
    }

    private void validateSupervisor(
            AgentDefinition definition, Map<String, AgentDefinition> loaded) {
        for (SubagentBinding binding : definition.orchestration().subagents()) {
            if (binding.bindingId() == null || binding.bindingId().isBlank()) {
                throw new IllegalStateException(
                        "Subagent binding id is blank for agent " + definition.agentId());
            }
            if (binding.targetAgentId() == null || binding.targetAgentId().isBlank()) {
                throw new IllegalStateException(
                        "Subagent targetAgentId is blank for "
                                + definition.agentId()
                                + " binding "
                                + binding.bindingId());
            }
            if (!loaded.containsKey(binding.targetAgentId())) {
                throw new IllegalStateException(
                        "Subagent target not found for "
                                + definition.agentId()
                                + " binding "
                                + binding.bindingId()
                                + ": "
                                + binding.targetAgentId());
            }
            if (binding.timeoutMs() != null && binding.timeoutMs() <= 0) {
                throw new IllegalStateException(
                        "Subagent timeout must be positive for binding " + binding.bindingId());
            }
            if (binding.maxRetries() < 0) {
                throw new IllegalStateException(
                        "Subagent maxRetries cannot be negative for binding "
                                + binding.bindingId());
            }
            if (binding.failurePolicy() == SubagentBinding.FailurePolicy.FALLBACK) {
                if (binding.fallbackAgentId().isBlank()
                        || !loaded.containsKey(binding.fallbackAgentId())) {
                    throw new IllegalStateException(
                            "Subagent fallback target not found for binding "
                                    + binding.bindingId()
                                    + ": "
                                    + binding.fallbackAgentId());
                }
            }
        }
    }

    private void validatePipeline(
            AgentDefinition definition, Map<String, AgentDefinition> loaded) {
        List<PipelineStep> steps = definition.orchestration().pipeline();
        if (steps.isEmpty()) {
            throw new IllegalStateException(
                    "PIPELINE agent requires at least one step: " + definition.agentId());
        }
        Set<String> stepIds = new java.util.LinkedHashSet<>();
        Map<String, Integer> parallelGroupCounts = new java.util.LinkedHashMap<>();
        Set<String> closedParallelGroups = new java.util.LinkedHashSet<>();
        String activeParallelGroup = "";
        for (PipelineStep step : steps) {
            if (step.stepId() == null || step.stepId().isBlank() || !stepIds.add(step.stepId())) {
                throw new IllegalStateException(
                        "Pipeline step id must be unique and non-blank for " + definition.agentId());
            }
            if (step.agentId() == null || step.agentId().isBlank() || !loaded.containsKey(step.agentId())) {
                throw new IllegalStateException(
                        "Pipeline step target not found for " + definition.agentId() + ": " + step.agentId());
            }
            if (step.timeoutMs() != null && step.timeoutMs() <= 0) {
                throw new IllegalStateException("Pipeline step timeout must be positive: " + step.stepId());
            }
            if (step.maxRetries() != null && step.maxRetries() < 0) {
                throw new IllegalStateException("Pipeline step maxRetries cannot be negative: " + step.stepId());
            }
            String parallelGroup = step.parallelGroup();
            if (!parallelGroup.isBlank()) {
                if (!step.transitions().isEmpty()) {
                    throw new IllegalStateException(
                            "Parallel Pipeline steps cannot define transitions: " + step.stepId());
                }
                if (!parallelGroup.equals(activeParallelGroup)) {
                    if (!activeParallelGroup.isBlank()) closedParallelGroups.add(activeParallelGroup);
                    if (closedParallelGroups.contains(parallelGroup)) {
                        throw new IllegalStateException(
                                "Pipeline parallel group must be contiguous: " + parallelGroup);
                    }
                    activeParallelGroup = parallelGroup;
                }
                parallelGroupCounts.merge(parallelGroup, 1, Integer::sum);
            } else if (!activeParallelGroup.isBlank()) {
                closedParallelGroups.add(activeParallelGroup);
                activeParallelGroup = "";
            }
        }
        for (Map.Entry<String, Integer> entry : parallelGroupCounts.entrySet()) {
            if (entry.getValue() < 2) {
                throw new IllegalStateException(
                        "Pipeline parallel group requires at least two adjacent steps: "
                                + entry.getKey());
            }
        }
        for (int index = 0; index < steps.size(); index++) {
            PipelineStep step = steps.get(index);
            for (PipelineTransition transition : step.transitions()) {
                if (!stepIds.contains(transition.nextStepId())) {
                    throw new IllegalStateException(
                            "Pipeline transition target not found: " + transition.nextStepId());
                }
                int targetIndex = steps.stream()
                        .map(PipelineStep::stepId)
                        .toList()
                        .indexOf(transition.nextStepId());
                PipelineStep target = steps.get(targetIndex);
                if (!target.parallelGroup().isBlank()
                        && targetIndex > 0
                        && target.parallelGroup().equals(steps.get(targetIndex - 1).parallelGroup())) {
                    throw new IllegalStateException(
                            "Pipeline transition must target the first step of parallel group: "
                                    + transition.nextStepId());
                }
                if (steps.indexOf(step) >= steps.stream()
                        .map(PipelineStep::stepId)
                        .toList()
                        .indexOf(transition.nextStepId())) {
                    throw new IllegalStateException(
                            "Pipeline transition must point forward: " + step.stepId());
                }
            }
        }
    }

    public record AgentsConfig(List<AgentConfig> agents) {
        public AgentsConfig {
            agents = agents == null ? List.of() : List.copyOf(agents);
        }
    }

    public record AgentConfig(
            String agentId,
            String version,
            String name,
            String model,
            Map<String, Object> modelPolicy,
            String systemPrompt,
            Boolean enabled,
            String workspace,
            List<String> toolRefs,
            List<String> mcpRefs,
            List<String> skillRefs,
            OrchestrationPolicy orchestration) {

        public AgentConfig {
            modelPolicy = modelPolicy == null ? Map.of() : Map.copyOf(modelPolicy);
            toolRefs = toolRefs == null ? List.of() : List.copyOf(toolRefs);
            mcpRefs = mcpRefs == null ? List.of() : List.copyOf(mcpRefs);
            skillRefs = skillRefs == null ? List.of() : List.copyOf(skillRefs);
            orchestration = orchestration == null ? OrchestrationPolicy.single() : orchestration;
        }
    }
}
