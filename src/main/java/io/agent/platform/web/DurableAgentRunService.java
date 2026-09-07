/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.runtime.AgentEventEnvelope;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.runtime.AgentRuntimeService;
import io.agent.platform.runtime.ChatImage;
import io.agent.platform.runtime.ChatRequest;
import io.agent.platform.runtime.OrchestrationCheckpointStore;
import io.agent.platform.runtime.protocol.TaskContext;
import io.agent.platform.control.WorkflowAsset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Owns durable Agent run leases, cancellation signals, and restart recovery. */
@Service
public class DurableAgentRunService {

    private final OrchestrationCheckpointStore checkpoints;
    private final AgentRuntime runtime;
    private final PlatformCompatibilityState state;
    private final WorkflowAssetService workflowAssets;
    private final int recoveryBatchSize;
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    @Autowired
    public DurableAgentRunService(
            OrchestrationCheckpointStore checkpoints,
            AgentRuntime runtime,
            PlatformCompatibilityState state,
            WorkflowAssetService workflowAssets,
            @Value("${agent.platform.orchestration.recovery-batch-size:10}")
                    int recoveryBatchSize) {
        this.checkpoints = checkpoints;
        this.runtime = runtime;
        this.state = state;
        this.workflowAssets = workflowAssets;
        this.recoveryBatchSize = Math.max(1, recoveryBatchSize);
    }

    /** Compatibility constructor used by focused durability tests. */
    public DurableAgentRunService(
            OrchestrationCheckpointStore checkpoints,
            AgentRuntime runtime,
            PlatformCompatibilityState state,
            int recoveryBatchSize) {
        this(checkpoints, runtime, state, null, recoveryBatchSize);
    }

    public void register(String runId, String agentId, ChatRequest request) {
        checkpoints.register(runId, requestContract(agentId, request));
        if (!checkpoints.acquire(runId)) {
            throw new LeaseLostException("Unable to acquire orchestration run lease: " + runId);
        }
    }

    public Flux<AgentEventEnvelope> stream(
            String runId, String agentId, ChatRequest request) {
        return ownedStream(runId, agentId, request);
    }

    public void registerWorkflow(String runId, WorkflowAsset workflow, ChatRequest request) {
        checkpoints.register(runId, workflowRequestContract(workflow, request));
        if (!checkpoints.acquire(runId)) {
            throw new LeaseLostException("Unable to acquire Workflow run lease: " + runId);
        }
    }

    public Flux<AgentEventEnvelope> workflowStream(
            String runId, WorkflowAsset workflow, ChatRequest request) {
        return ownedWorkflowStream(runId, workflow, request);
    }

    public boolean requestCancellation(String runId) {
        boolean accepted = checkpoints.requestCancel(runId);
        ActiveRun active = activeRuns.get(runId);
        if (accepted && active != null) {
            active.stop().tryEmitError(new RunCancelledException("运行已由用户取消"));
        }
        return accepted;
    }

    public void succeeded(String runId) {
        checkpoints.terminal(runId, "SUCCEEDED");
    }

    public void failed(String runId) {
        checkpoints.terminal(runId, "FAILED");
    }

    public void cancelled(String runId) {
        checkpoints.terminal(runId, "CANCELLED");
    }

    public void suspended(String runId) {
        if (!checkpoints.suspend(runId)) {
            throw new LeaseLostException("Unable to suspend Workflow run: " + runId);
        }
    }

    /** Resumes a durably suspended Workflow without requiring the original SSE client. */
    public boolean resume(String runId) {
        if (!checkpoints.resume(runId)) return false;
        recover(runId);
        return true;
    }

    public static boolean isCancellation(Throwable error) {
        return rootCause(error) instanceof CancellationException;
    }

    public static boolean isLeaseLost(Throwable error) {
        return rootCause(error) instanceof LeaseLostException;
    }

    public static boolean isWorkflowWaiting(Throwable error) {
        return rootCause(error) instanceof AgentRuntimeService.WorkflowWaitingException;
    }

    @Scheduled(
            fixedDelayString = "${agent.platform.orchestration.recovery-scan-ms:5000}",
            initialDelayString = "${agent.platform.orchestration.recovery-initial-delay-ms:5000}")
    public void recoverExpiredRuns() {
        for (String runId : checkpoints.claimAbandonedCancellations(recoveryBatchSize)) {
            state.cancelRun(runId, "运行取消请求已在实例恢复后完成");
            cancelled(runId);
        }
        for (String runId : checkpoints.claimRecoverable(recoveryBatchSize)) {
            if (!activeRuns.containsKey(runId)) {
                recover(runId);
            }
        }
    }

    private Flux<AgentEventEnvelope> ownedStream(
            String runId, String agentId, ChatRequest request) {
        return Flux.defer(
                () -> {
                    ActiveRun previous = activeRuns.get(runId);
                    if (previous != null) {
                        return Flux.error(
                                new IllegalStateException("Run is already active on this instance: " + runId));
                    }
                    Sinks.One<Void> stop = Sinks.one();
                    long heartbeatMs = Math.max(1_000L, checkpoints.leaseMs() / 3L);
                    Disposable heartbeat =
                            Flux.interval(Duration.ofMillis(heartbeatMs))
                                    .subscribe(
                                            ignored -> {
                                                if (checkpoints.cancellationRequested(runId)) {
                                                    stop.tryEmitError(
                                                            new RunCancelledException(
                                                                    "运行已由用户取消"));
                                                } else if (!checkpoints.renew(runId)) {
                                                    stop.tryEmitError(
                                                            new LeaseLostException(
                                                                    "Orchestration lease lost: " + runId));
                                                }
                                            },
                                            stop::tryEmitError);
                    ActiveRun active = new ActiveRun(stop, heartbeat);
                    activeRuns.put(runId, active);
                    return Flux.defer(() -> runtime.stream(agentId, request))
                            .takeUntilOther(stop.asMono())
                            .concatWith(
                                    Flux.defer(
                                            () ->
                                                    checkpoints.cancellationRequested(runId)
                                                            ? Flux.error(
                                                                    new RunCancelledException(
                                                                            "运行已由用户取消"))
                                                            : Flux.empty()))
                            .doFinally(
                                    signal -> {
                                        activeRuns.remove(runId, active);
                                        heartbeat.dispose();
                                    });
                });
    }

    private Flux<AgentEventEnvelope> ownedWorkflowStream(
            String runId, WorkflowAsset workflow, ChatRequest request) {
        return ownedPublisher(runId, () -> runtime.workflowStream(workflow, request));
    }

    private Flux<AgentEventEnvelope> ownedPublisher(
            String runId,
            java.util.function.Supplier<Flux<AgentEventEnvelope>> publisher) {
        return Flux.defer(
                () -> {
                    ActiveRun previous = activeRuns.get(runId);
                    if (previous != null) {
                        return Flux.error(
                                new IllegalStateException(
                                        "Run is already active on this instance: " + runId));
                    }
                    Sinks.One<Void> stop = Sinks.one();
                    long heartbeatMs = Math.max(1_000L, checkpoints.leaseMs() / 3L);
                    Disposable heartbeat =
                            Flux.interval(Duration.ofMillis(heartbeatMs))
                                    .subscribe(
                                            ignored -> {
                                                if (checkpoints.cancellationRequested(runId)) {
                                                    stop.tryEmitError(
                                                            new RunCancelledException(
                                                                    "运行已由用户取消"));
                                                } else if (!checkpoints.renew(runId)) {
                                                    stop.tryEmitError(
                                                            new LeaseLostException(
                                                                    "Orchestration lease lost: "
                                                                            + runId));
                                                }
                                            },
                                            stop::tryEmitError);
                    ActiveRun active = new ActiveRun(stop, heartbeat);
                    activeRuns.put(runId, active);
                    return Flux.defer(publisher)
                            .takeUntilOther(stop.asMono())
                            .concatWith(
                                    Flux.defer(
                                            () ->
                                                    checkpoints.cancellationRequested(runId)
                                                            ? Flux.error(
                                                                    new RunCancelledException(
                                                                            "运行已由用户取消"))
                                                            : Flux.empty()))
                            .doFinally(
                                    signal -> {
                                        activeRuns.remove(runId, active);
                                        heartbeat.dispose();
                                    });
                });
    }

    private void recover(String runId) {
        Map<String, Object> stored = checkpoints.request(runId).orElse(Map.of());
        if (stored.isEmpty()) {
            state.failRun(runId, new IllegalStateException("Missing durable run request"));
            failed(runId);
            return;
        }
        try {
            if ("workflow".equals(string(stored.get("runtime_kind")))) {
                recoverWorkflow(runId, stored);
                return;
            }
            String agentId = string(stored.get("agent_id"));
            ChatRequest request = requestFromContract(runId, agentId, stored);
            state.markRunRecovering(runId);
            StringBuilder answer = new StringBuilder();
            ownedStream(runId, agentId, request)
                    .subscribe(
                            event -> {
                                state.appendRunEventFromEnvelope(runId, event);
                                if (event.delta() != null) answer.append(event.delta());
                            },
                            error -> finishRecoveredFailure(runId, error),
                            () -> {
                                String text = answer.toString();
                                state.finishRun(runId, text);
                                state.appendSessionMessage(
                                        agentId,
                                        request.sessionId(),
                                        request.userId(),
                                        "assistant",
                                        text,
                                        Map.of("recovered", true, "run_id", runId));
                                succeeded(runId);
                            });
        } catch (RuntimeException error) {
            finishRecoveredFailure(runId, error);
        }
    }

    private void recoverWorkflow(String runId, Map<String, Object> stored) {
        if (workflowAssets == null) {
            throw new IllegalStateException("Workflow assets are unavailable during recovery");
        }
        String workflowId = string(stored.get("workflow_id"));
        int version = number(stored.get("workflow_version"), 0);
        WorkflowAsset workflow = workflowAssets.requirePublishedVersion(workflowId, version);
        ChatRequest request = requestFromContract(runId, "workflow:" + workflowId, stored);
        state.markRunRecovering(runId);
        StringBuilder answer = new StringBuilder();
        ownedWorkflowStream(runId, workflow, request)
                .subscribe(
                        event -> {
                            state.appendRunEventFromEnvelope(runId, event);
                            if (event.delta() != null) answer.append(event.delta());
                        },
                        error -> finishRecoveredFailure(runId, error),
                        () -> {
                            String text = answer.toString();
                            state.finishRun(runId, text);
                            state.appendSessionMessage(
                                    "workflow:" + workflowId,
                                    request.sessionId(),
                                    request.userId(),
                                    "assistant",
                                    text,
                                    Map.of(
                                            "recovered", true,
                                            "run_id", runId,
                                            "workflow_version", version));
                            succeeded(runId);
                        });
    }

    private void finishRecoveredFailure(String runId, Throwable error) {
        if (isWorkflowWaiting(error)) {
            suspended(runId);
        } else if (isCancellation(error)) {
            state.cancelRun(runId, message(error, "运行已取消"));
            cancelled(runId);
        } else if (!isLeaseLost(error)) {
            state.failRun(runId, error);
            failed(runId);
        }
    }

    private static Map<String, Object> requestContract(String agentId, ChatRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contract_version", "agent.run.request.v1");
        value.put("runtime_kind", "agent");
        value.put("agent_id", agentId);
        value.put("tenant_id", safe(request.tenantId()));
        value.put("user_id", safe(request.userId()));
        value.put("session_id", safe(request.sessionId()));
        value.put("message", safe(request.message()));
        value.put(
                "images",
                request.images().stream()
                        .map(
                                image ->
                                        Map.<String, Object>of(
                                                "url",
                                                image.url(),
                                                "data",
                                                image.data(),
                                                "media_type",
                                                image.mediaType()))
                        .toList());
        return Map.copyOf(value);
    }

    private static Map<String, Object> workflowRequestContract(
            WorkflowAsset workflow, ChatRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contract_version", "workflow.run.request.v1");
        value.put("runtime_kind", "workflow");
        value.put("workflow_id", workflow.workflowId());
        value.put("workflow_version", workflow.version());
        value.put("tenant_id", safe(request.tenantId()));
        value.put("user_id", safe(request.userId()));
        value.put("session_id", safe(request.sessionId()));
        value.put("message", safe(request.message()));
        value.put(
                "images",
                request.images().stream()
                        .map(
                                image ->
                                        Map.<String, Object>of(
                                                "url",
                                                image.url(),
                                                "data",
                                                image.data(),
                                                "media_type",
                                                image.mediaType()))
                        .toList());
        return Map.copyOf(value);
    }

    private static ChatRequest requestFromContract(
            String runId, String agentId, Map<String, Object> stored) {
        List<ChatImage> images = new ArrayList<>();
        if (stored.get("images") instanceof List<?> rows) {
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row)) continue;
                images.add(
                        new ChatImage(
                                string(row.get("url")),
                                string(row.get("data")),
                                string(row.get("media_type")).isBlank()
                                        ? "image/jpeg"
                                        : string(row.get("media_type"))));
            }
        }
        return new ChatRequest(
                string(stored.get("tenant_id")),
                string(stored.get("user_id")),
                string(stored.get("session_id")),
                string(stored.get("message")),
                TaskContext.root(runId, agentId, agentId, null),
                images);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error == null ? new IllegalStateException("Unknown run error") : error;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String message(Throwable error, String fallback) {
        Throwable cause = rootCause(error);
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? fallback
                : cause.getMessage();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int number(Object value, int fallback) {
        try {
            return value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private record ActiveRun(Sinks.One<Void> stop, Disposable heartbeat) {}

    public static final class RunCancelledException extends CancellationException {
        public RunCancelledException(String message) {
            super(message);
        }
    }

    public static final class LeaseLostException extends IllegalStateException {
        public LeaseLostException(String message) {
            super(message);
        }
    }
}
