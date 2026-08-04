/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime.protocol;

import java.util.Map;

public record TaskError(
        String code, String message, boolean retryable, Map<String, Object> details) {
    public TaskError {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
