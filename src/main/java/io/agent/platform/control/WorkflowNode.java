/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A node in an independent Workflow asset. */
public record WorkflowNode(
        String nodeId,
        WorkflowNodeType type,
        String refId,
        String instruction,
        Map<String, Object> config,
        Map<String, Object> inputMapping,
        Map<String, Object> outputSchema,
        Long timeoutMs,
        Integer maxRetries,
        WorkflowFailurePolicy failurePolicy,
        List<WorkflowPort> inputPorts,
        List<WorkflowPort> outputPorts) {

    public WorkflowNode {
        type = type == null ? WorkflowNodeType.AGENT_INVOKE : type;
        config = config == null ? Map.of() : Map.copyOf(config);
        inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        maxRetries = maxRetries == null ? 0 : maxRetries;
        failurePolicy = failurePolicy == null ? WorkflowFailurePolicy.FAIL_FAST : failurePolicy;
        inputPorts = normalizePorts(inputPorts, type, true, config);
        outputPorts = normalizePorts(outputPorts, type, false, config);
    }

    public WorkflowNode(
            String nodeId,
            WorkflowNodeType type,
            String refId,
            String instruction,
            Long timeoutMs,
            Integer maxRetries,
            WorkflowFailurePolicy failurePolicy,
            List<WorkflowPort> inputPorts,
            List<WorkflowPort> outputPorts) {
        this(nodeId, type, refId, instruction, Map.of(), Map.of(), Map.of(), timeoutMs,
                maxRetries, failurePolicy, inputPorts, outputPorts);
    }

    public Map<String, Object> effectiveConfig() {
        return new LinkedHashMap<>(config);
    }

    private static List<WorkflowPort> normalizePorts(
            List<WorkflowPort> ports, WorkflowNodeType type, boolean input, Map<String, Object> config) {
        if (ports != null && !ports.isEmpty()) return List.copyOf(ports);
        if ((type == WorkflowNodeType.INPUT && input) || (type == WorkflowNodeType.OUTPUT && !input)) {
            return List.of();
        }
        Map<String, Object> schema = objectMap(config == null ? null : config.get("schema"));
        String contract = type == WorkflowNodeType.INPUT || type == WorkflowNodeType.OUTPUT
                ? "workflow." + (type == WorkflowNodeType.INPUT ? "input" : "output") : "";
        return List.of(new WorkflowPort("value", input ? "input" : "output", contract, schema,
                input && type != WorkflowNodeType.INPUT, "one", input ? "节点输入" : "节点输出"));
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
