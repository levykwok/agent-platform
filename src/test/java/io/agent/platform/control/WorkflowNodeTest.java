/*
 * Copyright 2026 by the company contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowNodeTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void standaloneNodesDoNotConvertAgentSteps() {
        WorkflowNode node = new WorkflowNode(
                "research", WorkflowNodeType.AGENT_INVOKE, "researcher", "Analyze",
                null, 0, null, List.of(), List.of());

        assertEquals("research", node.nodeId());
        assertEquals(WorkflowNodeType.AGENT_INVOKE, node.type());
        assertEquals("researcher", node.refId());
    }

    @Test
    void yamlSupportsGenericNodeTypesAndMappings() throws Exception {
        WorkflowNode node =
                yaml.readValue(
                        "nodeId: query-order\n"
                                + "type: http.request\n"
                                + "config:\n"
                                + "  method: GET\n"
                                + "  path: /orders/{orderId}\n"
                                + "inputMapping:\n"
                                + "  orderId: $.orderId\n"
                                + "timeoutMs: 30000\n",
                        WorkflowNode.class);

        assertEquals("query-order", node.nodeId());
        assertEquals(WorkflowNodeType.HTTP_REQUEST, node.type());
        assertEquals("GET", node.config().get("method"));
        assertEquals("$.orderId", node.inputMapping().get("orderId"));
        assertEquals(30000L, node.timeoutMs());
    }

    @Test
    void yamlSupportsWorkflowBoundaryNodes() throws Exception {
        WorkflowNode input = yaml.readValue("nodeId: input\ntype: workflow.input\n", WorkflowNode.class);
        WorkflowNode output = yaml.readValue("nodeId: output\ntype: workflow.output\n", WorkflowNode.class);

        assertEquals(WorkflowNodeType.INPUT, input.type());
        assertEquals(WorkflowNodeType.OUTPUT, output.type());
    }

    @Test
    void agentPolicyKeepsItsOwnOrderedWorkflowSteps() {
        OrchestrationPolicy policy =
                new OrchestrationPolicy(
                        OrchestrationMode.WORKFLOW,
                        List.of(),
                        List.of(),
                        List.of(new WorkflowStep("write", "writer", "Write")));

        assertEquals(1, policy.workflow().size());
        assertEquals("writer", policy.workflow().get(0).agentId());
    }

    @Test
    void typedEdgesValidatePortsAndContracts() {
        WorkflowNode source =
                new WorkflowNode(
                        "source",
                        WorkflowNodeType.DATA_TRANSFORM,
                        "",
                        "",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        0,
                        null,
                        List.of(
                                new WorkflowPort(
                                        "input",
                                        "input",
                                        "",
                                        Map.of(),
                                        false,
                                        "one",
                                        "可选")),
                        List.of(
                                new WorkflowPort(
                                        "result",
                                        "output",
                                        "order.v1",
                                        Map.of(
                                                "type", "object",
                                                "properties", Map.of("id", Map.of("type", "string"))),
                                        false,
                                        "one",
                                        "订单")));
        WorkflowNode target =
                new WorkflowNode(
                        "target",
                        WorkflowNodeType.AGENT_INVOKE,
                        "agent",
                        "",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        0,
                        null,
                        List.of(
                                new WorkflowPort(
                                        "order",
                                        "input",
                                        "invoice.v1",
                                        Map.of(
                                                "type", "object",
                                                "properties", Map.of("invoiceId", Map.of("type", "string")),
                                                "required", List.of("invoiceId")),
                                        true,
                                        "one",
                                        "发票")),
                        List.of());
        WorkflowValidationResult result =
                new WorkflowContractValidator()
                        .validate(
                                List.of(source, target),
                                List.of(
                                        new WorkflowEdge(
                                                "edge_1",
                                                new WorkflowEndpoint("source", "result"),
                                                new WorkflowEndpoint("target", "order"),
                                                "data",
                                                Map.of())));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().startsWith("CONTRACT_")));
    }
}
