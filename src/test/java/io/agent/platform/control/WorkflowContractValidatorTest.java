/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowContractValidatorTest {

    private final WorkflowContractValidator validator = new WorkflowContractValidator();

    @Test
    void rejectsDisconnectedNodesAndAnUnconnectedRequiredOutputBoundary() {
        WorkflowValidationResult result =
                validator.validate(
                        List.of(node("input", WorkflowNodeType.INPUT),
                                node("orphan", WorkflowNodeType.DATA_TRANSFORM),
                                node("output", WorkflowNodeType.OUTPUT)),
                        List.of());

        assertFalse(result.valid());
        assertTrue(codes(result).contains("REQUIRED_INPUT_UNCONNECTED"));
        assertTrue(codes(result).contains("NODE_UNREACHABLE_FROM_INPUT"));
        assertTrue(codes(result).contains("NODE_CANNOT_REACH_OUTPUT"));
    }

    @Test
    void rejectsAmbiguousBranchAndSingleCardinalityFanIn() {
        List<WorkflowNode> nodes =
                List.of(
                        node("input", WorkflowNodeType.INPUT),
                        node("worker", WorkflowNodeType.DATA_TRANSFORM),
                        node("left", WorkflowNodeType.DATA_TRANSFORM),
                        node("right", WorkflowNodeType.DATA_TRANSFORM),
                        node("output", WorkflowNodeType.OUTPUT));
        List<WorkflowEdge> edges =
                List.of(
                        edge("e1", "input", "worker"),
                        edge("e2", "worker", "left"),
                        edge("e3", "worker", "right"),
                        edge("e4", "left", "output"),
                        edge("e5", "right", "output"));

        WorkflowValidationResult result = validator.validate(nodes, edges);

        assertFalse(result.valid());
        assertTrue(codes(result).contains("AMBIGUOUS_NODE_BRANCH"));
        assertTrue(codes(result).contains("INPUT_CARDINALITY_EXCEEDED"));
    }

    @Test
    void joinDefaultsToManyAndAcceptsParallelFanIn() {
        List<WorkflowNode> nodes =
                List.of(
                        node("input", WorkflowNodeType.INPUT),
                        node("parallel", WorkflowNodeType.PARALLEL),
                        node("left", WorkflowNodeType.DATA_TRANSFORM),
                        node("right", WorkflowNodeType.DATA_TRANSFORM),
                        node("join", WorkflowNodeType.JOIN),
                        node("output", WorkflowNodeType.OUTPUT));
        List<WorkflowEdge> edges =
                List.of(
                        edge("e1", "input", "parallel"),
                        edge("e2", "parallel", "left"),
                        edge("e3", "parallel", "right"),
                        edge("e4", "left", "join"),
                        edge("e5", "right", "join"),
                        edge("e6", "join", "output"));

        WorkflowValidationResult result = validator.validate(nodes, edges);

        assertTrue(result.valid(), () -> result.diagnostics().toString());
    }

    @Test
    void rejectsParallelBranchesWithoutACommonJoin() {
        List<WorkflowNode> nodes =
                List.of(
                        node("input", WorkflowNodeType.INPUT),
                        node("parallel", WorkflowNodeType.PARALLEL),
                        node("left", WorkflowNodeType.DATA_TRANSFORM),
                        node("right", WorkflowNodeType.DATA_TRANSFORM),
                        node("output", WorkflowNodeType.OUTPUT));
        WorkflowValidationResult result =
                validator.validate(
                        nodes,
                        List.of(
                                edge("e1", "input", "parallel"),
                                edge("e2", "parallel", "left"),
                                edge("e3", "parallel", "right"),
                                edge("e4", "left", "output"),
                                edge("e5", "right", "output")));

        assertFalse(result.valid());
        assertTrue(codes(result).contains("PARALLEL_JOIN_INVALID"));
    }

    private static WorkflowNode node(String id, WorkflowNodeType type) {
        return new WorkflowNode(id, type, "", "", null, 0, null, List.of(), List.of());
    }

    private static WorkflowEdge edge(String id, String from, String to) {
        return new WorkflowEdge(
                id,
                new WorkflowEndpoint(from, "value"),
                new WorkflowEndpoint(to, "value"),
                "data",
                Map.of());
    }

    private static List<String> codes(WorkflowValidationResult result) {
        return result.diagnostics().stream().map(WorkflowDiagnostic::code).toList();
    }
}
