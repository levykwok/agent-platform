/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.adapter.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.OrchestrationPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentExecutionPolicyTest {

    @Test
    void safeDefaultsCloseHostAndSubagentAdjacentCapabilities() {
        AgentExecutionPolicy policy = AgentExecutionPolicy.from(definition(Map.of()));

        assertEquals(6, policy.maxIters());
        assertEquals(12, policy.maxToolCalls());
        assertEquals(120_000L, policy.timeoutMs());
        assertFalse(policy.fileRead());
        assertFalse(policy.fileWrite());
        assertFalse(policy.shell());
        assertFalse(policy.memoryTools());
    }

    @Test
    void explicitRuntimePolicyIsBoundedAndCapabilitiesAreOptIn() {
        AgentExecutionPolicy policy =
                AgentExecutionPolicy.from(
                        definition(
                                Map.of(
                                        "runtime",
                                        Map.of(
                                                "max_iters",
                                                999,
                                                "max_tool_calls",
                                                3,
                                                "timeout_ms",
                                                10),
                                        "capabilities",
                                        Map.of("file_read", true, "shell", true))));

        assertEquals(20, policy.maxIters());
        assertEquals(3, policy.maxToolCalls());
        assertEquals(1_000L, policy.timeoutMs());
        assertTrue(policy.fileRead());
        assertTrue(policy.shell());
        assertFalse(policy.fileWrite());
    }

    private AgentDefinition definition(Map<String, Object> modelPolicy) {
        return new AgentDefinition(
                "test",
                "v1",
                "test",
                "model",
                modelPolicy,
                "",
                true,
                Path.of("target", "policy-test"),
                List.of(),
                List.of(),
                List.of(),
                OrchestrationPolicy.single());
    }
}
