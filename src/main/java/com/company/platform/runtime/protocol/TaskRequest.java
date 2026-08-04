/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime.protocol;

import java.util.Map;

public record TaskRequest(TaskContext context, Map<String, Object> input) {
    public TaskRequest {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
