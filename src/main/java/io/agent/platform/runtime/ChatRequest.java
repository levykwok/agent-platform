/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.runtime.protocol.TaskContext;

public record ChatRequest(
        String tenantId,
        String userId,
        String sessionId,
        String message,
        TaskContext taskContext,
        java.util.List<ChatImage> images) {

    public ChatRequest {
        taskContext = taskContext == null ? TaskContext.root(null, null) : taskContext;
        images = images == null ? java.util.List.of() : java.util.List.copyOf(images);
    }

    public ChatRequest(String tenantId, String userId, String sessionId, String message) {
        this(
                tenantId,
                userId,
                sessionId,
                message,
                TaskContext.root(null, null),
                java.util.List.of());
    }

    public ChatRequest(
            String tenantId,
            String userId,
            String sessionId,
            String message,
            TaskContext taskContext) {
        this(tenantId, userId, sessionId, message, taskContext, java.util.List.of());
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }
}
