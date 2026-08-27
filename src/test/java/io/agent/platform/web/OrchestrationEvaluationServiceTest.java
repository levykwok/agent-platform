/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.runtime.AgentEventEnvelope;
import io.agent.platform.runtime.AgentRuntime;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class OrchestrationEvaluationServiceTest {

    @TempDir Path tempDir;

    @Test
    void judgeValidatesRouterSupervisorAndPipelineContracts() {
        List<AgentEventEnvelope> events =
                List.of(
                        event(
                                "router_decision",
                                null,
                                Map.of(
                                        "route_id",
                                        "to-research",
                                        "target_agent_id",
                                        "researcher")),
                        event(
                                "supervisor_subagent_result",
                                null,
                                Map.of("binding_id", "research", "fallback_used", false)),
                        event(
                                "supervisor_subagent_result",
                                null,
                                Map.of("binding_id", "write", "fallback_used", false)),
                        event(
                                "pipeline_step_end",
                                null,
                                Map.of("step_id", "research")),
                        event(
                                "pipeline_parallel_end",
                                null,
                                Map.of("parallel_group", "gather")),
                        event(
                                "pipeline_result", "final answer", Map.of("step_id", "write")));
        Map<String, Object> expected =
                Map.of(
                        "route_id",
                        "to-research",
                        "target_agent_id",
                        "researcher",
                        "binding_ids",
                        List.of("research", "write"),
                        "child_call_count",
                        2,
                        "allow_fallback",
                        false,
                        "pipeline_step_ids",
                        List.of("research", "write"),
                        "pipeline_parallel_groups",
                        List.of("gather"),
                        "output_contains",
                        List.of("final answer"),
                        "max_duration_ms",
                        1_000);

        Map<String, Object> result =
                OrchestrationEvaluationService.judgeCase(
                        1,
                        Map.of("name", "full trace", "input", "test"),
                        expected,
                        events,
                        50);

        assertTrue(Boolean.TRUE.equals(result.get("passed")));
        assertTrue(((List<?>) result.get("failures")).isEmpty());
    }

    @Test
    void failedAssertionsExplainObservedContractMismatch() {
        Map<String, Object> result =
                OrchestrationEvaluationService.judgeCase(
                        1,
                        Map.of("input", "test"),
                        Map.of(
                                "route_id",
                                "expected",
                                "binding_ids",
                                List.of("writer"),
                                "pipeline_step_ids",
                                List.of("one", "two"),
                                "pipeline_parallel_groups",
                                List.of("gather")),
                        List.of(event("router_decision", null, Map.of("route_id", "actual"))),
                        10);

        assertFalse(Boolean.TRUE.equals(result.get("passed")));
        String failures = String.valueOf(result.get("failures"));
        assertTrue(failures.contains("route_id expected expected but was actual"));
        assertTrue(failures.contains("binding_ids"));
        assertTrue(failures.contains("pipeline_step_ids"));
        assertTrue(failures.contains("pipeline_parallel_groups"));
    }

    @Test
    void realEvaluationRunIsPersistedAndTenantScoped() {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("evaluations.db"),
                        "platform_config",
                        "platform_",
                        "");
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.stream(eq("router"), any()))
                .thenReturn(
                        Flux.just(
                                event(
                                        "router_decision",
                                        null,
                                        Map.of(
                                                "route_id",
                                                "route-a",
                                                "target_agent_id",
                                                "agent-a")),
                                event("text_block_delta", "ok", Map.of())));
        OrchestrationEvaluationService service =
                new OrchestrationEvaluationService(storage, runtime);
        service.initialize();
        PlatformAuthService.Principal owner =
                new PlatformAuthService.Principal(
                        "user-a", "a@example.com", "A", "org-a", "ORG_MEMBER");

        Map<String, Object> evaluation =
                service.run(
                                "router",
                                owner,
                                Map.of(
                                        "name",
                                        "router suite",
                                        "cases",
                                        List.of(
                                                Map.of(
                                                        "input",
                                                        "route this",
                                                        "expected",
                                                        Map.of(
                                                                "route_id",
                                                                "route-a",
                                                                "target_agent_id",
                                                                "agent-a",
                                                                "output_contains",
                                                                "ok")))))
                        .block();

        assertEquals("passed", evaluation.get("status"));
        assertEquals(1, service.list("router", owner, 20).size());
        PlatformAuthService.Principal other =
                new PlatformAuthService.Principal(
                        "user-b", "b@example.com", "B", "org-a", "ORG_MEMBER");
        assertTrue(service.list("router", other, 20).isEmpty());
    }

    @Test
    void synchronousRuntimeConfigurationFailureBecomesFailedCaseInsteadOfHttpFailure() {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.resolve("sync-failure").toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("sync-failure/evaluations.db"),
                        "platform_config",
                        "platform_",
                        "");
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.stream(eq("broken-agent"), any()))
                .thenThrow(new IllegalStateException("model is not configured"));
        OrchestrationEvaluationService service =
                new OrchestrationEvaluationService(storage, runtime);
        service.initialize();
        PlatformAuthService.Principal owner =
                new PlatformAuthService.Principal(
                        "user-a", "a@example.com", "A", "org-a", "ORG_MEMBER");

        Map<String, Object> evaluation =
                service.run(
                                "broken-agent",
                                owner,
                                Map.of(
                                        "cases",
                                        List.of(
                                                Map.of(
                                                        "input",
                                                        "test",
                                                        "expected",
                                                        Map.of()))))
                        .block();

        assertEquals("failed", evaluation.get("status"));
        Map<?, ?> result = (Map<?, ?>) ((List<?>) evaluation.get("results")).get(0);
        assertTrue(String.valueOf(result.get("failures")).contains("model is not configured"));
    }

    private static AgentEventEnvelope event(
            String type, String delta, Map<String, Object> payload) {
        return new AgentEventEnvelope(
                type + "-1", type, Instant.now().toString(), "test", delta, payload);
    }
}
