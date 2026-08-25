/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agent.platform.control.PipelineStep;
import io.agent.platform.control.PipelineTransition;
import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineBranchingTest {

    @Test
    void matchingTransitionJumpsToTargetStep() {
        List<PipelineStep> steps =
                List.of(
                        step(
                                "research",
                                new PipelineTransition("needs_review", "reviewer"),
                                new PipelineTransition("", "writer", true)),
                        step("reviewer"),
                        step("writer"));

        assertEquals(1, AgentRuntimeService.nextPipelineIndex(steps, 0, "needs_review"));
    }

    @Test
    void defaultTransitionIsUsedWhenNoConditionMatches() {
        List<PipelineStep> steps =
                List.of(
                        step(
                                "research",
                                new PipelineTransition("needs_review", "reviewer"),
                                new PipelineTransition("", "writer", true)),
                        step("reviewer"),
                        step("writer"));

        assertEquals(2, AgentRuntimeService.nextPipelineIndex(steps, 0, "complete"));
    }

    @Test
    void normalWorkflowFallsThroughToNextListStep() {
        List<PipelineStep> steps = List.of(step("research"), step("writer"));

        assertEquals(1, AgentRuntimeService.nextPipelineIndex(steps, 0, "ordinary result"));
    }

    private static PipelineStep step(String id, PipelineTransition... transitions) {
        return new PipelineStep(id, "agent-" + id, null, null, 0, null, List.of(transitions));
    }
}
