/*
 * Copyright 2026 by the company contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.control.WorkflowEdge;
import io.agent.platform.control.WorkflowEndpoint;
import io.agent.platform.control.WorkflowNode;
import io.agent.platform.control.WorkflowNodeType;
import io.agent.platform.web.PlatformCompatibilityState;
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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class GenericWorkflowNodeRuntimeTest {

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
