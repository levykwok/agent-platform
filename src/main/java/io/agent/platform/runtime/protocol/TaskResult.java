/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import java.util.Map;
import java.util.List;

public record TaskResult(
        String taskId,
        TaskStatus status,
        String content,
        Map<String, Object> data,
        String summary,
        List<Map<String, Object>> artifacts,
        TaskError error,
        Map<String, Object> usage) {

    /** Backward-compatible constructor for callers using the original task result fields. */
    public TaskResult(
            String taskId,
            TaskStatus status,
            String content,
            Map<String, Object> data,
            TaskError error,
            Map<String, Object> usage) {
        this(taskId, status, content, data, content, List.of(), error, usage);
    }

    public TaskResult {
        status = status == null ? TaskStatus.COMPLETED : status;
        data =
                data == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(
                                new java.util.LinkedHashMap<>(data));
        summary = summary == null ? content : summary;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        usage =
                usage == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(
                                new java.util.LinkedHashMap<>(usage));
    }

    public AgentBusinessResult businessResult() {
        AgentBusinessResult decoded = AgentBusinessResult.fromText(content);
        if (status == TaskStatus.COMPLETED
                && !AgentBusinessResult.SUCCEEDED.equals(decoded.status())) {
            return decoded;
        }
        return new AgentBusinessResult(
                status == TaskStatus.COMPLETED
                        ? AgentBusinessResult.SUCCEEDED
                        : AgentBusinessResult.FAILED,
                data,
                summary,
                artifacts,
                error);
    }
}
