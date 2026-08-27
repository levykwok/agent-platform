/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.control.PipelineStep;
import io.agent.platform.control.McpRegistry;
import io.agent.platform.control.ModelConfigRegistry;
import io.agent.platform.control.ModelProviderRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.SkillRegistry;
import io.agent.platform.control.ToolRegistry;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformRunPersistenceTest {

    @TempDir Path tempDir;

    @Test
    void runFreezesAgentVersionAndFullConfigurationSnapshot() throws Exception {
        PlatformStorageLayer storage = storage(tempDir);
        AgentDefinition definition =
                new AgentDefinition(
                        "snapshot-agent",
                        "v7",
                        "Snapshot Agent",
                        "model-a",
                        Map.of("qa", "model-a"),
                        "system",
                        true,
                        tempDir.resolve("agent"),
                        List.of("tool-a"),
                        List.of("mcp-a"),
                        List.of("skill-a"),
                        new OrchestrationPolicy(
                                OrchestrationMode.PIPELINE,
                                List.of(),
                                List.of(),
                                List.of(new PipelineStep("write", "writer", "Write"))));
        PlatformCompatibilityState state = newState(storage, definition);

        var run = state.createRun("snapshot-agent", "test", "user-1");

        assertEquals("v7", run.get("agent_version"));
        assertEquals("model-a", run.get("model_snapshot"));
        assertEquals(Map.of("qa", "model-a"), run.get("model_policy_snapshot"));
        assertTrue(((Map<?, ?>) run.get("config_snapshot")).containsKey("orchestration"));
        String serialized = new ObjectMapper().writeValueAsString(run);
        assertTrue(serialized.contains("\"mode\":\"PIPELINE\""));
        assertTrue(serialized.contains("\"pipeline\""));
        assertFalse(serialized.contains("\"workflow\""));
        assertEquals(
                1L,
                state.orchestrationMetrics("snapshot-agent", "user-1").get("pipeline_runs"));
    }

    @Test
    void orchestrationMetricsUseLabelsAndPersistedDecisionEvents() throws Exception {
        PlatformCompatibilityState state = newState(storage(tempDir));
        var run = state.createRun("metrics-agent", "test", "user-1");
        String runId = String.valueOf(run.get("run_id"));
        state.appendRunEvent(
                runId,
                "router_decision",
                Map.of("decision_source", "llm", "duration_ms", 10));
        state.appendRunEvent(
                runId,
                "supervisor_plan",
                Map.of("decision_source", "fallback", "duration_ms", 20));
        state.appendRunEvent(
                runId,
                "supervisor_revise",
                Map.of("decision_source", "llm", "duration_ms", 30));
        state.appendRunEvent(runId, "supervisor_subagent_result", Map.of());
        state.appendRunEvent(runId, "supervisor_subagent_result", Map.of());
        state.appendRunEvent(
                runId,
                "pipeline_parallel_end",
                Map.of("parallel_group", "gather", "duration_ms", 15));
        state.appendAuditEvent(
                "llm.call",
                "metrics-agent",
                Map.of(
                        "root_task_id", runId,
                        "agent_id", "metrics-agent",
                        "configured_model", "test-model",
                        "input_tokens", 40,
                        "output_tokens", 10,
                        "total_tokens", 50));
        state.recordOrchestrationEvaluation(runId, "router", true, "correct route");

        Map<String, Object> metrics = state.orchestrationMetrics("metrics-agent", "user-1");

        assertEquals(1.0D, metrics.get("router_accuracy"));
        assertEquals(0.5D, metrics.get("supervisor_fallback_rate"));
        assertEquals(2.0D, metrics.get("supervisor_average_agent_calls"));
        assertEquals(1L, metrics.get("pipeline_parallel_groups"));
        assertEquals(15L, metrics.get("pipeline_parallel_p95_ms"));
        assertEquals(30L, metrics.get("decision_p95_ms"));
        Map<?, ?> timingSummary = (Map<?, ?>) state.runTiming(runId).get("summary");
        assertEquals(1, timingSummary.get("model_calls"));
        assertEquals(50L, timingSummary.get("total_tokens"));
    }

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

    @Test
    void sessionAttachmentsSurviveStateReload() throws Exception {
        PlatformStorageLayer storage = storage(tempDir);
        PlatformCompatibilityState first = newState(storage);

        var attachment =
                first.attachDocument(
                        "session-1",
                        java.util.Map.of(
                                "doc_id", "doc-1",
                                "version_id", "v1",
                                "filename", "notes.md",
                                "parse_status", "parsed"),
                        "org-1",
                        "user-1");

        PlatformCompatibilityState reloaded = newState(storage);

        assertEquals(1, reloaded.attachments("session-1", "user-1", "org-1").size());
        assertEquals(
                        attachment.get("attachment_id"),
                reloaded
                        .attachments("session-1", "user-1", "org-1")
                        .get(0)
                        .get("attachment_id"));
    }

    @Test
    void sessionOwnershipIsCheckedAcrossUsersAndOrganizations() {
        PlatformStorageLayer storage = storage(tempDir);
        PlatformWorkspaceSessionStore sessions = new PlatformWorkspaceSessionStore(storage);
        sessions.create(
                java.util.Map.of(
                        "agent_id", "agent-a",
                        "session_id", "session-owned",
                        "title", "owned"),
                "org-a",
                "user-a");

        assertTrue(sessions.ownedBy("session-owned", "org-a", "user-a"));
        assertFalse(sessions.ownedBy("session-owned", "org-a", "user-b"));
        assertFalse(sessions.ownedBy("session-owned", "org-b", "user-a"));
        assertFalse(sessions.ownedBy("missing", "org-a", "user-a"));
    }

    @Test
    void orchestrationModelSlotsUseModeSpecificThenSharedThenQaFallback() throws Exception {
        PlatformCompatibilityState state = newState(storage(tempDir));

        assertTrue(
                state.slots().stream()
                        .map(row -> String.valueOf(row.get("slot_key")))
                        .toList()
                        .containsAll(
                                List.of(
                                        "orchestration",
                                        "router_decision",
                                        "supervisor_decision")));

        state.bindSlot("qa", java.util.Map.of("model_id", "qa-model"));
        assertEquals("qa-model", state.defaultOrchestrationModelId("SUPERVISOR"));

        state.bindSlot("orchestration", java.util.Map.of("model_id", "fast-shared"));
        assertEquals("fast-shared", state.defaultOrchestrationModelId("ROUTER"));
        assertEquals("fast-shared", state.defaultOrchestrationModelId("SUPERVISOR"));

        state.bindSlot("supervisor_decision", java.util.Map.of("model_id", "fast-supervisor"));
        assertEquals("fast-supervisor", state.defaultOrchestrationModelId("supervisor"));
        assertEquals("fast-shared", state.defaultOrchestrationModelId("router"));
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
        return newState(storage, null);
    }

    private static PlatformCompatibilityState newState(
            PlatformStorageLayer storage, AgentDefinition definition) throws Exception {
        AgentDefinitionRegistry agents = mock(AgentDefinitionRegistry.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        McpRegistry mcps = mock(McpRegistry.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        ModelConfigRegistry models = mock(ModelConfigRegistry.class);
        ModelProviderRegistry providers = mock(ModelProviderRegistry.class);
        PlatformWorkspaceSessionStore sessions = mock(PlatformWorkspaceSessionStore.class);
        McpToolDiscoveryService discovery = mock(McpToolDiscoveryService.class);
        SkillSandboxSmokeTestService smoke = mock(SkillSandboxSmokeTestService.class);
        when(agents.allPublished()).thenReturn(definition == null ? List.of() : List.of(definition));
        when(agents.findPublished(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        invocation ->
                                definition != null
                                                && definition.agentId().equals(invocation.getArgument(0))
                                        ? java.util.Optional.of(definition)
                                        : java.util.Optional.empty());
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
