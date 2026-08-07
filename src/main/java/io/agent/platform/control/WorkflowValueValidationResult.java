/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;

public record WorkflowValueValidationResult(boolean valid, List<String> errors) {
    public WorkflowValueValidationResult { errors = errors == null ? List.of() : List.copyOf(errors); }
}
