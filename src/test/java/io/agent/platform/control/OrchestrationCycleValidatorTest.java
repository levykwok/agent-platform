/*
 * Copyright 2026 by the company contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrchestrationCycleValidatorTest {

    @Test
    void detectsWorkflowAgentWorkflowCycle() {
        Map<String, AgentDefinition> definitions =
                definitions(
                        definition(
                                "flow-a",
                                new OrchestrationPolicy(
                                        OrchestrationMode.WORKFLOW,
                                        List.of(),
                                        List.of(),
                                        List.of(
                                                new WorkflowStep(
                                                        "call-b", "flow-b", "call b")))),
                        definition(
                                "flow-b",
                                new OrchestrationPolicy(
                                        OrchestrationMode.WORKFLOW,
                                        List.of(),
                                        List.of(),
                                        List.of(
                                                new WorkflowStep(
                                                        "call-a", "flow-a", "call a")))));

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> OrchestrationCycleValidator.validate(definitions));

        assertTrue(error.getMessage().contains("flow-a -> flow-b -> flow-a"));
    }

    @Test
    void allowsAcyclicNestedWorkflow() {
        Map<String, AgentDefinition> definitions =
                definitions(
                        definition(
                                "flow-a",
                                new OrchestrationPolicy(
                                        OrchestrationMode.WORKFLOW,
                                        List.of(),
                                        List.of(),
                                        List.of(
                                                new WorkflowStep(
                                                        "call-b", "flow-b", "call b")))),
                        definition(
                                "flow-b",
                                new OrchestrationPolicy(
                                        OrchestrationMode.WORKFLOW,
                                        List.of(),
                                        List.of(),
                                        List.of(
                                                new WorkflowStep(
                                                        "call-leaf", "leaf", "call leaf")))),
                        definition("leaf", OrchestrationPolicy.single()));

        assertDoesNotThrow(() -> OrchestrationCycleValidator.validate(definitions));
    }

    private static Map<String, AgentDefinition> definitions(AgentDefinition... values) {
        Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
        for (AgentDefinition value : values) {
            definitions.put(value.agentId(), value);
        }
        return definitions;
    }

    private static AgentDefinition definition(String id, OrchestrationPolicy policy) {
        return new AgentDefinition(
                id,
                "v1",
                id,
                "",
                Map.of(),
                "",
                true,
                Path.of("target", "cycle-test", id),
                List.of(),
                List.of(),
                List.of(),
                policy);
    }
}
