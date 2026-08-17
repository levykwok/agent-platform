/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Stable v1 envelope for every nested Agent task invocation. */
public record AgentTaskEnvelope(
        String contractVersion,
        TaskRequest request,
        TaskResult result,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> metadata) {

    public static final String VERSION = "agent.task.v1";

    public AgentTaskEnvelope {
        contractVersion =
                contractVersion == null || contractVersion.isBlank()
                        ? VERSION
                        : contractVersion.trim();
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? startedAt : finishedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public long durationMs() {
        return Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
    }

    public String taskId() {
        return request == null || request.context() == null ? "" : request.context().taskId();
    }

    public static AgentTaskEnvelope completed(
            TaskRequest request,
            TaskResult result,
            Instant startedAt,
            Instant finishedAt,
            Map<String, Object> metadata) {
        return new AgentTaskEnvelope(VERSION, request, result, startedAt, finishedAt, metadata);
    }

    public static AgentTaskEnvelope failed(
            TaskRequest request,
            TaskStatus status,
            Throwable failure,
            Instant startedAt,
            Instant finishedAt,
            Map<String, Object> metadata) {
        status = status == null ? TaskStatus.FAILED : status;
        String message = failure == null ? "Task failed" : String.valueOf(failure.getMessage());
        TaskError error = new TaskError("TASK_" + status.name(), message, false, Map.of());
        TaskResult result =
                new TaskResult(
                        request == null || request.context() == null ? "" : request.context().taskId(),
                        status,
                        "",
                        Map.of(),
                        error,
                        Map.of("duration_ms", Math.max(0L, Duration.between(startedAt, finishedAt).toMillis())));
        return new AgentTaskEnvelope(VERSION, request, result, startedAt, finishedAt, metadata);
    }
}
