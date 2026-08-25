/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

/** A forward-only branch between Pipeline steps. */
public record PipelineTransition(String when, String nextStepId, boolean defaultTransition) {

    public PipelineTransition(String when, String nextStepId) {
        this(when, nextStepId, false);
    }

    public PipelineTransition {
        when = when == null ? "" : when;
        nextStepId = nextStepId == null ? "" : nextStepId;
    }
}
