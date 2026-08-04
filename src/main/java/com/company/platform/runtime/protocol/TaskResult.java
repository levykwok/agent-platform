/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime.protocol;

import java.util.Map;

public record TaskResult(
        String taskId,
        TaskStatus status,
        String content,
        Map<String, Object> data,
        TaskError error,
        Map<String, Object> usage) {
    public TaskResult {
        status = status == null ? TaskStatus.COMPLETED : status;
        data = data == null ? Map.of() : Map.copyOf(data);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
    }
}
