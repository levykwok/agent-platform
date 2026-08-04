/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import java.time.Instant;
import java.util.Map;

public record ProgressEvent(
        String eventId,
        String taskId,
        String rootTaskId,
        String nodeId,
        String type,
        Instant createdAt,
        String summary,
        Map<String, Object> payload) {
    public ProgressEvent {
        createdAt = createdAt == null ? Instant.now() : createdAt;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
