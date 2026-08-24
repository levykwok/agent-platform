/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record TaskContext(
        String taskId,
        String parentTaskId,
        String rootTaskId,
        String sourceAgentId,
        String targetAgentId,
        String stepId,
        int depth,
        Instant deadlineAt,
        Map<String, Object> metadata) {

    /** Backward-compatible constructor for the original context contract. */
    public TaskContext(
            String taskId,
            String parentTaskId,
            String rootTaskId,
            String sourceAgentId,
            String targetAgentId,
            String stepId,
            Instant deadlineAt,
            Map<String, Object> metadata) {
        this(
                taskId,
                parentTaskId,
                rootTaskId,
                sourceAgentId,
                targetAgentId,
                stepId,
                parentTaskId == null || parentTaskId.isBlank() ? 0 : 1,
                deadlineAt,
                metadata);
    }

    public TaskContext {
        taskId = valueOrGenerated(taskId, "task");
        rootTaskId = valueOrGenerated(rootTaskId, taskId);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static TaskContext root(String sourceAgentId, String targetAgentId) {
        String taskId = generatedId();
        return new TaskContext(
                taskId, null, taskId, sourceAgentId, targetAgentId, null, 0, null, Map.of());
    }

    public static TaskContext root(
            String rootTaskId, String sourceAgentId, String targetAgentId, Instant deadlineAt) {
        String taskId = valueOrGenerated(rootTaskId, "task");
        return new TaskContext(
                taskId,
                null,
                taskId,
                sourceAgentId,
                targetAgentId,
                null,
                0,
                deadlineAt,
                Map.of());
    }

    public TaskContext child(String sourceAgentId, String targetAgentId, String stepId) {
        return new TaskContext(
                generatedId(),
                taskId,
                rootTaskId,
                sourceAgentId,
                targetAgentId,
                stepId,
                depth + 1,
                deadlineAt,
                metadata);
    }

    public TaskContext withTarget(String targetAgentId) {
        return new TaskContext(
                taskId,
                parentTaskId,
                rootTaskId,
                sourceAgentId,
                targetAgentId,
                stepId,
                depth,
                deadlineAt,
                metadata);
    }

    public TaskContext withMetadata(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(metadata);
        next.put(key, value);
        return new TaskContext(
                taskId,
                parentTaskId,
                rootTaskId,
                sourceAgentId,
                targetAgentId,
                stepId,
                depth,
                deadlineAt,
                next);
    }

    private static String valueOrGenerated(String value, String prefix) {
        return value == null || value.isBlank() ? prefix + "_" + generatedId() : value;
    }

    private static String generatedId() {
        return UUID.randomUUID().toString();
    }
}
