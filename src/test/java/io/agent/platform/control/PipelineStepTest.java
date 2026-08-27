/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineStepTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void legacyConstructorKeepsOriginalDefaults() {
        PipelineStep step = new PipelineStep("research", "researcher", "Analyze the request");

        assertEquals("research", step.stepId());
        assertEquals("researcher", step.agentId());
        assertEquals("Analyze the request", step.instruction());
        assertNull(step.timeoutMs());
        assertEquals(0, step.maxRetries());
        assertEquals(PipelineStep.FailurePolicy.FAIL_FAST, step.failurePolicy());
        assertEquals("", step.parallelGroup());
    }

    @Test
    void parallelGroupSupportsSnakeCaseAlias() throws Exception {
        PipelineStep step =
                yaml.readValue(
                        "stepId: facts\nagentId: researcher\nparallel_group: gather\n",
                        PipelineStep.class);

        assertEquals("gather", step.parallelGroup());
    }

    @Test
    void yamlFieldsDeserializeIntoExecutionPolicy() throws Exception {
        PipelineStep step =
                yaml.readValue(
                        "stepId: research\n"
                                + "agentId: researcher\n"
                                + "instruction: Analyze the request\n"
                                + "timeoutMs: 120000\n"
                                + "maxRetries: 2\n"
                                + "failurePolicy: USE_INPUT\n",
                        PipelineStep.class);

        assertEquals(120000L, step.timeoutMs());
        assertEquals(2, step.maxRetries());
        assertEquals(PipelineStep.FailurePolicy.USE_INPUT, step.failurePolicy());
    }

    @Test
    void yamlTransitionsDeserializeIntoStep() throws Exception {
        PipelineStep step =
                yaml.readValue(
                        "stepId: research\n"
                                + "agentId: researcher\n"
                                + "transitions:\n"
                                + "  - when: needs_review\n"
                                + "    nextStepId: reviewer\n"
                                + "  - nextStepId: writer\n"
                                + "    defaultTransition: true\n",
                        PipelineStep.class);

        assertEquals(2, step.transitions().size());
        assertEquals("needs_review", step.transitions().get(0).when());
        assertEquals("reviewer", step.transitions().get(0).nextStepId());
        assertEquals(true, step.transitions().get(1).defaultTransition());
    }

    @Test
    void absentPolicyFieldsUseSafeDefaults() throws Exception {
        PipelineStep step =
                yaml.readValue(
                        "stepId: write\nagentId: writer\ninstruction: Write an answer\n",
                        PipelineStep.class);

        assertNull(step.timeoutMs());
        assertEquals(0, step.maxRetries());
        assertEquals(PipelineStep.FailurePolicy.FAIL_FAST, step.failurePolicy());
    }

    @Test
    void allFailurePoliciesAreExplicitlySupported() {
        assertEquals(
                Map.of(
                        "FAIL_FAST", PipelineStep.FailurePolicy.FAIL_FAST,
                        "SKIP", PipelineStep.FailurePolicy.SKIP,
                        "USE_INPUT", PipelineStep.FailurePolicy.USE_INPUT),
                Map.of(
                        "FAIL_FAST", PipelineStep.FailurePolicy.valueOf("FAIL_FAST"),
                        "SKIP", PipelineStep.FailurePolicy.valueOf("SKIP"),
                        "USE_INPUT", PipelineStep.FailurePolicy.valueOf("USE_INPUT")));
    }
}
