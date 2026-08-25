/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;

/** One ordered Agent invocation in an Agent-owned Pipeline. */
public record PipelineStep(
        String stepId,
        String agentId,
        String instruction,
        Long timeoutMs,
        Integer maxRetries,
        FailurePolicy failurePolicy,
        List<PipelineTransition> transitions) {

    public enum FailurePolicy {
        FAIL_FAST,
        SKIP,
        USE_INPUT
    }

    public PipelineStep(String stepId, String agentId, String instruction) {
        this(stepId, agentId, instruction, null, null, null, List.of());
    }

    public PipelineStep(
            String stepId,
            String agentId,
            String instruction,
            Long timeoutMs,
            Integer maxRetries,
            FailurePolicy failurePolicy) {
        this(stepId, agentId, instruction, timeoutMs, maxRetries, failurePolicy, List.of());
    }

    public PipelineStep {
        maxRetries = maxRetries == null ? 0 : maxRetries;
        failurePolicy = failurePolicy == null ? FailurePolicy.FAIL_FAST : failurePolicy;
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }
}
