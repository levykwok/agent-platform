/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static validation for typed workflow ports, contracts and edges. */
public final class WorkflowContractValidator {

    public WorkflowValidationResult validate(WorkflowAsset asset) {
        return asset == null ? validate(List.of(), List.of()) : validate(asset.nodes(), asset.edges());
    }

    public WorkflowValidationResult validate(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        List<WorkflowDiagnostic> diagnostics = new ArrayList<>();
        List<WorkflowNode> safeNodes = nodes == null ? List.of() : nodes;
        Map<String, WorkflowNode> nodeById = new LinkedHashMap<>();
        for (WorkflowNode node : safeNodes) {
            if (node == null || node.nodeId() == null || node.nodeId().isBlank()) {
                diagnostics.add(error("NODE_ID_REQUIRED", "", "", "", "Workflow node id is required"));
                continue;
            }
            if (nodeById.putIfAbsent(node.nodeId(), node) != null) {
                diagnostics.add(error("DUPLICATE_NODE_ID", node.nodeId(), "", "", "Workflow node id is duplicated: " + node.nodeId()));
            }
        }
        List<WorkflowEdge> safeEdges = edges == null ? List.of() : edges;
        Map<String, WorkflowEdge> edgeById = new HashMap<>();
        Map<String, Set<String>> incoming = new HashMap<>();
        Map<String, Set<String>> graph = new HashMap<>();
        for (WorkflowEdge edge : safeEdges) {
            validateEdge(edge, nodeById, edgeById, incoming, graph, diagnostics);
        }
        validateRequiredInputs(safeNodes, incoming, diagnostics);
        validateInputCardinality(safeNodes, incoming, diagnostics);
        validateCycles(graph, diagnostics);
        validateTopology(safeNodes, safeEdges, nodeById, graph, diagnostics);
        return result(diagnostics);
    }

    private void validateEdge(
            WorkflowEdge edge,
            Map<String, WorkflowNode> nodeById,
            Map<String, WorkflowEdge> edgeById,
            Map<String, Set<String>> incoming,
            Map<String, Set<String>> graph,
            List<WorkflowDiagnostic> diagnostics) {
        if (edge == null) {
            diagnostics.add(error("EDGE_MISSING", "", "", "", "Workflow edge is missing"));
            return;
        }
        if (edge.edgeId().isBlank()) {
            diagnostics.add(error("EDGE_ID_REQUIRED", "", "", "", "Workflow edge id is required"));
        } else if (edgeById.putIfAbsent(edge.edgeId(), edge) != null) {
            diagnostics.add(error("DUPLICATE_EDGE_ID", "", "", edge.edgeId(), "Workflow edge id is duplicated"));
        }
        if (edge.from() == null || edge.to() == null) {
            diagnostics.add(error("EDGE_ENDPOINT_REQUIRED", "", "", edge.edgeId(), "Both source and target ports are required"));
            return;
        }
        WorkflowNode sourceNode = nodeById.get(edge.from().nodeId());
        WorkflowNode targetNode = nodeById.get(edge.to().nodeId());
        if (sourceNode == null || targetNode == null) {
            diagnostics.add(error("EDGE_NODE_NOT_FOUND", sourceNode == null ? edge.from().nodeId() : edge.to().nodeId(), "", edge.edgeId(), "Edge references a node that does not exist"));
            return;
        }
        WorkflowPort source = findPort(sourceNode.outputPorts(), edge.from().portId());
        WorkflowPort target = findPort(targetNode.inputPorts(), edge.to().portId());
        if (source == null) {
            diagnostics.add(error("SOURCE_PORT_NOT_FOUND", sourceNode.nodeId(), edge.from().portId(), edge.edgeId(), "Source output port was not found"));
        }
        if (target == null) {
            diagnostics.add(error("TARGET_PORT_NOT_FOUND", targetNode.nodeId(), edge.to().portId(), edge.edgeId(), "Target input port was not found"));
        }
        if (source == null || target == null) {
            return;
        }
        if (!source.output() || !target.input()) {
            diagnostics.add(error("PORT_DIRECTION_INVALID", targetNode.nodeId(), target.portId(), edge.edgeId(), "Data must flow from an output port to an input port"));
        }
        if (!edge.data() && !edge.control()) {
            diagnostics.add(error("EDGE_KIND_INVALID", targetNode.nodeId(), target.portId(), edge.edgeId(), "Edge kind must be data or control"));
        }
        if (edge.data()) {
            validateContractCompatibility(source, target, edge, diagnostics);
            incoming.computeIfAbsent(targetNode.nodeId() + ":" + target.portId(), ignored -> new HashSet<>()).add(edge.edgeId());
        }
        graph.computeIfAbsent(sourceNode.nodeId(), ignored -> new HashSet<>()).add(targetNode.nodeId());
    }

    private void validateRequiredInputs(List<WorkflowNode> nodes, Map<String, Set<String>> incoming, List<WorkflowDiagnostic> diagnostics) {
        for (WorkflowNode node : nodes) {
            if (node == null) continue;
            for (WorkflowPort port : node.inputPorts()) {
                if (port.required() && incoming.getOrDefault(node.nodeId() + ":" + port.portId(), Set.of()).isEmpty()) {
                    diagnostics.add(error("REQUIRED_INPUT_UNCONNECTED", node.nodeId(), port.portId(), "", "Required input port has no data edge"));
                }
            }
        }
    }

    private void validateInputCardinality(
            List<WorkflowNode> nodes,
            Map<String, Set<String>> incoming,
            List<WorkflowDiagnostic> diagnostics) {
        for (WorkflowNode node : nodes) {
            if (node == null) continue;
            for (WorkflowPort port : node.inputPorts()) {
                int count =
                        incoming.getOrDefault(node.nodeId() + ":" + port.portId(), Set.of())
                                .size();
                if (count > 1 && !"many".equals(port.cardinality())) {
                    diagnostics.add(
                            error(
                                    "INPUT_CARDINALITY_EXCEEDED",
                                    node.nodeId(),
                                    port.portId(),
                                    "",
                                    "Input port accepts one value but has "
                                            + count
                                            + " incoming data edges"));
                }
            }
        }
    }

    private void validateContractCompatibility(WorkflowPort source, WorkflowPort target, WorkflowEdge edge, List<WorkflowDiagnostic> diagnostics) {
        String sourceRef = source.contractRef();
        String targetRef = target.contractRef();
        if (sourceRef.isBlank() || targetRef.isBlank() || sourceRef.equals(targetRef)) {
            if (!sourceRef.isBlank() && targetRef.isBlank()) {
                diagnostics.add(warning("TARGET_CONTRACT_INHERITED", "", target.portId(), edge.edgeId(), "Target contract will inherit the source contract"));
            }
            return;
        }
        if (source.schema().isEmpty() || target.schema().isEmpty()) {
            diagnostics.add(error("CONTRACT_UNRESOLVED", "", target.portId(), edge.edgeId(), "Contracts " + sourceRef + " and " + targetRef + " differ and one schema is unavailable"));
            return;
        }
        if (!objectSchemasCompatible(source.schema(), target.schema(), edge.binding())) {
            diagnostics.add(new WorkflowDiagnostic(WorkflowDiagnostic.Severity.ERROR, "CONTRACT_INCOMPATIBLE", "", target.portId(), edge.edgeId(), "Source contract " + sourceRef + " is not compatible with target contract " + targetRef, List.of("Add a field mapping", "Add a data.transform node")));
        } else {
            diagnostics.add(warning("CONTRACT_MAPPING_REVIEW", "", target.portId(), edge.edgeId(), "Contracts are structurally compatible; review the field mapping"));
        }
    }

    private boolean objectSchemasCompatible(Map<String, Object> source, Map<String, Object> target, Map<String, Object> binding) {
        String sourceType = text(source.get("type"));
        String targetType = text(target.get("type"));
        if (!sourceType.isBlank() && !targetType.isBlank() && !typesCompatible(sourceType, targetType)) return false;
        Object sourcePropertiesValue = source.get("properties");
        Object targetPropertiesValue = target.get("properties");
        if (!(sourcePropertiesValue instanceof Map<?, ?> sourceProperties) || !(targetPropertiesValue instanceof Map<?, ?> targetProperties)) return true;
        for (String field : stringSet(target.get("required"))) {
            Object targetField = targetProperties.get(field);
            Object sourceField = sourceProperties.get(field);
            Object mappedSource = binding.get(field);
            if (sourceField == null && mappedSource == null) return false;
            if (sourceField instanceof Map<?, ?> sourceSchema && targetField instanceof Map<?, ?> targetSchema
                    && !typesCompatible(text(sourceSchema.get("type")), text(targetSchema.get("type")))) return false;
        }
        return true;
    }

    private void validateCycles(Map<String, Set<String>> graph, List<WorkflowDiagnostic> diagnostics) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String nodeId : graph.keySet()) {
            if (hasCycle(nodeId, graph, visiting, visited)) {
                diagnostics.add(error("WORKFLOW_CYCLE", nodeId, "", "", "Workflow edges contain a cycle; add an explicit loop node before enabling cycles"));
                return;
            }
        }
    }

    private void validateTopology(
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges,
            Map<String, WorkflowNode> nodeById,
            Map<String, Set<String>> graph,
            List<WorkflowDiagnostic> diagnostics) {
        List<WorkflowNode> inputs =
                nodes.stream().filter(node -> node != null && node.type() == WorkflowNodeType.INPUT).toList();
        List<WorkflowNode> outputs =
                nodes.stream().filter(node -> node != null && node.type() == WorkflowNodeType.OUTPUT).toList();
        if (inputs.size() != 1 || outputs.size() != 1) return;
        String inputId = inputs.get(0).nodeId();
        String outputId = outputs.get(0).nodeId();
        Map<String, Integer> incomingCount = new HashMap<>();
        Map<String, Integer> outgoingCount = new HashMap<>();
        Map<String, Set<String>> reverse = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            if (edge == null || edge.from() == null || edge.to() == null) continue;
            if (!nodeById.containsKey(edge.from().nodeId())
                    || !nodeById.containsKey(edge.to().nodeId())) continue;
            outgoingCount.merge(edge.from().nodeId(), 1, Integer::sum);
            incomingCount.merge(edge.to().nodeId(), 1, Integer::sum);
            reverse.computeIfAbsent(edge.to().nodeId(), ignored -> new HashSet<>())
                    .add(edge.from().nodeId());
            if (outputId.equals(edge.from().nodeId())) {
                diagnostics.add(
                        error(
                                "OUTPUT_HAS_OUTGOING_EDGE",
                                outputId,
                                edge.from().portId(),
                                edge.edgeId(),
                                "workflow.output cannot have outgoing edges"));
            }
            if (inputId.equals(edge.to().nodeId())) {
                diagnostics.add(
                        error(
                                "INPUT_HAS_INCOMING_EDGE",
                                inputId,
                                edge.to().portId(),
                                edge.edgeId(),
                                "workflow.input cannot have incoming edges"));
            }
        }
        Set<String> reachableFromInput = reachable(inputId, graph);
        Set<String> reachesOutput = reachable(outputId, reverse);
        for (WorkflowNode node : nodes) {
            if (node == null) continue;
            if (!reachableFromInput.contains(node.nodeId())) {
                diagnostics.add(
                        error(
                                "NODE_UNREACHABLE_FROM_INPUT",
                                node.nodeId(),
                                "",
                                "",
                                "Node is not reachable from workflow.input"));
            }
            if (!reachesOutput.contains(node.nodeId())) {
                diagnostics.add(
                        error(
                                "NODE_CANNOT_REACH_OUTPUT",
                                node.nodeId(),
                                "",
                                "",
                                "Node cannot reach workflow.output"));
            }
            int outgoing = outgoingCount.getOrDefault(node.nodeId(), 0);
            if (outgoing > 1
                    && node.type() != WorkflowNodeType.PARALLEL
                    && node.type() != WorkflowNodeType.CONDITION) {
                diagnostics.add(
                        error(
                                "AMBIGUOUS_NODE_BRANCH",
                                node.nodeId(),
                                "",
                                "",
                                "Only parallel and condition nodes may have multiple outgoing edges"));
            }
            long parallelDataEdges =
                    node.type() == WorkflowNodeType.PARALLEL
                            ? edges.stream()
                                    .filter(
                                            edge ->
                                                    edge != null
                                                            && edge.from() != null
                                                            && node.nodeId()
                                                                    .equals(
                                                                            edge.from()
                                                                                    .nodeId())
                                                            && edge.data())
                                    .count()
                            : 0;
            if (node.type() == WorkflowNodeType.PARALLEL && parallelDataEdges < 2) {
                diagnostics.add(
                        error(
                                "PARALLEL_BRANCHES_REQUIRED",
                                node.nodeId(),
                                "",
                                "",
                                "parallel requires at least two outgoing branches"));
            }
            if (node.type() == WorkflowNodeType.PARALLEL && parallelDataEdges >= 2) {
                validateParallelConvergence(
                        node, edges, nodeById, graph, diagnostics);
            }
            if (node.type() == WorkflowNodeType.JOIN
                    && incomingCount.getOrDefault(node.nodeId(), 0) < 2) {
                diagnostics.add(
                        error(
                                "JOIN_INPUTS_REQUIRED",
                                node.nodeId(),
                                "",
                                "",
                                "join requires at least two incoming branches"));
            }
            if (node.type() == WorkflowNodeType.CONDITION) {
                long controlEdges =
                        edges.stream()
                                .filter(
                                        edge ->
                                                edge != null
                                                        && edge.from() != null
                                                        && node.nodeId().equals(edge.from().nodeId())
                                                        && edge.control())
                                .count();
                long defaults =
                        edges.stream()
                                .filter(
                                        edge ->
                                                edge != null
                                                        && edge.from() != null
                                                        && node.nodeId().equals(edge.from().nodeId())
                                                        && edge.defaultEdge())
                                .count();
                if (controlEdges < 2 || defaults != 1) {
                    diagnostics.add(
                            error(
                                    "CONDITION_BRANCHES_INVALID",
                                    node.nodeId(),
                                    "",
                                    "",
                                    "condition requires at least two control edges and exactly one default edge"));
                }
            }
        }
    }

    private void validateParallelConvergence(
            WorkflowNode parallel,
            List<WorkflowEdge> edges,
            Map<String, WorkflowNode> nodeById,
            Map<String, Set<String>> graph,
            List<WorkflowDiagnostic> diagnostics) {
        List<String> branchStarts =
                edges.stream()
                        .filter(
                                edge ->
                                        edge != null
                                                && edge.from() != null
                                                && edge.to() != null
                                                && parallel.nodeId()
                                                        .equals(edge.from().nodeId())
                                                && edge.data())
                        .map(edge -> edge.to().nodeId())
                        .toList();
        Set<String> commonJoins = null;
        boolean nestedParallel = false;
        for (String branchStart : branchStarts) {
            Set<String> reachable = reachableUntilJoin(branchStart, graph, nodeById);
            Set<String> joins = new HashSet<>();
            for (String nodeId : reachable) {
                WorkflowNode candidate = nodeById.get(nodeId);
                if (candidate == null) continue;
                if (candidate.type() == WorkflowNodeType.JOIN) joins.add(nodeId);
                if (!parallel.nodeId().equals(nodeId)
                        && candidate.type() == WorkflowNodeType.PARALLEL) {
                    nestedParallel = true;
                }
            }
            if (commonJoins == null) commonJoins = joins;
            else commonJoins.retainAll(joins);
        }
        if (commonJoins == null || commonJoins.isEmpty()) {
            diagnostics.add(
                    error(
                            "PARALLEL_JOIN_INVALID",
                            parallel.nodeId(),
                            "",
                            "",
                            "Every parallel branch must converge on a common join node"));
        }
        if (nestedParallel) {
            diagnostics.add(
                    error(
                            "NESTED_PARALLEL_UNSUPPORTED",
                            parallel.nodeId(),
                            "",
                            "",
                            "Nested parallel nodes before the matching join are not supported"));
        }
    }

    private static Set<String> reachableUntilJoin(
            String start,
            Map<String, Set<String>> graph,
            Map<String, WorkflowNode> nodeById) {
        Set<String> visited = new HashSet<>();
        List<String> pending = new ArrayList<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            if (!visited.add(current)) continue;
            WorkflowNode node = nodeById.get(current);
            if (node != null && node.type() == WorkflowNodeType.JOIN) continue;
            pending.addAll(graph.getOrDefault(current, Set.of()));
        }
        return visited;
    }

    private static Set<String> reachable(String start, Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        List<String> pending = new ArrayList<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            if (!visited.add(current)) continue;
            pending.addAll(graph.getOrDefault(current, Set.of()));
        }
        return visited;
    }

    private boolean hasCycle(String nodeId, Map<String, Set<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(nodeId)) return true;
        if (!visited.add(nodeId)) return false;
        visiting.add(nodeId);
        for (String next : graph.getOrDefault(nodeId, Set.of())) if (hasCycle(next, graph, visiting, visited)) return true;
        visiting.remove(nodeId);
        return false;
    }

    private static WorkflowPort findPort(List<WorkflowPort> ports, String portId) {
        return ports == null ? null : ports.stream().filter(port -> port != null && port.portId().equals(portId)).findFirst().orElse(null);
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> list)) return Set.of();
        Set<String> result = new HashSet<>();
        for (Object item : list) if (item != null) result.add(String.valueOf(item));
        return result;
    }

    private static boolean typesCompatible(String source, String target) {
        if (source.isBlank() || target.isBlank() || source.equals(target)) return true;
        return "integer".equals(source) && "number".equals(target);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static WorkflowValidationResult result(List<WorkflowDiagnostic> diagnostics) {
        return new WorkflowValidationResult(diagnostics.stream().noneMatch(d -> d.severity() == WorkflowDiagnostic.Severity.ERROR), diagnostics);
    }

    private static WorkflowDiagnostic error(String code, String nodeId, String portId, String edgeId, String message) {
        return new WorkflowDiagnostic(WorkflowDiagnostic.Severity.ERROR, code, nodeId, portId, edgeId, message, List.of());
    }

    private static WorkflowDiagnostic warning(String code, String nodeId, String portId, String edgeId, String message) {
        return new WorkflowDiagnostic(WorkflowDiagnostic.Severity.WARNING, code, nodeId, portId, edgeId, message, List.of());
    }
}
