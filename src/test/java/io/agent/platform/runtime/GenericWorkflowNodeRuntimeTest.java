/*
 * Copyright 2026 by the company contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.control.WorkflowEdge;
import io.agent.platform.control.WorkflowEndpoint;
import io.agent.platform.control.WorkflowNode;
import io.agent.platform.control.WorkflowNodeType;
import io.agent.platform.web.PlatformCompatibilityState;
import io.agent.platform.web.DurableAgentRunService;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

class GenericWorkflowNodeRuntimeTest {

    @TempDir Path tempDir;

    @BeforeAll
    static void allowPrivateHttpTargetsForLocalServers() {
        System.setProperty("agent.platform.workflow.http.allow-private-network", "true");
        System.setProperty("agent.platform.workflow.jdbc.allow-prefixes", "jdbc:sqlite:");
    }

    @AfterAll
    static void restorePrivateHttpPolicy() {
        System.clearProperty("agent.platform.workflow.http.allow-private-network");
        System.clearProperty("agent.platform.workflow.jdbc.allow-prefixes");
    }

    @Test
    void standaloneWorkflowCanRunWithoutAnAgentDefinition() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/health",
                exchange -> {
                    byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            AgentRuntimeService runtime =
                    new AgentRuntimeService(
                            mock(AgentDefinitionRegistry.class),
                            mock(AgentScopeHarnessFactory.class),
                            mock(PlatformCompatibilityState.class));
            WorkflowAsset asset =
                    new WorkflowAsset(
                            "health-flow",
                            1,
                            "Health flow",
                            "",
                            "platform",
                            "manual",
                            "PUBLISHED",
                            Map.of(),
                            Map.of(),
                            List.of(
                                    new WorkflowNode(
                                            "health",
                                            WorkflowNodeType.HTTP_REQUEST,
                                            "",
                                            "",
                                            Map.of(
                                                    "url",
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort()
                                                            + "/health",
                                                    "method",
                                                    "GET"),
                                            Map.of(),
                                            Map.of(),
                                            5000L,
                                            0,
                                            null,
                                            List.of(),
                                            List.of())),
                            List.of(),
                            "now",
                            "now",
                            "now");

            assertEquals(
                    "{\"status\":\"ok\"}",
                    runtime.workflow(asset, new ChatRequest("platform", "user", "s", "check")).block().text());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpNodeCanFeedAnExistingAgent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/orders",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = "{\"status\":\"ok\",\"content\":\"order loaded\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
            AgentScopeHarnessFactory factory = mock(AgentScopeHarnessFactory.class);
            PlatformCompatibilityState platformState = mock(PlatformCompatibilityState.class);
            HarnessAgent writer = mock(HarnessAgent.class);
            doReturn(
                            Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("final answer")
                                            .build()))
                    .when(writer)
                    .call(any(UserMessage.class), any(RuntimeContext.class));
            AgentDefinition writerDefinition = definition("writer", OrchestrationPolicy.single());
            WorkflowAsset asset =
                    new WorkflowAsset(
                            "order-flow", 1, "Order flow", "", "platform", "manual", "PUBLISHED",
                            Map.of(), Map.of(),
                            List.of(
                                    new WorkflowNode(
                                            "query", WorkflowNodeType.HTTP_REQUEST, "", "", Map.of(
                                                    "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/orders",
                                                    "method", "POST", "body", "{\"query\":\"{{input}}\"}"),
                                            Map.of(), Map.of(), 5000L, 0, null, List.of(), List.of()),
                                    new WorkflowNode(
                                            "write", WorkflowNodeType.AGENT_INVOKE, "writer", "Write the result",
                                            Map.of(), Map.of(), Map.of(), null, 0, null, List.of(), List.of())),
                            List.of(new WorkflowEdge(
                                    "query-write",
                                    new WorkflowEndpoint("query", "value"),
                                    new WorkflowEndpoint("write", "value"),
                                    "data", Map.of())),
                            "now", "now", "now");
            when(registry.findPublished(anyString()))
                    .thenAnswer(
                            invocation -> {
                                String id = invocation.getArgument(0);
                                return Optional.ofNullable(
                                        "writer".equals(id) ? writerDefinition : null);
                            });
            when(factory.create(any(AgentDefinition.class))).thenReturn(writer);

            AgentRuntimeService runtime =
                    new AgentRuntimeService(registry, factory, platformState);

            assertEquals("final answer", runtime.workflow(asset, new ChatRequest("t", "u", "s", "10086")).block().text());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parallelBranchesRunConcurrentlyAndJoinInDeclaredOrder() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(executor);
        server.createContext(
                "/a",
                exchange -> {
                    entered.countDown();
                    await(entered);
                    await(release);
                    byte[] body = "A".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.createContext(
                "/b",
                exchange -> {
                    entered.countDown();
                    await(entered);
                    release.countDown();
                    byte[] body = "B".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            AgentRuntimeService runtime =
                    new AgentRuntimeService(
                            mock(AgentDefinitionRegistry.class),
                            mock(AgentScopeHarnessFactory.class),
                            mock(PlatformCompatibilityState.class));
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            WorkflowAsset asset =
                    new WorkflowAsset(
                            "parallel-flow",
                            1,
                            "Parallel flow",
                            "",
                            "platform",
                            "manual",
                            "PUBLISHED",
                            Map.of(),
                            Map.of(),
                            List.of(
                                    new WorkflowNode(
                                            "parallel",
                                            WorkflowNodeType.PARALLEL,
                                            "",
                                            "",
                                            5000L,
                                            0,
                                            null,
                                            List.of(),
                                            List.of()),
                                    new WorkflowNode(
                                            "a",
                                            WorkflowNodeType.HTTP_REQUEST,
                                            "",
                                            "",
                                            Map.of("url", base + "/a", "method", "GET"),
                                            Map.of(),
                                            Map.of(),
                                            5000L,
                                            0,
                                            null,
                                            List.of(),
                                            List.of()),
                                    new WorkflowNode(
                                            "b",
                                            WorkflowNodeType.HTTP_REQUEST,
                                            "",
                                            "",
                                            Map.of("url", base + "/b", "method", "GET"),
                                            Map.of(),
                                            Map.of(),
                                            5000L,
                                            0,
                                            null,
                                            List.of(),
                                            List.of()),
                                    new WorkflowNode(
                                            "join",
                                            WorkflowNodeType.JOIN,
                                            "",
                                            "",
                                            5000L,
                                            0,
                                            null,
                                            List.of(),
                                            List.of())),
                            List.of(
                                    new WorkflowEdge(
                                            "parallel-a",
                                            new WorkflowEndpoint("parallel", "value"),
                                            new WorkflowEndpoint("a", "value"),
                                            "data",
                                            Map.of()),
                                    new WorkflowEdge(
                                            "parallel-b",
                                            new WorkflowEndpoint("parallel", "value"),
                                            new WorkflowEndpoint("b", "value"),
                                            "data",
                                            Map.of()),
                                    new WorkflowEdge(
                                            "a-join",
                                            new WorkflowEndpoint("a", "value"),
                                            new WorkflowEndpoint("join", "value"),
                                            "data",
                                            Map.of()),
                                    new WorkflowEdge(
                                            "b-join",
                                            new WorkflowEndpoint("b", "value"),
                                            new WorkflowEndpoint("join", "value"),
                                            "data",
                                            Map.of())),
                            "now",
                            "now",
                            "now");

            assertEquals(
                    "[\"A\",\"B\"]",
                    runtime.workflow(asset, new ChatRequest("t", "u", "s", "input")).block().text());
            assertTrue(entered.getCount() == 0, "both branches should have started");
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void workflowStreamExposesRealNodeEventsAndDataTransformOutput() {
        AgentRuntimeService runtime =
                new AgentRuntimeService(
                        mock(AgentDefinitionRegistry.class),
                        mock(AgentScopeHarnessFactory.class),
                        mock(PlatformCompatibilityState.class));
        WorkflowAsset asset =
                new WorkflowAsset(
                        "observable-flow",
                        1,
                        "Observable flow",
                        "",
                        "platform",
                        "manual",
                        "PUBLISHED",
                        Map.of(),
                        Map.of(),
                        List.of(
                                new WorkflowNode(
                                        "input",
                                        WorkflowNodeType.INPUT,
                                        "",
                                        "",
                                        null,
                                        0,
                                        null,
                                        List.of(),
                                        List.of()),
                                new WorkflowNode(
                                        "transform",
                                        WorkflowNodeType.DATA_TRANSFORM,
                                        "",
                                        "",
                                        Map.of("mapping", Map.of("echo", "$")),
                                        Map.of(),
                                        Map.of(),
                                        null,
                                        0,
                                        null,
                                        List.of(),
                                        List.of()),
                                new WorkflowNode(
                                        "output",
                                        WorkflowNodeType.OUTPUT,
                                        "",
                                        "",
                                        null,
                                        0,
                                        null,
                                        List.of(),
                                        List.of())),
                        List.of(
                                new WorkflowEdge(
                                        "input-transform",
                                        new WorkflowEndpoint("input", "value"),
                                        new WorkflowEndpoint("transform", "value"),
                                        "data",
                                        Map.of()),
                                new WorkflowEdge(
                                        "transform-output",
                                        new WorkflowEndpoint("transform", "value"),
                                        new WorkflowEndpoint("output", "value"),
                                        "data",
                                        Map.of())),
                        "now",
                        "now",
                        "now");

        List<AgentEventEnvelope> events =
                runtime.workflowStream(asset, new ChatRequest("t", "u", "s", "hello"))
                        .collectList()
                        .block();

        List<String> types =
                events.stream().map(AgentEventEnvelope::type).collect(Collectors.toList());
        assertEquals(3, types.stream().filter("workflow_node_start"::equals).count());
        assertEquals(3, types.stream().filter("workflow_node_complete"::equals).count());
        assertEquals("{\"echo\":\"hello\"}",
                events.stream()
                        .filter(event -> "text_block_delta".equals(event.type()))
                        .findFirst()
                        .orElseThrow()
                        .delta());
    }

    @Test
    void parallelBranchSideEffectsAreRecoveredFromNodeCheckpoints() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ExecutorService executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(executor);
        server.createContext(
                "/effect",
                exchange -> {
                    String bodyText = "call-" + calls.incrementAndGet();
                    byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            PlatformStorageLayer storage =
                    new PlatformStorageLayer(
                            tempDir.toString(),
                            "sqlite",
                            "jdbc:sqlite:" + tempDir.resolve("parallel-checkpoint.db"),
                            "platform_config",
                            "platform_",
                            "");
            OrchestrationCheckpointStore checkpoints =
                    new OrchestrationCheckpointStore(storage, "runtime-test", 30_000);
            checkpoints.initialize();
            AgentRuntimeService runtime =
                    new AgentRuntimeService(
                            mock(AgentDefinitionRegistry.class),
                            mock(AgentScopeHarnessFactory.class),
                            mock(PlatformCompatibilityState.class),
                            mock(OrchestrationDecisionModel.class),
                            checkpoints);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/effect";
            WorkflowNode parallel =
                    new WorkflowNode("parallel", WorkflowNodeType.PARALLEL, "", "", null, 0, null, List.of(), List.of());
            WorkflowNode left = httpNode("left", url);
            WorkflowNode right = httpNode("right", url);
            WorkflowNode join =
                    new WorkflowNode("join", WorkflowNodeType.JOIN, "", "", null, 0, null, List.of(), List.of());
            WorkflowAsset asset =
                    new WorkflowAsset(
                            "checkpoint-flow",
                            1,
                            "Checkpoint flow",
                            "",
                            "platform",
                            "manual",
                            "PUBLISHED",
                            Map.of(),
                            Map.of(),
                            List.of(parallel, left, right, join),
                            List.of(
                                    edge("parallel-left", "parallel", "left"),
                                    edge("parallel-right", "parallel", "right"),
                                    edge("left-join", "left", "join"),
                                    edge("right-join", "right", "join")),
                            "now",
                            "now",
                            "now");
            String runId = "run-parallel-recovery";
            ChatRequest request =
                    new ChatRequest(
                            "org",
                            "user",
                            "session",
                            "input",
                            io.agent.platform.runtime.protocol.TaskContext.root(
                                    runId, "user", "workflow:checkpoint-flow", null));
            checkpoints.register(runId, Map.of("runtime_kind", "workflow"));
            assertTrue(checkpoints.acquire(runId));

            runtime.workflow(asset, request).block();
            assertEquals(2, calls.get());
            runtime.workflow(asset, request).block();
            assertEquals(2, calls.get(), "recovery must not repeat completed branch effects");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void mcpNodeProjectsOnlyTheSelectedCapabilityAndDropsTargetOrchestration() {
        AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
        AgentScopeHarnessFactory factory = mock(AgentScopeHarnessFactory.class);
        HarnessAgent harness = mock(HarnessAgent.class);
        AgentDefinition target =
                new AgentDefinition(
                        "capability-agent",
                        "v1",
                        "Capability agent",
                        "model",
                        Map.of(),
                        "system",
                        true,
                        tempDir.resolve("capability-agent"),
                        List.of("ordinary-tool"),
                        List.of("selected-mcp", "other-mcp"),
                        List.of("other-skill"),
                        new OrchestrationPolicy(
                                OrchestrationMode.PIPELINE,
                                List.of(),
                                List.of(),
                                List.of()));
        when(registry.findPublished("capability-agent")).thenReturn(Optional.of(target));
        when(factory.create(any(AgentDefinition.class))).thenReturn(harness);
        doReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .textContent("mcp result")
                                        .build()))
                .when(harness)
                .call(any(UserMessage.class), any(RuntimeContext.class));
        WorkflowNode mcp =
                new WorkflowNode(
                        "mcp",
                        WorkflowNodeType.MCP_INVOKE,
                        "selected-mcp",
                        "use it",
                        Map.of("agent_id", "capability-agent"),
                        Map.of(),
                        Map.of(),
                        null,
                        0,
                        null,
                        List.of(),
                        List.of());
        WorkflowAsset workflow =
                new WorkflowAsset(
                        "mcp-flow", 1, "MCP", "", "platform", "manual", "PUBLISHED",
                        Map.of(), Map.of(), List.of(mcp), List.of(), "", "", "");
        AgentRuntimeService runtime =
                new AgentRuntimeService(registry, factory, mock(PlatformCompatibilityState.class));

        assertEquals(
                "mcp result",
                runtime.workflow(workflow, new ChatRequest("org", "user", "s", "input"))
                        .block()
                        .text());
        ArgumentCaptor<AgentDefinition> definition =
                ArgumentCaptor.forClass(AgentDefinition.class);
        verify(factory).create(definition.capture());
        assertTrue(definition.getValue().toolRefs().isEmpty());
        assertEquals(List.of("selected-mcp"), definition.getValue().mcpRefs());
        assertTrue(definition.getValue().skillRefs().isEmpty());
        assertEquals(OrchestrationMode.SINGLE, definition.getValue().orchestration().mode());
    }

    @Test
    void databaseWriteUsesADeterministicBoundIdempotencyKey() throws Exception {
        Path database = tempDir.resolve("workflow-node.db");
        String jdbcUrl = "jdbc:sqlite:" + database;
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE effects(value TEXT, idempotency_key TEXT UNIQUE)");
        }
        WorkflowNode write =
                new WorkflowNode(
                        "write",
                        WorkflowNodeType.DATABASE_WRITE,
                        "",
                        "",
                        Map.of(
                                "jdbc_url", jdbcUrl,
                                "sql", "INSERT INTO effects(idempotency_key,value) VALUES ({{idempotency_key}},?) ON CONFLICT(idempotency_key) DO NOTHING",
                                "parameters", List.of("$.value"),
                                "idempotency_key", "effect"),
                        Map.of(),
                        Map.of(),
                        5_000L,
                        0,
                        null,
                        List.of(),
                        List.of());
        WorkflowAsset asset =
                new WorkflowAsset(
                        "database-flow", 1, "Database", "", "platform", "manual", "PUBLISHED",
                        Map.of(), Map.of(), List.of(write), List.of(), "", "", "");
        AgentRuntimeService runtime =
                new AgentRuntimeService(
                        mock(AgentDefinitionRegistry.class),
                        mock(AgentScopeHarnessFactory.class),
                        mock(PlatformCompatibilityState.class));
        ChatRequest request =
                new ChatRequest(
                        "org",
                        "user",
                        "session",
                        "{\"value\":\"once\"}",
                        io.agent.platform.runtime.protocol.TaskContext.root(
                                "database-run", "user", "workflow:database-flow", null));

        assertEquals("{\"updated_rows\":1}", runtime.workflow(asset, request).block().text());
        assertEquals("{\"updated_rows\":0}", runtime.workflow(asset, request).block().text());
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl);
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT value,idempotency_key FROM effects")) {
            assertTrue(rows.next());
            assertEquals("once", rows.getString("value"));
            assertEquals("database-run:write:effect", rows.getString("idempotency_key"));
            assertFalse(rows.next());
        }
    }

    @Test
    void humanApprovalSuspendsAndThenContinuesWithTheOriginalInput() {
        PlatformCompatibilityState state = mock(PlatformCompatibilityState.class);
        Map<String, Object> pending =
                Map.of(
                        "waiting_id", "wait_approval-run_approval",
                        "status", "waiting");
        when(state.waiting("approval-run")).thenReturn(null, Map.of(
                "waiting_id", "wait_approval-run_approval",
                "status", "resumed",
                "answer", Map.of("approved", true)));
        when(state.createWaiting(eq("approval-run"), any())).thenReturn(pending);
        WorkflowNode approval =
                new WorkflowNode(
                        "approval",
                        WorkflowNodeType.HUMAN_APPROVAL,
                        "",
                        "Approve",
                        null,
                        0,
                        null,
                        List.of(),
                        List.of());
        WorkflowAsset asset =
                new WorkflowAsset(
                        "approval-flow", 1, "Approval", "", "platform", "manual", "PUBLISHED",
                        Map.of(), Map.of(), List.of(approval), List.of(), "", "", "");
        AgentRuntimeService runtime =
                new AgentRuntimeService(
                        mock(AgentDefinitionRegistry.class),
                        mock(AgentScopeHarnessFactory.class),
                        state);
        ChatRequest request =
                new ChatRequest(
                        "org",
                        "user",
                        "session",
                        "original",
                        io.agent.platform.runtime.protocol.TaskContext.root(
                                "approval-run", "user", "workflow:approval-flow", null));

        Throwable waiting =
                assertThrows(Throwable.class, () -> runtime.workflow(asset, request).block());
        assertTrue(DurableAgentRunService.isWorkflowWaiting(waiting));
        assertEquals("original", runtime.workflow(asset, request).block().text());
    }

    @Test
    void conditionSelectsTheMatchingControlEdge() {
        WorkflowNode input =
                new WorkflowNode("input", WorkflowNodeType.INPUT, "", "", null, 0, null, List.of(), List.of());
        WorkflowNode condition =
                new WorkflowNode("condition", WorkflowNodeType.CONDITION, "", "", null, 0, null, List.of(), List.of());
        WorkflowNode accepted =
                new WorkflowNode(
                        "accepted", WorkflowNodeType.DATA_TRANSFORM, "", "",
                        Map.of("template", "accepted"), Map.of(), Map.of(), null, 0, null, List.of(), List.of());
        WorkflowNode fallback =
                new WorkflowNode(
                        "fallback", WorkflowNodeType.DATA_TRANSFORM, "", "",
                        Map.of("template", "fallback"), Map.of(), Map.of(), null, 0, null, List.of(), List.of());
        WorkflowAsset asset =
                new WorkflowAsset(
                        "condition-flow", 1, "Condition", "", "platform", "manual", "PUBLISHED",
                        Map.of(), Map.of(), List.of(input, condition, accepted, fallback),
                        List.of(
                                edge("input-condition", "input", "condition"),
                                new WorkflowEdge(
                                        "condition-accepted",
                                        new WorkflowEndpoint("condition", "value"),
                                        new WorkflowEndpoint("accepted", "value"),
                                        "control",
                                        Map.of(),
                                        Map.of("path", "$.status", "operator", "equals", "value", "ok"),
                                        false),
                                new WorkflowEdge(
                                        "condition-fallback",
                                        new WorkflowEndpoint("condition", "value"),
                                        new WorkflowEndpoint("fallback", "value"),
                                        "control",
                                        Map.of(),
                                        Map.of(),
                                        true)),
                        "", "", "");
        AgentRuntimeService runtime =
                new AgentRuntimeService(
                        mock(AgentDefinitionRegistry.class),
                        mock(AgentScopeHarnessFactory.class),
                        mock(PlatformCompatibilityState.class));

        assertEquals(
                "accepted",
                runtime.workflow(asset, new ChatRequest("org", "user", "s", "{\"status\":\"ok\"}"))
                        .block()
                        .text());
    }

    @Test
    void foreachWithoutATargetMapsItemsInStableOrder() {
        WorkflowNode foreach =
                new WorkflowNode(
                        "foreach", WorkflowNodeType.FOREACH, "", "", Map.of("concurrency", 4),
                        Map.of(), Map.of(), null, 0, null, List.of(), List.of());
        WorkflowAsset asset =
                new WorkflowAsset(
                        "foreach-flow", 1, "Foreach", "", "platform", "manual", "PUBLISHED",
                        Map.of(), Map.of(), List.of(foreach), List.of(), "", "", "");
        AgentRuntimeService runtime =
                new AgentRuntimeService(
                        mock(AgentDefinitionRegistry.class),
                        mock(AgentScopeHarnessFactory.class),
                        mock(PlatformCompatibilityState.class));

        assertEquals(
                "[\"a\",\"b\",\"c\"]",
                runtime.workflow(asset, new ChatRequest("org", "user", "s", "[\"a\",\"b\",\"c\"]"))
                        .block()
                        .text());
    }

    @Test
    void subflowUsesItsPinnedVersionAndRejectsRecursiveCycles() {
        io.agent.platform.web.WorkflowAssetService workflows =
                mock(io.agent.platform.web.WorkflowAssetService.class);
        WorkflowAsset child =
                new WorkflowAsset(
                        "child", 2, "Child", "", "platform", "manual", "PUBLISHED",
                        Map.of(), Map.of(),
                        List.of(new WorkflowNode(
                                "transform", WorkflowNodeType.DATA_TRANSFORM, "", "",
                                Map.of("template", "child: {{input}}"), Map.of(), Map.of(), null, 0, null, List.of(), List.of())),
                        List.of(), "", "", "");
        WorkflowNode invokeChild =
                new WorkflowNode(
                        "child-call", WorkflowNodeType.SUBFLOW_INVOKE, "child", "",
                        Map.of("workflow_version", 2), Map.of(), Map.of(), null, 0, null, List.of(), List.of());
        WorkflowAsset parent =
                new WorkflowAsset(
                        "parent", 1, "Parent", "", "platform", "manual", "PUBLISHED",
                        Map.of(), Map.of(), List.of(invokeChild), List.of(), "", "", "");
        when(workflows.requirePublishedVersion("child", 2)).thenReturn(child);
        when(workflows.requirePublished("child")).thenReturn(child);
        AgentRuntimeService runtime =
                new AgentRuntimeService(
                        mock(AgentDefinitionRegistry.class),
                        mock(AgentScopeHarnessFactory.class),
                        mock(PlatformCompatibilityState.class),
                        workflows);

        assertEquals(
                "child: input",
                runtime.workflow(parent, new ChatRequest("org", "user", "s", "input"))
                        .block()
                        .text());

        WorkflowAsset recursiveChild =
                new WorkflowAsset(
                        "child", 2, "Child", "", "platform", "manual", "PUBLISHED",
                        Map.of(), Map.of(),
                        List.of(new WorkflowNode(
                                "parent-call", WorkflowNodeType.SUBFLOW_INVOKE, "parent", "",
                                Map.of("workflow_version", 1), Map.of(), Map.of(), null, 0, null, List.of(), List.of())),
                        List.of(), "", "", "");
        when(workflows.requirePublishedVersion("child", 2)).thenReturn(recursiveChild);
        when(workflows.requirePublished("child")).thenReturn(recursiveChild);
        when(workflows.requirePublishedVersion("parent", 1)).thenReturn(parent);
        when(workflows.requirePublished("parent")).thenReturn(parent);
        Throwable cycle =
                assertThrows(
                        Throwable.class,
                        () -> runtime.workflow(parent, new ChatRequest("org", "user", "cycle", "input")).block());
        assertTrue(String.valueOf(cycle.getMessage()).contains("cycle"));
    }

    private static WorkflowNode httpNode(String id, String url) {
        return new WorkflowNode(
                id,
                WorkflowNodeType.HTTP_REQUEST,
                "",
                "",
                Map.of("url", url, "method", "GET"),
                Map.of(),
                Map.of(),
                5_000L,
                0,
                null,
                List.of(),
                List.of());
    }

    private static WorkflowEdge edge(String id, String from, String to) {
        return new WorkflowEdge(
                id,
                new WorkflowEndpoint(from, "value"),
                new WorkflowEndpoint(to, "value"),
                "data",
                Map.of());
    }

    private static AgentDefinition definition(String id, OrchestrationPolicy policy) {
        return new AgentDefinition(
                id,
                "v1",
                id,
                "",
                Map.of(),
                "",
                true,
                Path.of("target", "generic-node-test", id),
                List.of(),
                List.of(),
                List.of(),
                policy);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for workflow branch", exception);
        }
    }
}
