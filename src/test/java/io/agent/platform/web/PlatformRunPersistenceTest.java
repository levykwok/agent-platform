/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.McpRegistry;
import io.agent.platform.control.ModelConfigRegistry;
import io.agent.platform.control.ModelProviderRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.SkillRegistry;
import io.agent.platform.control.ToolRegistry;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformRunPersistenceTest {

    @TempDir Path tempDir;

    @Test
    void runEventsStepsAndWaitingSurviveStateReload() throws Exception {
        PlatformStorageLayer storage = storage(tempDir);
        PlatformCompatibilityState first = newState(storage);

        var run = first.createRun("research-flow", "review this", "user-1");
        String runId = String.valueOf(run.get("run_id"));
        first.appendRunEvent(runId, "workflow.step.started", java.util.Map.of("step_id", "research"));
        var waiting = first.createWaiting(runId, java.util.Map.of("prompt", "Approve the result"));
        first.resumeWaiting(runId, String.valueOf(waiting.get("waiting_id")), java.util.Map.of("answer", "yes"));

        PlatformCompatibilityState reloaded = newState(storage);

        assertEquals("running", reloaded.run(runId).get("status"));
        assertFalse(reloaded.runSteps(runId).isEmpty());
        assertEquals(4, reloaded.runEvents(runId).size());
        assertEquals("resumed", reloaded.waiting(runId).get("status"));
        assertEquals("yes", reloaded.waiting(runId).get("answer"));
    }

    private static PlatformStorageLayer storage(Path workspace) {
        return new PlatformStorageLayer(
                workspace.toString(),
                "sqlite",
                "jdbc:sqlite:" + workspace.resolve("platform-test.db"),
                "platform_config",
                "platform_",
                "");
    }

    private static PlatformCompatibilityState newState(PlatformStorageLayer storage)
            throws Exception {
        AgentDefinitionRegistry agents = mock(AgentDefinitionRegistry.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        McpRegistry mcps = mock(McpRegistry.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        ModelConfigRegistry models = mock(ModelConfigRegistry.class);
        ModelProviderRegistry providers = mock(ModelProviderRegistry.class);
        PlatformWorkspaceSessionStore sessions = mock(PlatformWorkspaceSessionStore.class);
        McpToolDiscoveryService discovery = mock(McpToolDiscoveryService.class);
        SkillSandboxSmokeTestService smoke = mock(SkillSandboxSmokeTestService.class);
        when(agents.allPublished()).thenReturn(List.of());
        when(tools.all()).thenReturn(List.of());
        when(mcps.all()).thenReturn(List.of());
        when(skills.all()).thenReturn(List.of());
        when(models.all()).thenReturn(List.of());
        when(providers.all()).thenReturn(List.of());

        PlatformCompatibilityState state =
                new PlatformCompatibilityState(
                        agents,
                        tools,
                        mcps,
                        skills,
                        models,
                        providers,
                        sessions,
                        discovery,
                        smoke,
                        storage);
        Method init = PlatformCompatibilityState.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(state);
        return state;
    }
}
