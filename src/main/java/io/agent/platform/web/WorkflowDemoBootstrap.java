/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Installs stable public Workflow examples without overwriting user edits. */
@Component
public final class WorkflowDemoBootstrap {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDemoBootstrap.class);

    private final WorkflowAssetService workflows;
    private final boolean enabled;

    public WorkflowDemoBootstrap(
            WorkflowAssetService workflows,
            @Value("${agent.platform.workflow.demo.enabled:true}") boolean enabled) {
        this.workflows = workflows;
        this.enabled = enabled;
    }

    @PostConstruct
    void install() {
        if (!enabled) return;
        installIfMissing(serialDemo());
        installIfMissing(parallelDemo());
    }

    private void installIfMissing(Map<String, Object> definition) {
        String workflowId = String.valueOf(definition.get("workflow_id"));
        try {
            workflows.require(workflowId);
        } catch (IllegalArgumentException missing) {
            try {
                workflows.create(definition);
                workflows.publish(workflowId);
            } catch (RuntimeException error) {
                log.warn("Install Workflow demo {} failed: {}", workflowId, error.getMessage());
            }
        }
    }

    private static Map<String, Object> serialDemo() {
        return Map.of(
                "workflow_id", "workflow-demo-transform",
                "name", "Workflow Demo · 串行数据转换",
                "description", "无模型依赖：输入经过显式 data.transform 节点后输出。",
                "domain", "platform",
                "trigger_type", "manual",
                "visibility", "PUBLIC",
                "input_schema", Map.of("type", "string"),
                "output_schema", Map.of("type", "string"),
                "nodes", List.of(
                        boundary("input", "workflow.input", Map.of("type", "string")),
                        node(
                                "format",
                                "data.transform",
                                Map.of("template", "Workflow received: {{input}}")),
                        boundary("output", "workflow.output", Map.of("type", "string"))),
                "edges", List.of(
                        edge("input-format", "input", "format"),
                        edge("format-output", "format", "output")));
    }

    private static Map<String, Object> parallelDemo() {
        return Map.of(
                "workflow_id", "workflow-demo-parallel",
                "name", "Workflow Demo · 并行分叉汇聚",
                "description", "无模型依赖：两个转换分支并发执行，并按声明顺序汇聚为数组。",
                "domain", "platform",
                "trigger_type", "manual",
                "visibility", "PUBLIC",
                "input_schema", Map.of("type", "string"),
                "output_schema", Map.of("type", "array", "items", Map.of("type", "string")),
                "nodes", List.of(
                        boundary("input", "workflow.input", Map.of("type", "string")),
                        node("fanout", "parallel", Map.of()),
                        node("left", "data.transform", Map.of("template", "left: {{input}}")),
                        node("right", "data.transform", Map.of("template", "right: {{input}}")),
                        node("join", "join", Map.of()),
                        boundary(
                                "output",
                                "workflow.output",
                                Map.of("type", "array", "items", Map.of("type", "string")))),
                "edges", List.of(
                        edge("input-fanout", "input", "fanout"),
                        edge("fanout-left", "fanout", "left"),
                        edge("fanout-right", "fanout", "right"),
                        edge("left-join", "left", "join"),
                        edge("right-join", "right", "join"),
                        edge("join-output", "join", "output")));
    }

    private static Map<String, Object> boundary(
            String id, String type, Map<String, Object> schema) {
        return Map.of(
                "nodeId", id,
                "type", type,
                "config", Map.of("schema", schema));
    }

    private static Map<String, Object> node(
            String id, String type, Map<String, Object> config) {
        return Map.of("nodeId", id, "type", type, "config", config);
    }

    private static Map<String, Object> edge(String id, String from, String to) {
        return Map.of(
                "edgeId", id,
                "from", Map.of("nodeId", from, "portId", "value"),
                "to", Map.of("nodeId", to, "portId", "value"),
                "kind", "data");
    }
}
