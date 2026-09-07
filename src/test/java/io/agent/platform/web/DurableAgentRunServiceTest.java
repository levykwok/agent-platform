/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.runtime.AgentEventEnvelope;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.runtime.AgentRuntimeService;
import io.agent.platform.runtime.ChatRequest;
import io.agent.platform.runtime.OrchestrationCheckpointStore;
import io.agent.platform.runtime.protocol.TaskContext;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class DurableAgentRunServiceTest {

    @TempDir Path tempDir;

    @Test
    void localCancellationInterruptsAnActiveStream() throws Exception {
        PlatformStorageLayer storage = storage(tempDir);
        OrchestrationCheckpointStore checkpoints =
                new OrchestrationCheckpointStore(storage, "instance-a", 5_000);
        checkpoints.initialize();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.stream(eq("agent"), any())).thenReturn(Flux.never());
        DurableAgentRunService service =
                new DurableAgentRunService(
                        checkpoints, runtime, mock(PlatformCompatibilityState.class), 10);
        ChatRequest request = request("run-cancel", "agent");
        service.register("run-cancel", "agent", request);

        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        service.stream("run-cancel", "agent", request)
                .subscribe(ignored -> {}, failure::complete, () -> failure.complete(null));
        assertTrue(service.requestCancellation("run-cancel"));
        Throwable error = failure.get(5, TimeUnit.SECONDS);
        assertTrue(DurableAgentRunService.isCancellation(error));

        service.cancelled("run-cancel");
    }

    @Test
    void expiredRunIsClaimedAndFinishedByAnotherInstance() throws Exception {
        PlatformStorageLayer storage = storage(tempDir);
        OrchestrationCheckpointStore first =
                new OrchestrationCheckpointStore(storage, "instance-a", 5_000);
        OrchestrationCheckpointStore second =
                new OrchestrationCheckpointStore(storage, "instance-b", 5_000);
        first.initialize();
        second.initialize();
        first.register(
                "run-recover",
                Map.of(
                        "agent_id",
                        "agent",
                        "tenant_id",
                        "org",
                        "user_id",
                        "user",
                        "session_id",
                        "session",
                        "message",
                        "resume",
                        "images",
                        List.of()));
        first.acquire("run-recover");
        expire(storage, "run-recover");
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.stream(eq("agent"), any()))
                .thenReturn(
                        Flux.just(
                                new AgentEventEnvelope(
                                        "event-1",
                                        "text_block_delta",
                                        Instant.now().toString(),
                                        "agent",
                                        "recovered answer",
                                        Map.of())));
        PlatformCompatibilityState state = mock(PlatformCompatibilityState.class);
        DurableAgentRunService service =
                new DurableAgentRunService(second, runtime, state, 10);

        service.recoverExpiredRuns();

        verify(state, timeout(5_000)).markRunRecovering("run-recover");
        verify(state, timeout(5_000)).finishRun("run-recover", "recovered answer");
        verify(state, timeout(5_000))
                .appendSessionMessage(
                        eq("agent"),
                        eq("session"),
                        eq("user"),
                        eq("assistant"),
                        eq("recovered answer"),
                        any());
    }

    @Test
    void synchronousRuntimeFailureAlwaysReleasesTheLocalActiveRun() {
        PlatformStorageLayer storage = storage(tempDir);
        OrchestrationCheckpointStore checkpoints =
                new OrchestrationCheckpointStore(storage, "instance-a", 5_000);
        checkpoints.initialize();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.stream(eq("agent"), any()))
                .thenThrow(new IllegalStateException("synchronous runtime failure"));
        DurableAgentRunService service =
                new DurableAgentRunService(
                        checkpoints, runtime, mock(PlatformCompatibilityState.class), 10);
        ChatRequest request = request("run-sync-failure", "agent");
        service.register("run-sync-failure", "agent", request);

        IllegalStateException first =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.stream("run-sync-failure", "agent", request).blockLast());
        IllegalStateException second =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.stream("run-sync-failure", "agent", request).blockLast());

        assertTrue(first.getMessage().contains("synchronous runtime failure"));
        assertTrue(second.getMessage().contains("synchronous runtime failure"));
        verify(runtime, times(2)).stream(eq("agent"), any());
        service.failed("run-sync-failure");
    }

    @Test
    void durableRunStreamsHandleTwoFourAndEightConcurrentRoots() {
        PlatformStorageLayer storage = storage(tempDir);
        OrchestrationCheckpointStore checkpoints =
                new OrchestrationCheckpointStore(storage, "instance-a", 5_000);
        checkpoints.initialize();
        AgentRuntime runtime = mock(AgentRuntime.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(runtime.stream(eq("agent"), any()))
                .thenAnswer(
                        ignored ->
                                Flux.defer(
                                        () -> {
                                            int current = active.incrementAndGet();
                                            maxActive.accumulateAndGet(current, Math::max);
                                            return reactor.core.publisher.Mono.delay(
                                                            Duration.ofMillis(500))
                                                    .map(
                                                            tick ->
                                                                    new AgentEventEnvelope(
                                                                            "event-" + tick,
                                                                            "text_block_delta",
                                                                            Instant.now().toString(),
                                                                            "agent",
                                                                            "ok",
                                                                            Map.of()))
                                                    .doOnSuccess(
                                                            event ->
                                                                    active.decrementAndGet())
                                                    .flux();
                                        }));
        DurableAgentRunService service =
                new DurableAgentRunService(
                        checkpoints, runtime, mock(PlatformCompatibilityState.class), 10);

        for (int concurrency : List.of(2, 4, 8)) {
            maxActive.set(0);
            for (int index = 0; index < concurrency; index++) {
                String runId = "run-load-" + concurrency + "-" + index;
                service.register(runId, "agent", request(runId, "agent"));
            }

            reactor.core.publisher.Flux.range(0, concurrency)
                    .flatMap(
                            index -> {
                                String runId = "run-load-" + concurrency + "-" + index;
                                return service.stream(runId, "agent", request(runId, "agent"))
                                        .then();
                            },
                            concurrency)
                    .then()
                    .block(Duration.ofSeconds(10));

            assertEquals(concurrency, maxActive.get(), "root concurrency " + concurrency);
            assertEquals(0, active.get());
            for (int index = 0; index < concurrency; index++) {
                service.succeeded("run-load-" + concurrency + "-" + index);
            }
        }
    }

    @Test
    void suspendedWorkflowResumesWithoutTheOriginalStreamClient() {
        PlatformStorageLayer storage = storage(tempDir);
        OrchestrationCheckpointStore checkpoints =
                new OrchestrationCheckpointStore(storage, "instance-a", 5_000);
        checkpoints.initialize();
        AgentRuntime runtime = mock(AgentRuntime.class);
        PlatformCompatibilityState state = mock(PlatformCompatibilityState.class);
        WorkflowAssetService workflows = mock(WorkflowAssetService.class);
        WorkflowAsset workflow =
                new WorkflowAsset(
                        "approval-flow",
                        2,
                        "Approval",
                        "",
                        "platform",
                        "manual",
                        "PUBLISHED",
                        Map.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        "now",
                        "now",
                        "now");
        when(workflows.requirePublishedVersion("approval-flow", 2)).thenReturn(workflow);
        when(runtime.workflowStream(eq(workflow), any()))
                .thenReturn(
                        Flux.error(
                                new AgentRuntimeService.WorkflowWaitingException(
                                        "run-approval",
                                        "wait-approval",
                                        Map.of("status", "waiting"))),
                        Flux.just(
                                new AgentEventEnvelope(
                                        "event-resumed",
                                        "text_block_delta",
                                        Instant.now().toString(),
                                        "approval-flow",
                                        "approved result",
                                        Map.of())));
        DurableAgentRunService service =
                new DurableAgentRunService(checkpoints, runtime, state, workflows, 10);
        ChatRequest request = request("run-approval", "workflow:approval-flow");
        service.registerWorkflow("run-approval", workflow, request);

        Throwable waiting =
                assertThrows(
                        Throwable.class,
                        () -> service.workflowStream("run-approval", workflow, request).blockLast());
        assertTrue(DurableAgentRunService.isWorkflowWaiting(waiting));
        service.suspended("run-approval");
        assertTrue(service.resume("run-approval"));

        verify(state, timeout(5_000)).markRunRecovering("run-approval");
        verify(state, timeout(5_000)).finishRun("run-approval", "approved result");
        verify(runtime, times(2)).workflowStream(eq(workflow), any());
    }

    private static ChatRequest request(String runId, String agentId) {
        return new ChatRequest(
                "org",
                "user",
                "session",
                "test",
                TaskContext.root(runId, agentId, agentId, null));
    }

    private static PlatformStorageLayer storage(Path workspace) {
        return new PlatformStorageLayer(
                workspace.toString(),
                "sqlite",
                "jdbc:sqlite:" + workspace.resolve("durable-runs.db"),
                "platform_config",
                "platform_",
                "");
    }

    private static void expire(PlatformStorageLayer storage, String runId) throws Exception {
        try (var connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE platform_orchestration_runs SET lease_until=0 WHERE run_id=?")) {
            statement.setString(1, runId);
            statement.executeUpdate();
        }
    }
}
