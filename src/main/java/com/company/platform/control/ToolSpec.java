/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import java.util.Map;

/** Definition of a tool that can be bound into an agent toolkit. */
public record ToolSpec(
        String toolId,
        String type,
        String className,
        String description,
        boolean enabled,
        String scriptPath,
        Map<String, Object> parameterSchema,
        long timeoutMs) {

    public ToolSpec {
        toolId = toolId == null || toolId.isBlank() ? className : toolId;
        type = type == null || type.isBlank() ? "java" : type.strip().toLowerCase();
        className = className == null ? "" : className.strip();
        scriptPath = scriptPath == null ? "" : scriptPath.strip();
        parameterSchema = parameterSchema == null ? Map.of() : Map.copyOf(parameterSchema);
        timeoutMs = timeoutMs <= 0 ? 5000 : timeoutMs;
    }
}
