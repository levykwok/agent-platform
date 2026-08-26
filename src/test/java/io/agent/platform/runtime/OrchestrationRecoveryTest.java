/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.control.PipelineStep;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.SubagentBinding;
import io.agent.platform.runtime.protocol.TaskContext;
import io.agent.platform.web.PlatformCompatibilityState;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class OrchestrationRecoveryTest {

    @TempDir Path tempDir;

    @Test
    void pipelineRecoveryDoesNotRepeatCommittedStep() {
        Fixture fixture = new Fixture(tempDir);
        HarnessAgent first = fixture.single("first", "first-result");
        HarnessAgent failing = mock(HarnessAgent.class);
        doReturn(Mono.error(new IllegalStateException("simulated crash")))
                .when(failing)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        fixture.agents.put("second", failing);
        fixture.definition("second", OrchestrationPolicy.single());
        fixture.definition(
                "pipeline",
                new OrchestrationPolicy(
                        OrchestrationMode.PIPELINE,
                        List.of(),
                        List.of(),
                        List.of(
                                new PipelineStep("first-step", "first", "first"),
                                new PipelineStep("second-step", "second", "second"))));
        ChatRequest request = fixture.request("run-pipeline", "pipeline", "start");
        fixture.register("run-pipeline", "pipeline");

        assertThrows(
                Throwable.class,
                () -> fixture.runtime(fixture.decisionModel).chat("pipeline", request).block());

        HarnessAgent recoveredSecond = fixture.single("second", "second-result");
        ChatResponse recovered =
                fixture.runtime(fixture.decisionModel).chat("pipeline", request).block();

        assertEquals("second-result", recovered.text());
        verify(first, times(1)).call(any(UserMessage.class), any(RuntimeContext.class));
        verify(failing, times(1)).call(any(UserMessage.class), any(RuntimeContext.class));
        verify(recoveredSecond, times(1))
                .call(any(UserMessage.class), any(RuntimeContext.class));
    }

    @Test
    void supervisorRecoveryDoesNotRepeatCommittedSubagentBatch() {
        Fixture fixture = new Fixture(tempDir);
        HarnessAgent researcher = fixture.single("researcher", "facts");
        HarnessAgent writer = fixture.single("writer", "draft");
        HarnessAgent supervisor = fixture.single("supervisor", "final answer");
        fixture.definition(
                "supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(
                                new SubagentBinding(
                                        "research",
                                        "researcher",
                                        "research",
                                        "",
                                        true,
                                        List.of()),
                                new SubagentBinding(
                                        "write", "writer", "write", "", true, List.of())),
                        List.of(),
                        List.of(),
                        4));
        OrchestrationDecisionModel planning = mock(OrchestrationDecisionModel.class);
        when(planning.decide(any(AgentDefinition.class), anyString()))
                .thenReturn(
                        decision(
                                "{\"steps\":[{\"binding_id\":\"research\",\"instruction\":\"collect facts\"},{\"binding_id\":\"write\",\"instruction\":\"write draft\"}],\"reason\":\"two steps\"}"));
        ChatRequest request = fixture.request("run-supervisor", "supervisor", "prepare answer");
        fixture.register("run-supervisor", "supervisor");

        fixture.runtime(planning)
                .stream("supervisor", request)
                .takeUntil(event -> "supervisor_subagent_result".equals(event.type()))
                .collectList()
                .block();

        OrchestrationDecisionModel recoveryDecisions = mock(OrchestrationDecisionModel.class);
        when(recoveryDecisions.decide(any(AgentDefinition.class), anyString()))
                .thenReturn(
                        decision(
                                "{\"action\":\"NEXT\",\"next_step\":{\"binding_id\":\"write\",\"instruction\":\"write draft\"},\"reason\":\"continue\"}"),
                        decision("{\"action\":\"FINISH\",\"reason\":\"done\"}"));

        ChatResponse recovered =
                fixture.runtime(recoveryDecisions).chat("supervisor", request).block();

        assertEquals("final answer", recovered.text());
        verify(researcher, times(1)).call(any(UserMessage.class), any(RuntimeContext.class));
        verify(writer, times(1)).call(any(UserMessage.class), any(RuntimeContext.class));
        verify(supervisor, times(1)).call(any(UserMessage.class), any(RuntimeContext.class));
        verify(planning, times(1)).decide(any(AgentDefinition.class), anyString());
        verify(recoveryDecisions, times(2))
                .decide(any(AgentDefinition.class), anyString());
    }

    private static Mono<OrchestrationDecisionModel.DecisionResponse> decision(String text) {
        return Mono.just(new OrchestrationDecisionModel.DecisionResponse(text, "test-model", 1L));
    }

    private static final class Fixture {
        private final AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
        private final AgentScopeHarnessFactory factory = mock(AgentScopeHarnessFactory.class);
        private final PlatformCompatibilityState state = mock(PlatformCompatibilityState.class);
        private final OrchestrationDecisionModel decisionModel = mock(OrchestrationDecisionModel.class);
        private final Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
        private final Map<String, HarnessAgent> agents = new LinkedHashMap<>();
        private final OrchestrationCheckpointStore checkpoints;

        private Fixture(Path workspace) {
            PlatformStorageLayer storage =
                    new PlatformStorageLayer(
                            workspace.toString(),
                            "sqlite",
                            "jdbc:sqlite:" + workspace.resolve("recovery.db"),
                            "platform_config",
                            "platform_",
                            "");
            checkpoints = new OrchestrationCheckpointStore(storage, "test-instance", 30_000);
            checkpoints.initialize();
            when(registry.findPublished(anyString()))
                    .thenAnswer(
                            call -> Optional.ofNullable(definitions.get(call.getArgument(0))));
            when(factory.create(any(AgentDefinition.class)))
                    .thenAnswer(
                            call ->
                                    agents.get(
                                            ((AgentDefinition) call.getArgument(0)).agentId()));
        }

        private AgentRuntimeService runtime(OrchestrationDecisionModel decisions) {
            return new AgentRuntimeService(registry, factory, state, decisions, checkpoints);
        }

        private void register(String runId, String agentId) {
            checkpoints.register(runId, Map.of("agent_id", agentId));
            checkpoints.acquire(runId);
        }

        private HarnessAgent single(String id, String output) {
            definition(id, OrchestrationPolicy.single());
            HarnessAgent agent = mock(HarnessAgent.class);
            doReturn(
                            Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent(output)
                                            .build()))
                    .when(agent)
                    .call(any(UserMessage.class), any(RuntimeContext.class));
            agents.put(id, agent);
            return agent;
        }

        private void definition(String id, OrchestrationPolicy orchestration) {
            definitions.put(
                    id,
                    new AgentDefinition(
                            id,
                            "v1",
                            id,
                            "",
                            Map.of(),
                            "",
                            true,
                            Path.of("target", "recovery", id),
                            List.of(),
                            List.of(),
                            List.of(),
                            orchestration));
        }

        private ChatRequest request(String runId, String agentId, String message) {
            return new ChatRequest(
                    "tenant",
                    "user",
                    "session",
                    message,
                    TaskContext.root(runId, agentId, agentId, null));
        }
    }
}
