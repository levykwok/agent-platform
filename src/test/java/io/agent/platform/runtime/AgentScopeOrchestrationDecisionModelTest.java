/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.OrchestrationPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentScopeOrchestrationDecisionModelTest {

    @Test
    void forcedRouterPolicyAddsDashScopeThinkingOverride() {
        AgentDefinition router = definition(OrchestrationMode.ROUTER, true);

        Object value =
                AgentScopeOrchestrationDecisionModel.decisionOptions(router)
                        .getAdditionalBodyParams()
                        .get("enable_thinking");

        assertEquals(Boolean.FALSE, value);
    }

    @Test
    void modelDefaultAndNonRouterCallsDoNotReceiveThinkingOverride() {
        AgentDefinition defaultRouter = definition(OrchestrationMode.ROUTER, false);
        AgentDefinition supervisor = definition(OrchestrationMode.SUPERVISOR, true);

        assertFalse(
                AgentScopeOrchestrationDecisionModel.decisionOptions(defaultRouter)
                        .getAdditionalBodyParams()
                        .containsKey("enable_thinking"));
        assertFalse(
                AgentScopeOrchestrationDecisionModel.decisionOptions(supervisor)
                        .getAdditionalBodyParams()
                        .containsKey("enable_thinking"));
    }

    private static AgentDefinition definition(OrchestrationMode mode, boolean disableThinking) {
        return new AgentDefinition(
                "decision-test",
                "v1",
                "Decision Test",
                "model",
                Map.of(),
                "system",
                true,
                Path.of("."),
                List.of(),
                List.of(),
                List.of(),
                new OrchestrationPolicy(
                        mode,
                        List.of(),
                        List.of(),
                        List.of(),
                        OrchestrationPolicy.DEFAULT_MAX_SUPERVISOR_STEPS,
                        false,
                        2,
                        disableThinking));
    }
}
