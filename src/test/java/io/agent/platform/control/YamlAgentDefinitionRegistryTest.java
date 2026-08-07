/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class YamlAgentDefinitionRegistryTest {

    @Test
    void reloadPreservesWorkflowExecutionPolicyAndTransitions() throws Exception {
        PlatformConfigStore configStore = mock(PlatformConfigStore.class);
        Environment environment = mock(Environment.class);
        PlatformStorageLayer storage = mock(PlatformStorageLayer.class);
        when(environment.resolveRequiredPlaceholders(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.agentDefinitionWorkspace(anyString()))
                .thenAnswer(invocation -> Path.of("target", "registry-test", invocation.getArgument(0)));

        WorkflowStep research =
                new WorkflowStep(
                        "research",
                        "researcher",
                        "Research the request",
                        1_200L,
                        2,
                        WorkflowStep.FailurePolicy.USE_INPUT,
                        List.of(
                                new WorkflowTransition("needs_review", "reviewer"),
                                new WorkflowTransition("", "writer", true)));
        OrchestrationPolicy workflow =
                new OrchestrationPolicy(
                        OrchestrationMode.WORKFLOW,
                        List.of(),
                        List.of(),
                        List.of(
                                research,
                                new WorkflowStep("reviewer", "reviewer", "Review"),
                                new WorkflowStep("writer", "writer", "Write")));
        YamlAgentDefinitionRegistry.AgentConfig flow =
                new YamlAgentDefinitionRegistry.AgentConfig(
                        "research-flow",
                        "v1",
                        "Research flow",
                        "",
                        java.util.Map.of(),
                        "",
                        true,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        workflow);
        YamlAgentDefinitionRegistry.AgentsConfig config =
                new YamlAgentDefinitionRegistry.AgentsConfig(
                        List.of(agent("researcher"), agent("reviewer"), agent("writer"), flow));
        when(configStore.read(
                        any(PlatformConfigStore.ConfigFile.class),
                        any(Class.class)))
                .thenReturn(config);

        YamlAgentDefinitionRegistry registry =
                new YamlAgentDefinitionRegistry(configStore, environment, storage);
        registry.load();

        WorkflowStep loaded =
                registry.findPublished("research-flow")
                        .orElseThrow()
                        .orchestration()
                        .workflow()
                        .get(0);
        assertEquals(1_200L, loaded.timeoutMs());
        assertEquals(2, loaded.maxRetries());
        assertEquals(WorkflowStep.FailurePolicy.USE_INPUT, loaded.failurePolicy());
        assertEquals(2, loaded.transitions().size());
        assertEquals("reviewer", loaded.transitions().get(0).nextStepId());
        assertEquals("writer", loaded.transitions().get(1).nextStepId());
        assertEquals(true, loaded.transitions().get(1).defaultTransition());
    }

    private static YamlAgentDefinitionRegistry.AgentConfig agent(String id) {
        return new YamlAgentDefinitionRegistry.AgentConfig(
                id,
                "v1",
                id,
                "",
                java.util.Map.of(),
                "",
                true,
                null,
                List.of(),
                List.of(),
                List.of(),
                OrchestrationPolicy.single());
    }
}
