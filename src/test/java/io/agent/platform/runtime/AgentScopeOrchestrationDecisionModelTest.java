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
        AgentDefinition router = definition(OrchestrationMode.ROUTER, true, false);

        Object value =
                AgentScopeOrchestrationDecisionModel.decisionOptions(router)
                        .getAdditionalBodyParams()
                        .get("enable_thinking");

        assertEquals(Boolean.FALSE, value);
    }

    @Test
    void supervisorPolicyAlsoDisablesThinkingWhileExplicitOptOutRestoresModelDefault() {
        AgentDefinition defaultRouter = definition(OrchestrationMode.ROUTER, false, true);
        AgentDefinition supervisor = definition(OrchestrationMode.SUPERVISOR, false, true);
        AgentDefinition defaultSupervisor =
                definition(OrchestrationMode.SUPERVISOR, true, false);

        assertFalse(
                AgentScopeOrchestrationDecisionModel.decisionOptions(defaultRouter)
                        .getAdditionalBodyParams()
                        .containsKey("enable_thinking"));
        assertEquals(
                Boolean.FALSE,
                AgentScopeOrchestrationDecisionModel.decisionOptions(supervisor)
                        .getAdditionalBodyParams()
                        .get("enable_thinking"));
        assertFalse(
                AgentScopeOrchestrationDecisionModel.decisionOptions(defaultSupervisor)
                        .getAdditionalBodyParams()
                        .containsKey("enable_thinking"));
    }

    private static AgentDefinition definition(
            OrchestrationMode mode, boolean routerDisableThinking, boolean supervisorDisableThinking) {
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
                        routerDisableThinking,
                        supervisorDisableThinking));
    }
}
