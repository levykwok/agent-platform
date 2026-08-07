/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.control.RouteRule;
import io.agent.platform.control.WorkflowStep;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NestedOrchestrationTest {

    private final AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
    private final AgentScopeHarnessFactory factory = mock(AgentScopeHarnessFactory.class);
    private final PlatformCompatibilityState platformState = mock(PlatformCompatibilityState.class);
    private final Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, HarnessAgent> agents = new LinkedHashMap<>();
    private AgentRuntimeService runtime;

    @BeforeEach
    void setUp() {
        when(registry.findPublished(anyString()))
                .thenAnswer(
                        invocation ->
                                Optional.ofNullable(definitions.get(invocation.getArgument(0))));
        when(factory.create(any(AgentDefinition.class)))
                .thenAnswer(
                        invocation ->
                                agents.get(
                                        ((AgentDefinition) invocation.getArgument(0)).agentId()));
        runtime = new AgentRuntimeService(registry, factory, platformState);
    }

    @Test
    void routerCanTargetWorkflow() {
        addSingle("researcher", "research result");
        addSingle("writer", "final answer");
        addWorkflow(
                "research-flow",
                List.of(
                        new WorkflowStep("research", "researcher", "research"),
                        new WorkflowStep("write", "writer", "write")));
        addRouter(
                "entry",
                List.of(new RouteRule("to_flow", "research-flow", "", List.of("go"), false)));

        ChatResponse response = runtime.chat("entry", request("go")).block();

        assertEquals("final answer", response.text());
    }

    @Test
    void routerDefaultCanTargetWorkflow() {
        addSingle("writer", "default final answer");
        addWorkflow("default-flow", List.of(new WorkflowStep("write", "writer", "write")));
        addRouter("entry", List.of(new RouteRule("default", "default-flow", "", List.of(), true)));

        ChatResponse response = runtime.chat("entry", request("unmatched")).block();

        assertEquals("default final answer", response.text());
    }

    @Test
    void workflowStepCanTargetRouter() {
        addSingle("leaf-a", "router result");
        addSingle("leaf-b", "unused");
        addRouter(
                "step-router",
                List.of(new RouteRule("to_leaf", "leaf-a", "", List.of("go"), false)));
        addWorkflow("outer-flow", List.of(new WorkflowStep("route", "step-router", "route")));

        ChatResponse response = runtime.chat("outer-flow", request("go")).block();

        assertEquals("router result", response.text());
    }

    @Test
    void workflowStepCanTargetAnotherWorkflow() {
        addSingle("inner-agent", "inner result");
        addSingle("outer-agent", "outer result");
        addWorkflow("inner-flow", List.of(new WorkflowStep("inner", "inner-agent", "inner")));
        addWorkflow(
                "outer-flow",
                List.of(
                        new WorkflowStep("call-inner", "inner-flow", "call inner"),
                        new WorkflowStep("outer", "outer-agent", "finish")));

        ChatResponse response = runtime.chat("outer-flow", request("go")).block();

        assertEquals("outer result", response.text());
    }

    private void addSingle(String id, String output) {
        addDefinition(id, OrchestrationPolicy.single());
        HarnessAgent agent = mock(HarnessAgent.class);
        doReturn(MonoFactory.message(output))
                .when(agent)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put(id, agent);
    }

    private void addWorkflow(String id, List<WorkflowStep> steps) {
        addDefinition(
                id,
                new OrchestrationPolicy(OrchestrationMode.WORKFLOW, List.of(), List.of(), steps));
    }

    private void addRouter(String id, List<RouteRule> routes) {
        addDefinition(
                id,
                new OrchestrationPolicy(OrchestrationMode.ROUTER, List.of(), routes, List.of()));
    }

    private void addDefinition(String id, OrchestrationPolicy orchestration) {
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
                        Path.of("target", "nested-workflow", id),
                        List.of(),
                        List.of(),
                        List.of(),
                        orchestration));
    }

    private ChatRequest request(String message) {
        return new ChatRequest("tenant", "user", "session", message);
    }

    private static final class MonoFactory {
        private static reactor.core.publisher.Mono<Msg> message(String text) {
            return reactor.core.publisher.Mono.just(
                    Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build());
        }
    }
}
