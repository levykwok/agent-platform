/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.control.RouteRule;
import io.agent.platform.control.SubagentBinding;
import io.agent.platform.runtime.protocol.TaskContext;
import io.agent.platform.runtime.protocol.TaskStatus;
import io.agent.platform.control.PipelineStep;
import io.agent.platform.web.PlatformCompatibilityState;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NestedOrchestrationTest {

    private final AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
    private final AgentScopeHarnessFactory factory = mock(AgentScopeHarnessFactory.class);
    private final PlatformCompatibilityState platformState = mock(PlatformCompatibilityState.class);
    private final OrchestrationDecisionModel decisionModel = mock(OrchestrationDecisionModel.class);
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
        when(decisionModel.decide(any(AgentDefinition.class), anyString()))
                .thenAnswer(
                        invocation -> {
                            AgentDefinition definition = invocation.getArgument(0);
                            if (definition.orchestration().mode() == OrchestrationMode.ROUTER) {
                                String routeId = definition.orchestration().routes().get(0).ruleId();
                                return decision("{\"route_id\":\"" + routeId + "\",\"reason\":\"test\"}");
                            }
                            String bindingIds =
                                    definition.orchestration().subagents().stream()
                                            .map(binding -> "\"" + binding.bindingId() + "\"")
                                            .collect(java.util.stream.Collectors.joining(","));
                            return decision(
                                    "{\"binding_ids\":["
                                            + bindingIds
                                            + "],\"reason\":\"test\"}");
                        });
        runtime = new AgentRuntimeService(registry, factory, platformState, decisionModel);
    }

    @Test
    void routerCanTargetWorkflow() {
        addSingle("researcher", "research result");
        addSingle("writer", "final answer");
        addPipeline(
                "research-flow",
                List.of(
                        new PipelineStep("research", "researcher", "research"),
                        new PipelineStep("write", "writer", "write")));
        addRouter(
                "entry",
                List.of(new RouteRule("to_flow", "research-flow", "", List.of("go"), false)));

        ChatResponse response = runtime.chat("entry", request("go")).block();

        assertEquals("final answer", response.text());
    }

    @Test
    void routerDefaultCanTargetWorkflow() {
        addSingle("writer", "default final answer");
        addPipeline("default-flow", List.of(new PipelineStep("write", "writer", "write")));
        addRouter("entry", List.of(new RouteRule("default", "default-flow", "", List.of(), true)));

        ChatResponse response = runtime.chat("entry", request("unmatched")).block();

        assertEquals("default final answer", response.text());
    }

    @Test
    void routerUsesLlmSemanticDecisionInsteadOfKeywordMatching() {
        addSingle("leaf-a", "first result");
        addSingle("leaf-b", "semantic result");
        addRouter(
                "semantic-router",
                List.of(
                        new RouteRule("route-a", "leaf-a", "alpha", List.of("alpha"), false),
                        new RouteRule("route-b", "leaf-b", "beta", List.of("beta"), false)));
        when(decisionModel.decide(
                        org.mockito.ArgumentMatchers.argThat(
                                item -> item.agentId().equals("semantic-router")),
                        anyString()))
                .thenReturn(
                        decision(
                                "{\"route_id\":\"route-b\",\"reason\":\"semantic intent\"}"));

        ChatResponse response =
                runtime.chat("semantic-router", request("request without configured keywords")).block();

        assertEquals("semantic result", response.text());
    }

    @Test
    void routerFallsBackToConfiguredDefaultWhenLlmOutputIsInvalid() {
        addSingle("leaf-a", "first result");
        addSingle("leaf-default", "fallback result");
        addRouter(
                "fallback-router",
                List.of(
                        new RouteRule("route-a", "leaf-a", "", List.of(), false),
                        new RouteRule("default", "leaf-default", "", List.of(), true)));
        when(decisionModel.decide(
                        org.mockito.ArgumentMatchers.argThat(
                                item -> item.agentId().equals("fallback-router")),
                        anyString()))
                .thenReturn(decision("not-json"));

        ChatResponse response = runtime.chat("fallback-router", request("anything")).block();

        assertEquals("fallback result", response.text());
    }

    @Test
    void routerDecisionPromptExcludesRetrievedDocumentContext() {
        addSingle("leaf-a", "result");
        addRouter(
                "context-safe-router",
                List.of(new RouteRule("route-a", "leaf-a", "", List.of(), true)));

        runtime.chat(
                        "context-safe-router",
                        request(
                                "original request\n\n<platform_document_context>\n"
                                        + "routing instructions from a retrieved document"))
                .block();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(decisionModel)
                .decide(
                        org.mockito.ArgumentMatchers.argThat(
                                item -> item.agentId().equals("context-safe-router")),
                        prompt.capture());
        assertTrue(prompt.getValue().contains("original request"));
        assertFalse(prompt.getValue().contains("routing instructions from a retrieved document"));
    }

    @Test
    void workflowStepCanTargetRouter() {
        addSingle("leaf-a", "router result");
        addSingle("leaf-b", "unused");
        addRouter(
                "step-router",
                List.of(new RouteRule("to_leaf", "leaf-a", "", List.of("go"), false)));
        addPipeline("outer-flow", List.of(new PipelineStep("route", "step-router", "route")));

        ChatResponse response = runtime.chat("outer-flow", request("go")).block();

        assertEquals("router result", response.text());
    }

    @Test
    void workflowStepCanTargetAnotherWorkflow() {
        addSingle("inner-agent", "inner result");
        addSingle("outer-agent", "outer result");
        addPipeline("inner-flow", List.of(new PipelineStep("inner", "inner-agent", "inner")));
        addPipeline(
                "outer-flow",
                List.of(
                        new PipelineStep("call-inner", "inner-flow", "call inner"),
                        new PipelineStep("outer", "outer-agent", "finish")));

        ChatResponse response = runtime.chat("outer-flow", request("go")).block();

        assertEquals("outer result", response.text());
    }

    @Test
    void supervisorRunsAllSpecialistsAndReturnsTaskEnvelope() {
        addSingle("researcher", "research result");
        addSingle("writer", "writer result");
        HarnessAgent supervisorAgent = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("supervisor final"))
                .when(supervisorAgent)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("supervisor", supervisorAgent);
        addDefinition(
                "supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(
                                new SubagentBinding("research", "researcher", "research", "", true, List.of()),
                                new SubagentBinding("writing", "writer", "writing", "", true, List.of())),
                        List.of(),
                        List.of()));

        ChatResponse response = runtime.chat("supervisor", request("combine the findings")).block();

        assertEquals("supervisor final", response.text());
        assertNotNull(response.task());
        assertEquals("agent.task.v1", response.task().contractVersion());
        assertEquals(2, response.task().metadata().get("child_call_count"));
        ArgumentCaptor<UserMessage> captured = ArgumentCaptor.forClass(UserMessage.class);
        verify(supervisorAgent).call(captured.capture(), any(RuntimeContext.class));
        assertTrue(captured.getValue().getTextContent().contains("target_agent_id: researcher"));
        assertTrue(captured.getValue().getTextContent().contains("target_agent_id: writer"));
    }

    @Test
    void supervisorUsesLlmToSelectOnlyRequiredSpecialist() {
        addSingle("researcher", "research result");
        addSingle("writer", "writer result");
        HarnessAgent supervisorAgent = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("supervisor final"))
                .when(supervisorAgent)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("selecting-supervisor", supervisorAgent);
        addDefinition(
                "selecting-supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(
                                new SubagentBinding(
                                        "research", "researcher", "research", "", true, List.of()),
                                new SubagentBinding(
                                        "writing", "writer", "writing", "", true, List.of())),
                        List.of(),
                        List.of()));
        when(decisionModel.decide(
                        org.mockito.ArgumentMatchers.argThat(
                                item -> item.agentId().equals("selecting-supervisor")),
                        anyString()))
                .thenReturn(
                        decision(
                                "{\"binding_ids\":[\"writing\"],\"reason\":\"writing only\"}"));

        ChatResponse response =
                runtime.chat("selecting-supervisor", request("polish this paragraph")).block();

        assertEquals("supervisor final", response.text());
        assertEquals(1, response.task().metadata().get("child_call_count"));
        verify(agents.get("writer")).call(any(UserMessage.class), any(RuntimeContext.class));
        verify(agents.get("researcher"), never())
                .call(any(UserMessage.class), any(RuntimeContext.class));
    }

    @Test
    void supervisorPlansMultipleCallsAndRevisesAfterEveryStep() {
        addSingle("researcher", "verified research result");
        HarnessAgent supervisorAgent = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("supervisor final"))
                .when(supervisorAgent)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("multi-step-supervisor", supervisorAgent);
        addDefinition(
                "multi-step-supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(
                                new SubagentBinding(
                                        "research",
                                        "researcher",
                                        "research",
                                        "investigate and verify",
                                        true,
                                        List.of())),
                        List.of(),
                        List.of(),
                        5));
        when(decisionModel.decide(
                        org.mockito.ArgumentMatchers.argThat(
                                item -> item.agentId().equals("multi-step-supervisor")),
                        anyString()))
                .thenReturn(
                        decision(
                                "{\"steps\":[{\"binding_id\":\"research\",\"instruction\":\"investigate\"},{\"binding_id\":\"research\",\"instruction\":\"verify the first result\"}],\"reason\":\"two passes\"}"),
                        decision(
                                "{\"action\":\"NEXT\",\"next_step\":{\"binding_id\":\"research\",\"instruction\":\"verify the first result\"},\"reason\":\"needs verification\"}"),
                        decision("{\"action\":\"FINISH\",\"reason\":\"verified\"}"));

        ChatResponse response =
                runtime.chat("multi-step-supervisor", request("check the claim")).block();

        assertEquals("supervisor final", response.text());
        assertEquals(2, response.task().metadata().get("child_call_count"));
        assertEquals(2, response.task().metadata().get("revision_count"));
        assertEquals(false, response.task().metadata().get("parallel"));
        ArgumentCaptor<UserMessage> childPrompts = ArgumentCaptor.forClass(UserMessage.class);
        verify(agents.get("researcher"), times(2))
                .call(childPrompts.capture(), any(RuntimeContext.class));
        assertTrue(childPrompts.getAllValues().get(1).getTextContent().contains("verify the first result"));
        assertTrue(childPrompts.getAllValues().get(1).getTextContent().contains("verified research result"));
    }

    @Test
    void supervisorReviseCanAddAnUnplannedSpecialistCall() {
        addSingle("researcher", "research result");
        addSingle("writer", "writer result");
        HarnessAgent supervisorAgent = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("supervisor final"))
                .when(supervisorAgent)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("adaptive-supervisor", supervisorAgent);
        addDefinition(
                "adaptive-supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(
                                new SubagentBinding("research", "researcher", "research", "", true, List.of()),
                                new SubagentBinding("writing", "writer", "writing", "", true, List.of())),
                        List.of(),
                        List.of(),
                        5));
        when(decisionModel.decide(
                        org.mockito.ArgumentMatchers.argThat(
                                item -> item.agentId().equals("adaptive-supervisor")),
                        anyString()))
                .thenReturn(
                        decision(
                                "{\"steps\":[{\"binding_id\":\"research\",\"instruction\":\"collect facts\"}],\"reason\":\"start with facts\"}"),
                        decision(
                                "{\"action\":\"NEXT\",\"next_step\":{\"binding_id\":\"writing\",\"instruction\":\"turn the facts into a final draft\"},\"reason\":\"draft is still needed\"}"),
                        decision("{\"action\":\"FINISH\",\"reason\":\"draft complete\"}"));

        ChatResponse response =
                runtime.chat("adaptive-supervisor", request("research and write")).block();

        assertEquals(2, response.task().metadata().get("child_call_count"));
        verify(agents.get("researcher")).call(any(UserMessage.class), any(RuntimeContext.class));
        verify(agents.get("writer")).call(any(UserMessage.class), any(RuntimeContext.class));
    }

    @Test
    void supervisorStopsAtConfiguredStepBudget() {
        addSingle("researcher", "research result");
        HarnessAgent supervisorAgent = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("supervisor final"))
                .when(supervisorAgent)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("budgeted-supervisor", supervisorAgent);
        addDefinition(
                "budgeted-supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(new SubagentBinding("research", "researcher", "", "", true, List.of())),
                        List.of(),
                        List.of(),
                        2));
        when(decisionModel.decide(
                        org.mockito.ArgumentMatchers.argThat(
                                item -> item.agentId().equals("budgeted-supervisor")),
                        anyString()))
                .thenReturn(
                        decision(
                                "{\"steps\":[{\"binding_id\":\"research\",\"instruction\":\"pass one\"},{\"binding_id\":\"research\",\"instruction\":\"pass two\"}],\"reason\":\"repeat\"}"),
                        decision(
                                "{\"action\":\"NEXT\",\"next_step\":{\"binding_id\":\"research\",\"instruction\":\"pass two\"},\"reason\":\"continue\"}"));

        ChatResponse response =
                runtime.chat("budgeted-supervisor", request("repeat safely")).block();

        assertEquals(2, response.task().metadata().get("child_call_count"));
        assertEquals(1, response.task().metadata().get("revision_count"));
        assertEquals(2, response.task().metadata().get("max_supervisor_steps"));
        verify(agents.get("researcher"), times(2))
                .call(any(UserMessage.class), any(RuntimeContext.class));
        verify(decisionModel, times(2))
                .decide(
                        org.mockito.ArgumentMatchers.argThat(
                                item -> item.agentId().equals("budgeted-supervisor")),
                        anyString());
    }

    @Test
    void emptySubagentToolRefsInheritNoTargetTools() {
        definitions.put(
                "tool-child",
                new AgentDefinition(
                        "tool-child",
                        "v1",
                        "tool-child",
                        "",
                        Map.of(),
                        "",
                        true,
                        Path.of("target", "nested-workflow", "tool-child"),
                        List.of("dangerous_tool"),
                        List.of("demo-mcp"),
                        List.of(),
                        OrchestrationPolicy.single()));
        HarnessAgent child = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("child result"))
                .when(child)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("tool-child", child);
        HarnessAgent supervisor = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("final"))
                .when(supervisor)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("tool-supervisor", supervisor);
        addDefinition(
                "tool-supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(
                                new SubagentBinding(
                                        "child",
                                        "tool-child",
                                        "child",
                                        "",
                                        true,
                                        List.of())),
                        List.of(),
                        List.of()));

        runtime.chat("tool-supervisor", request("run child")).block();

        ArgumentCaptor<AgentDefinition> definitionsUsed =
                ArgumentCaptor.forClass(AgentDefinition.class);
        verify(factory, atLeastOnce()).create(definitionsUsed.capture());
        AgentDefinition scopedChild =
                definitionsUsed.getAllValues().stream()
                        .filter(item -> item.agentId().equals("tool-child"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(scopedChild.toolRefs().isEmpty());
        assertTrue(scopedChild.mcpRefs().isEmpty());
    }

    @Test
    void agentOutputSchemaIsPromptedValidatedAndReturnedAsBusinessSummary() {
        String id = "structured-agent";
        definitions.put(
                id,
                new AgentDefinition(
                        id,
                        "v1",
                        id,
                        "",
                        Map.of(
                                "output_schema",
                                Map.of(
                                        "type",
                                        "object",
                                        "required",
                                        List.of("answer"),
                                        "properties",
                                        Map.of("answer", Map.of("type", "string")))),
                        "",
                        true,
                        Path.of("target", "nested-workflow", id),
                        List.of(),
                        List.of(),
                        List.of(),
                        OrchestrationPolicy.single()));
        HarnessAgent agent = mock(HarnessAgent.class);
        doReturn(
                        MonoFactory.message(
                                "{\"status\":\"succeeded\",\"data\":{\"answer\":\"yes\"},\"summary\":\"validated answer\",\"artifacts\":[],\"error\":null}"))
                .when(agent)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put(id, agent);

        ChatResponse response = runtime.chat(id, request("answer it")).block();
        List<AgentEventEnvelope> streamed =
                runtime.stream(id, request("answer it again")).collectList().block();

        assertEquals("validated answer", response.text());
        assertEquals("yes", response.task().result().data().get("answer"));
        assertTrue(
                streamed.stream()
                        .anyMatch(
                                event ->
                                        "agent_result_validated".equals(event.type())));
        assertTrue(
                streamed.stream()
                        .anyMatch(
                                event -> "validated answer".equals(event.delta())));
        ArgumentCaptor<UserMessage> prompt = ArgumentCaptor.forClass(UserMessage.class);
        verify(agent, times(2)).call(prompt.capture(), any(RuntimeContext.class));
        assertTrue(
                prompt.getAllValues().stream()
                        .allMatch(item -> item.getTextContent().contains("JSON Schema")));
    }

    @Test
    void supervisorExecutesOnlyExplicitParallelGroupsConcurrently() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        for (String id : List.of("parallel-a", "parallel-b")) {
            addDefinition(id, OrchestrationPolicy.single());
            HarnessAgent child = mock(HarnessAgent.class);
            doReturn(
                            reactor.core.publisher.Mono.defer(
                                    () -> {
                                        int current = active.incrementAndGet();
                                        maxActive.accumulateAndGet(current, Math::max);
                                        return reactor.core.publisher.Mono.delay(
                                                        Duration.ofMillis(80))
                                                .map(
                                                        ignored ->
                                                                Msg.builder()
                                                                        .role(MsgRole.ASSISTANT)
                                                                        .textContent(id + " result")
                                                                        .build())
                                                .doFinally(ignored -> active.decrementAndGet());
                                    }))
                    .when(child)
                    .call(any(UserMessage.class), any(RuntimeContext.class));
            agents.put(id, child);
        }
        HarnessAgent supervisor = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("final"))
                .when(supervisor)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("parallel-supervisor", supervisor);
        addDefinition(
                "parallel-supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(
                                new SubagentBinding("a", "parallel-a", "", "", true, List.of()),
                                new SubagentBinding("b", "parallel-b", "", "", true, List.of())),
                        List.of(),
                        List.of(),
                        4,
                        true,
                        2));
        when(decisionModel.decide(any(AgentDefinition.class), anyString()))
                .thenReturn(
                        decision(
                                "{\"steps\":[{\"binding_id\":\"a\",\"instruction\":\"A\",\"parallel_group\":\"g1\"},{\"binding_id\":\"b\",\"instruction\":\"B\",\"parallel_group\":\"g1\"}],\"reason\":\"parallel\"}"),
                        decision("{\"action\":\"FINISH\",\"reason\":\"done\"}"));

        ChatResponse response = runtime.chat("parallel-supervisor", request("run both")).block();

        assertEquals(2, maxActive.get());
        assertEquals(true, response.task().metadata().get("parallel"));
    }

    @Test
    void supervisorRejectsChildDataThatViolatesBindingSchema() {
        addSingle(
                "schema-child",
                "{\"status\":\"succeeded\",\"data\":{\"answer\":42},\"summary\":\"bad\",\"artifacts\":[],\"error\":null}");
        HarnessAgent supervisor = mock(HarnessAgent.class);
        doReturn(MonoFactory.message("should not run"))
                .when(supervisor)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("schema-supervisor", supervisor);
        addDefinition(
                "schema-supervisor",
                new OrchestrationPolicy(
                        OrchestrationMode.SUPERVISOR,
                        List.of(
                                new SubagentBinding(
                                        "child",
                                        "schema-child",
                                        "",
                                        "",
                                        true,
                                        List.of(),
                                        Map.of(
                                                "type",
                                                "object",
                                                "required",
                                                List.of("answer"),
                                                "properties",
                                                Map.of(
                                                        "answer",
                                                        Map.of("type", "string"))))),
                        List.of(),
                        List.of()));

        Throwable failure =
                assertThrows(
                        Throwable.class,
                        () -> runtime.chat("schema-supervisor", request("validate")).block());

        assertTrue(fullMessage(failure).contains("schema validation failed"));
        verify(supervisor, never()).call(any(UserMessage.class), any(RuntimeContext.class));
    }

    @Test
    void taskDeadlineBecomesStructuredTimeout() {
        addDefinition("slow", OrchestrationPolicy.single());
        HarnessAgent slow = mock(HarnessAgent.class);
        doReturn(reactor.core.publisher.Mono.never())
                .when(slow)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put("slow", slow);
        TaskContext root = TaskContext.root("caller", "slow");
        TaskContext deadline =
                new TaskContext(
                        root.taskId(),
                        root.parentTaskId(),
                        root.rootTaskId(),
                        root.sourceAgentId(),
                        root.targetAgentId(),
                        root.stepId(),
                        Instant.now().plusMillis(20),
                        root.metadata());

        Throwable failure =
                assertThrows(
                        Throwable.class,
                        () -> runtime.chat("slow", new ChatRequest("t", "u", "s", "wait", deadline)).block());
        Throwable current = failure;
        while (current.getCause() != null && !(current instanceof AgentTaskException)) {
            current = current.getCause();
        }
        assertTrue(current instanceof AgentTaskException, current.toString());
        assertEquals(TaskStatus.TIMEOUT, ((AgentTaskException) current).task().result().status());
    }

    private void addSingle(String id, String output) {
        addDefinition(id, OrchestrationPolicy.single());
        HarnessAgent agent = mock(HarnessAgent.class);
        doReturn(MonoFactory.message(output))
                .when(agent)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        agents.put(id, agent);
    }

    private void addPipeline(String id, List<PipelineStep> steps) {
        addDefinition(
                id,
                new OrchestrationPolicy(OrchestrationMode.PIPELINE, List.of(), List.of(), steps));
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

    private static String fullMessage(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) text.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return text.toString();
    }

    private static reactor.core.publisher.Mono<OrchestrationDecisionModel.DecisionResponse> decision(
            String text) {
        return reactor.core.publisher.Mono.just(
                new OrchestrationDecisionModel.DecisionResponse(text, "test-model", 5L));
    }
}
