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
        Instant deadlineAt,
        Map<String, Object> metadata) {

    public TaskContext {
        taskId = valueOrGenerated(taskId, "task");
        rootTaskId = valueOrGenerated(rootTaskId, taskId);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static TaskContext root(String sourceAgentId, String targetAgentId) {
        String taskId = generatedId();
        return new TaskContext(
                taskId, null, taskId, sourceAgentId, targetAgentId, null, null, Map.of());
    }

    public TaskContext child(String sourceAgentId, String targetAgentId, String stepId) {
        return new TaskContext(
                generatedId(),
                taskId,
                rootTaskId,
                sourceAgentId,
                targetAgentId,
                stepId,
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
