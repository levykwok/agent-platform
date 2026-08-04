/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;

public record WorkflowStep(
        String stepId,
        String agentId,
        String instruction,
        Long timeoutMs,
        Integer maxRetries,
        FailurePolicy failurePolicy,
        List<WorkflowTransition> transitions) {

    public enum FailurePolicy {
        FAIL_FAST,
        SKIP,
        USE_INPUT
    }

    /** Backward-compatible constructor for the original three-field workflow schema. */
    public WorkflowStep(String stepId, String agentId, String instruction) {
        this(stepId, agentId, instruction, null, null, null, List.of());
    }

    public WorkflowStep(
            String stepId,
            String agentId,
            String instruction,
            Long timeoutMs,
            Integer maxRetries,
            FailurePolicy failurePolicy) {
        this(stepId, agentId, instruction, timeoutMs, maxRetries, failurePolicy, List.of());
    }

    public WorkflowStep {
        timeoutMs = timeoutMs;
        maxRetries = maxRetries == null ? 0 : maxRetries;
        failurePolicy = failurePolicy == null ? FailurePolicy.FAIL_FAST : failurePolicy;
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }
}
