/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowStepTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void legacyConstructorKeepsOriginalDefaults() {
        WorkflowStep step = new WorkflowStep("research", "researcher", "Analyze the request");

        assertEquals("research", step.stepId());
        assertEquals("researcher", step.agentId());
        assertEquals("Analyze the request", step.instruction());
        assertNull(step.timeoutMs());
        assertEquals(0, step.maxRetries());
        assertEquals(WorkflowStep.FailurePolicy.FAIL_FAST, step.failurePolicy());
    }

    @Test
    void yamlFieldsDeserializeIntoExecutionPolicy() throws Exception {
        WorkflowStep step =
                yaml.readValue(
                        "stepId: research\n"
                                + "agentId: researcher\n"
                                + "instruction: Analyze the request\n"
                                + "timeoutMs: 120000\n"
                                + "maxRetries: 2\n"
                                + "failurePolicy: USE_INPUT\n",
                        WorkflowStep.class);

        assertEquals(120000L, step.timeoutMs());
        assertEquals(2, step.maxRetries());
        assertEquals(WorkflowStep.FailurePolicy.USE_INPUT, step.failurePolicy());
    }

    @Test
    void yamlTransitionsDeserializeIntoStep() throws Exception {
        WorkflowStep step =
                yaml.readValue(
                        "stepId: research\n"
                                + "agentId: researcher\n"
                                + "transitions:\n"
                                + "  - when: needs_review\n"
                                + "    nextStepId: reviewer\n"
                                + "  - nextStepId: writer\n"
                                + "    defaultTransition: true\n",
                        WorkflowStep.class);

        assertEquals(2, step.transitions().size());
        assertEquals("needs_review", step.transitions().get(0).when());
        assertEquals("reviewer", step.transitions().get(0).nextStepId());
        assertEquals(true, step.transitions().get(1).defaultTransition());
    }

    @Test
    void absentPolicyFieldsUseSafeDefaults() throws Exception {
        WorkflowStep step =
                yaml.readValue(
                        "stepId: write\nagentId: writer\ninstruction: Write an answer\n",
                        WorkflowStep.class);

        assertNull(step.timeoutMs());
        assertEquals(0, step.maxRetries());
        assertEquals(WorkflowStep.FailurePolicy.FAIL_FAST, step.failurePolicy());
    }

    @Test
    void allFailurePoliciesAreExplicitlySupported() {
        assertEquals(
                Map.of(
                        "FAIL_FAST", WorkflowStep.FailurePolicy.FAIL_FAST,
                        "SKIP", WorkflowStep.FailurePolicy.SKIP,
                        "USE_INPUT", WorkflowStep.FailurePolicy.USE_INPUT),
                Map.of(
                        "FAIL_FAST", WorkflowStep.FailurePolicy.valueOf("FAIL_FAST"),
                        "SKIP", WorkflowStep.FailurePolicy.valueOf("SKIP"),
                        "USE_INPUT", WorkflowStep.FailurePolicy.valueOf("USE_INPUT")));
    }
}
