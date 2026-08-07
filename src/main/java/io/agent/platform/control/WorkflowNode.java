/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generic workflow node. Legacy agent steps are represented as agent.invoke nodes. */
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
        WorkflowStep.FailurePolicy failurePolicy,
        List<WorkflowTransition> transitions,
        List<WorkflowPort> inputPorts,
        List<WorkflowPort> outputPorts) {

    public WorkflowNode {
        type = type == null ? WorkflowNodeType.AGENT_INVOKE : type;
        config = config == null ? Map.of() : Map.copyOf(config);
        inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        maxRetries = maxRetries == null ? 0 : maxRetries;
        failurePolicy = failurePolicy == null ? WorkflowStep.FailurePolicy.FAIL_FAST : failurePolicy;
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        inputPorts = normalizePorts(inputPorts, type, true, config);
        outputPorts = normalizePorts(outputPorts, type, false, config);
    }

    /** Backward-compatible constructor for the pre-contract node shape. */
    public WorkflowNode(
            String nodeId,
            WorkflowNodeType type,
            String refId,
            String instruction,
            Map<String, Object> config,
            Map<String, Object> inputMapping,
            Map<String, Object> outputSchema,
            Long timeoutMs,
            Integer maxRetries,
            WorkflowStep.FailurePolicy failurePolicy,
            List<WorkflowTransition> transitions) {
        this(nodeId, type, refId, instruction, config, inputMapping, outputSchema, timeoutMs,
                maxRetries, failurePolicy, transitions, List.of(), List.of());
    }

    public WorkflowNode(
            String nodeId,
            WorkflowNodeType type,
            String refId,
            String instruction,
            Long timeoutMs,
            Integer maxRetries,
            WorkflowStep.FailurePolicy failurePolicy,
            List<WorkflowTransition> transitions) {
        this(nodeId, type, refId, instruction, Map.of(), Map.of(), Map.of(), timeoutMs,
                maxRetries, failurePolicy, transitions, List.of(), List.of());
    }

    public static WorkflowNode fromLegacy(WorkflowStep step) {
        return new WorkflowNode(
                step.stepId(), WorkflowNodeType.AGENT_INVOKE, step.agentId(), step.instruction(),
                Map.of(), Map.of(), Map.of(), step.timeoutMs(), step.maxRetries(),
                step.failurePolicy(), step.transitions(), List.of(), List.of());
    }

    public WorkflowStep asAgentStep() {
        if (type != WorkflowNodeType.AGENT_INVOKE) {
            throw new IllegalStateException(
                    "Workflow node " + nodeId + " is not an agent.invoke node: " + type.value());
        }
        return new WorkflowStep(nodeId, refId, instruction, timeoutMs, maxRetries, failurePolicy, transitions);
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
