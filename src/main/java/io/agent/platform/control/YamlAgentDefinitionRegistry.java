/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
                                                s.toolRefs()))
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
        List<WorkflowStep> workflow =
                policy.workflow().stream()
                        .map(
                                s ->
                                        new WorkflowStep(
                                                resolve(s.stepId()),
                                                resolve(s.agentId()),
                                                resolve(s.instruction()),
                                                s.timeoutMs(),
                                                s.maxRetries(),
                                                s.failurePolicy(),
                                                s.transitions().stream()
                                                        .map(
                                                                transition ->
                                                                        new WorkflowTransition(
                                                                                resolve(
                                                                                        transition
                                                                                                .when()),
                                                                                resolve(
                                                                                        transition
                                                                                                .nextStepId()),
                                                                                transition
                                                                                        .defaultTransition()))
                                                        .toList()))
                        .toList();
        List<WorkflowNode> nodes = policy.nodes().stream().map(this::resolveNode).toList();
        return new OrchestrationPolicy(policy.mode(), subagents, routes, workflow, nodes, policy.edges());
    }

    private WorkflowNode resolveNode(WorkflowNode node) {
        return new WorkflowNode(
                resolve(node.nodeId()),
                node.type(),
                resolve(node.refId()),
                resolve(node.instruction()),
                node.config(),
                node.inputMapping(),
                node.outputSchema(),
                node.timeoutMs(),
                node.maxRetries(),
                node.failurePolicy(),
                node.transitions().stream().map(this::resolveTransition).toList(),
                node.inputPorts(),
                node.outputPorts());
    }

    private WorkflowTransition resolveTransition(WorkflowTransition transition) {
        return new WorkflowTransition(
                resolve(transition.when()),
                resolve(transition.nextStepId()),
                transition.defaultTransition());
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
                case WORKFLOW -> validateWorkflow(definition, loaded);
                case SUPERVISOR, SINGLE -> validateSupervisor(definition, loaded);
                default ->
                        throw new IllegalStateException(
                        "Unsupported orchestration mode: " + orchestration.mode());
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

    private void validateWorkflow(AgentDefinition definition, Map<String, AgentDefinition> loaded) {
        List<WorkflowNode> nodes = definition.orchestration().workflowNodes();
        if (nodes.isEmpty()) {
            throw new IllegalStateException(
                    "WORKFLOW agent requires at least one workflow step: " + definition.agentId());
        }
        Set<String> stepIds = new LinkedHashSet<>();
        Set<String> allStepIds = stepIdsFor(definition);
        for (WorkflowNode node : nodes) {
            if (node.nodeId() == null || node.nodeId().isBlank()) {
                throw new IllegalStateException(
                        "Workflow stepId is blank for agent " + definition.agentId());
            }
            if (!stepIds.add(node.nodeId())) {
                throw new IllegalStateException(
                        "Duplicate workflow stepId "
                                + node.nodeId()
                                + " in agent "
                                + definition.agentId());
            }
            if (node.type() == WorkflowNodeType.AGENT_INVOKE
                    || node.type() == WorkflowNodeType.SUBFLOW_INVOKE) {
                if (node.refId() == null || node.refId().isBlank()) {
                    throw new IllegalStateException(
                            "Workflow refId is blank in node "
                                    + node.nodeId()
                                    + " for agent "
                                    + definition.agentId());
                }
                if (!loaded.containsKey(node.refId())) {
                    throw new IllegalStateException(
                            "Workflow node target not found for agent "
                                    + definition.agentId()
                                    + ": "
                                    + node.refId());
                }
            }
            for (WorkflowTransition transition : node.transitions()) {
                if (transition.nextStepId().isBlank()
                        || !allStepIds.contains(transition.nextStepId())) {
                    throw new IllegalStateException(
                            "Workflow transition target not found in step " + node.nodeId());
                }
                int targetIndex =
                        nodes.stream()
                                .map(WorkflowNode::nodeId)
                                .toList()
                                .indexOf(transition.nextStepId());
                int currentIndex = nodes.indexOf(node);
                if (targetIndex <= currentIndex) {
                    throw new IllegalStateException(
                            "Workflow transition must move forward from step " + node.nodeId());
                }
            }
            if (node.timeoutMs() != null && node.timeoutMs() <= 0) {
                throw new IllegalStateException(
                        "Workflow timeoutMs must be positive in step " + node.nodeId());
            }
            if (node.maxRetries() != null && node.maxRetries() < 0) {
                throw new IllegalStateException(
                        "Workflow maxRetries must not be negative in step " + node.nodeId());
            }
        }
    }

    private Set<String> stepIdsFor(AgentDefinition definition) {
        return definition.orchestration().workflowNodes().stream()
                .map(WorkflowNode::nodeId)
                .collect(java.util.stream.Collectors.toSet());
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
