/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import java.util.Map;

public record TaskRequest(TaskContext context, Map<String, Object> input) {
    public TaskRequest {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
