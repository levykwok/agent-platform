/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OrchestrationPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingSupervisorStepBudgetUsesBackwardCompatibleDefault() throws Exception {
        OrchestrationPolicy policy =
                objectMapper.readValue(
                        "{\"mode\":\"SUPERVISOR\",\"subagents\":[],\"routes\":[],\"pipeline\":[]}",
                        OrchestrationPolicy.class);

        assertEquals(OrchestrationPolicy.DEFAULT_MAX_SUPERVISOR_STEPS, policy.maxSupervisorSteps());
        assertFalse(policy.supervisorParallelEnabled());
        assertEquals(2, policy.maxSupervisorParallelism());
        assertFalse(policy.routerDisableThinking());
    }

    @Test
    void routerThinkingPolicyRoundTrips() throws Exception {
        OrchestrationPolicy policy =
                objectMapper.readValue(
                        "{\"mode\":\"ROUTER\",\"routerDisableThinking\":true}",
                        OrchestrationPolicy.class);

        assertTrue(policy.routerDisableThinking());
        OrchestrationPolicy restored =
                objectMapper.readValue(
                        objectMapper.writeValueAsString(policy), OrchestrationPolicy.class);
        assertTrue(restored.routerDisableThinking());
    }

    @Test
    void supervisorStepBudgetRoundTripsAndIsClamped() throws Exception {
        OrchestrationPolicy configured =
                objectMapper.readValue(
                        "{\"mode\":\"SUPERVISOR\",\"maxSupervisorSteps\":7}",
                        OrchestrationPolicy.class);
        OrchestrationPolicy excessive =
                objectMapper.readValue(
                        "{\"mode\":\"SUPERVISOR\",\"maxSupervisorSteps\":99}",
                        OrchestrationPolicy.class);

        assertEquals(7, configured.maxSupervisorSteps());
        assertEquals(OrchestrationPolicy.MAX_SUPERVISOR_STEPS, excessive.maxSupervisorSteps());
        assertEquals(
                7,
                objectMapper
                        .readValue(
                                objectMapper.writeValueAsString(configured),
                                OrchestrationPolicy.class)
                        .maxSupervisorSteps());
    }

    @Test
    void explicitSupervisorParallelPolicyRoundTrips() throws Exception {
        OrchestrationPolicy policy =
                objectMapper.readValue(
                        "{\"mode\":\"SUPERVISOR\",\"supervisorParallelEnabled\":true,\"maxSupervisorParallelism\":4}",
                        OrchestrationPolicy.class);

        assertTrue(policy.supervisorParallelEnabled());
        assertEquals(4, policy.maxSupervisorParallelism());
        OrchestrationPolicy restored =
                objectMapper.readValue(
                        objectMapper.writeValueAsString(policy), OrchestrationPolicy.class);
        assertTrue(restored.supervisorParallelEnabled());
        assertEquals(4, restored.maxSupervisorParallelism());
    }

    @Test
    void legacyWorkflowModeLoadsAsPipelineAndWritesCanonicalName() throws Exception {
        OrchestrationPolicy policy =
                objectMapper.readValue(
                        "{\"mode\":\"WORKFLOW\",\"workflow\":[]}",
                        OrchestrationPolicy.class);

        assertEquals(OrchestrationMode.PIPELINE, policy.mode());
        String serialized = objectMapper.writeValueAsString(policy);
        assertTrue(serialized.contains("\"mode\":\"PIPELINE\""));
        assertTrue(serialized.contains("\"pipeline\":[]"));
        assertFalse(serialized.contains("\"workflow\""));
    }
}
