/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SubagentBindingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyBindingGetsFailFastDefaults() throws Exception {
        SubagentBinding binding =
                objectMapper.readValue(
                        "{\"bindingId\":\"research\",\"targetAgentId\":\"researcher\"}",
                        SubagentBinding.class);

        assertEquals(0, binding.maxRetries());
        assertEquals(SubagentBinding.FailurePolicy.FAIL_FAST, binding.failurePolicy());
        assertEquals("", binding.fallbackAgentId());
    }

    @Test
    void resiliencePolicyRoundTrips() throws Exception {
        SubagentBinding binding =
                objectMapper.readValue(
                        "{\"bindingId\":\"research\",\"targetAgentId\":\"primary\","
                                + "\"timeoutMs\":5000,\"maxRetries\":2,"
                                + "\"failurePolicy\":\"FALLBACK\",\"fallbackAgentId\":\"backup\"}",
                        SubagentBinding.class);

        assertEquals(5_000L, binding.timeoutMs());
        assertEquals(2, binding.maxRetries());
        assertEquals(SubagentBinding.FailurePolicy.FALLBACK, binding.failurePolicy());
        assertEquals("backup", binding.fallbackAgentId());
        assertEquals(
                binding,
                objectMapper.readValue(
                        objectMapper.writeValueAsString(binding), SubagentBinding.class));
    }
}
