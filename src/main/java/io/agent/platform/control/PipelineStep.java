/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

/** One ordered Agent invocation in an Agent-owned Pipeline. */
public record PipelineStep(
        String stepId,
        String agentId,
        String instruction,
        Long timeoutMs,
        Integer maxRetries,
        FailurePolicy failurePolicy,
        List<PipelineTransition> transitions,
        @JsonAlias("parallel_group") String parallelGroup) {

    public enum FailurePolicy {
        FAIL_FAST,
        SKIP,
        USE_INPUT
    }

    public PipelineStep(String stepId, String agentId, String instruction) {
        this(stepId, agentId, instruction, null, null, null, List.of(), "");
    }

    public PipelineStep(
            String stepId,
            String agentId,
            String instruction,
            Long timeoutMs,
            Integer maxRetries,
            FailurePolicy failurePolicy) {
        this(stepId, agentId, instruction, timeoutMs, maxRetries, failurePolicy, List.of(), "");
    }

    public PipelineStep(
            String stepId,
            String agentId,
            String instruction,
            Long timeoutMs,
            Integer maxRetries,
            FailurePolicy failurePolicy,
            List<PipelineTransition> transitions) {
        this(
                stepId,
                agentId,
                instruction,
                timeoutMs,
                maxRetries,
                failurePolicy,
                transitions,
                "");
    }

    public PipelineStep {
        maxRetries = maxRetries == null ? 0 : maxRetries;
        failurePolicy = failurePolicy == null ? FailurePolicy.FAIL_FAST : failurePolicy;
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        parallelGroup = parallelGroup == null ? "" : parallelGroup.strip();
    }
}
