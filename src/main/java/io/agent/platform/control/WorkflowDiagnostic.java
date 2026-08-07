/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;

/** Structured feedback returned by draft and publish validation. */
public record WorkflowDiagnostic(
        Severity severity,
        String code,
        String nodeId,
        String portId,
        String edgeId,
        String message,
        List<String> suggestions) {

    public WorkflowDiagnostic {
        severity = severity == null ? Severity.ERROR : severity;
        code = code == null ? "WORKFLOW_INVALID" : code;
        nodeId = nodeId == null ? "" : nodeId;
        portId = portId == null ? "" : portId;
        edgeId = edgeId == null ? "" : edgeId;
        message = message == null ? "" : message;
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public enum Severity {
        ERROR,
        WARNING
    }
}
