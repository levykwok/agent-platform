/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

public record WorkflowTransition(String when, String nextStepId, boolean defaultTransition) {

    public WorkflowTransition(String when, String nextStepId) {
        this(when, nextStepId, false);
    }

    public WorkflowTransition {
        when = when == null ? "" : when;
        nextStepId = nextStepId == null ? "" : nextStepId;
    }
}
