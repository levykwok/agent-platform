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
        if (safeEdges.isEmpty()) {
            return result(diagnostics);
        }
        Map<String, WorkflowEdge> edgeById = new HashMap<>();
        Map<String, Set<String>> incoming = new HashMap<>();
        Map<String, Set<String>> graph = new HashMap<>();
        for (WorkflowEdge edge : safeEdges) {
            validateEdge(edge, nodeById, edgeById, incoming, graph, diagnostics);
        }
        validateRequiredInputs(safeNodes, incoming, diagnostics);
        validateCycles(graph, diagnostics);
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
        return ("integer".equals(source) && "number".equals(target))
                || ("number".equals(source) && "string".equals(target))
                || ("boolean".equals(source) && "string".equals(target));
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
