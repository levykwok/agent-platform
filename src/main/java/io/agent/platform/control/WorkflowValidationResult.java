/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;

/** Result of validating typed workflow ports and edges. */
public record WorkflowValidationResult(boolean valid, List<WorkflowDiagnostic> diagnostics) {

    public WorkflowValidationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.severity() == WorkflowDiagnostic.Severity.ERROR);
    }
}
