/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.platform.control.WorkflowStep;
import com.company.platform.control.WorkflowTransition;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowBranchingTest {

    @Test
    void matchingTransitionJumpsToTargetStep() {
        List<WorkflowStep> steps =
                List.of(
                        step(
                                "research",
                                new WorkflowTransition("needs_review", "reviewer"),
                                new WorkflowTransition("", "writer", true)),
                        step("reviewer"),
                        step("writer"));

        assertEquals(1, AgentRuntimeService.nextWorkflowIndex(steps, 0, "needs_review"));
    }

    @Test
    void defaultTransitionIsUsedWhenNoConditionMatches() {
        List<WorkflowStep> steps =
                List.of(
                        step(
                                "research",
                                new WorkflowTransition("needs_review", "reviewer"),
                                new WorkflowTransition("", "writer", true)),
                        step("reviewer"),
                        step("writer"));

        assertEquals(2, AgentRuntimeService.nextWorkflowIndex(steps, 0, "complete"));
    }

    @Test
    void normalWorkflowFallsThroughToNextListStep() {
        List<WorkflowStep> steps = List.of(step("research"), step("writer"));

        assertEquals(1, AgentRuntimeService.nextWorkflowIndex(steps, 0, "ordinary result"));
    }

    private static WorkflowStep step(String id, WorkflowTransition... transitions) {
        return new WorkflowStep(id, "agent-" + id, null, null, 0, null, List.of(transitions));
    }
}
