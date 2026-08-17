/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.WorkflowNode;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowAssetServiceTest {

    @TempDir Path tempDir;

    @Test
    void workflowCrudAndPublishArePersisted() throws Exception {
        AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
        when(registry.findPublished("researcher"))
                .thenReturn(Optional.of(mock(AgentDefinition.class)));
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("platform-test.db"),
                        "platform_config",
                        "platform_",
                        "");

        WorkflowAssetService service = new WorkflowAssetService(storage, registry);
        invokeLoad(service);
        Map<String, Object> created =
                service.create(
                        Map.of(
                                "workflow_id",
                                "order_review",
                                "name",
                                "Order review",
                                "nodes",
                                List.of(
                                        Map.of("nodeId", "workflow_input", "type", "workflow.input"),
                                        Map.of(
                                                "nodeId",
                                                "research",
                                                "type",
                                                "agent.invoke",
                                                "refId",
                                                "researcher",
                                                "position",
                                                Map.of("x", 70, "y", 60)),
                                        Map.of("nodeId", "workflow_output", "type", "workflow.output")),
                                "edges",
                                List.of(
                                        Map.of(
                                                "edgeId", "input-research",
                                                "from", Map.of("nodeId", "workflow_input", "portId", "value"),
                                                "to", Map.of("nodeId", "research", "portId", "value"),
                                                "kind", "data"),
                                        Map.of(
                                                "edgeId", "research-output",
                                                "from", Map.of("nodeId", "research", "portId", "value"),
                                                "to", Map.of("nodeId", "workflow_output", "portId", "value"),
                                                "kind", "data"))));

        assertEquals("DRAFT", created.get("status"));
        WorkflowNode createdNode =
                (WorkflowNode) ((List<?>) created.get("nodes")).get(1);
        assertEquals(
                Map.of("x", 70, "y", 60),
                createdNode.config().get("canvas_position"));
        assertEquals(1, service.list(null, null).size());
        assertEquals("PUBLISHED", service.publish("order_review").get("status"));

        WorkflowAssetService reloaded = new WorkflowAssetService(storage, registry);
        invokeLoad(reloaded);
        assertEquals("PUBLISHED", reloaded.get("order_review").get("status"));
        reloaded.delete("order_review");
        assertThrows(IllegalArgumentException.class, () -> reloaded.get("order_review"));
    }

    @Test
    void publishRejectsUnknownAgentAndEmptyWorkflow() throws Exception {
        AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
        when(registry.findPublished("missing")).thenReturn(Optional.empty());
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "file",
                        "",
                        "platform_config",
                        "platform_",
                        "");
        WorkflowAssetService service = new WorkflowAssetService(storage, registry);
        invokeLoad(service);
        service.create(Map.of("workflow_id", "empty_flow", "name", "Empty"));
        assertThrows(IllegalArgumentException.class, () -> service.publish("empty_flow"));
        service.create(
                Map.of(
                        "workflow_id",
                        "missing_agent_flow",
                        "name",
                        "Missing",
                        "nodes",
                        List.of(
                                Map.of(
                                        "nodeId",
                                        "step",
                                        "type",
                                        "agent.invoke",
                                        "refId",
                                        "missing"))));
        assertThrows(
                IllegalArgumentException.class, () -> service.publish("missing_agent_flow"));
    }

    @Test
    void privateWorkflowIsOnlyReadableAndWritableByItsOwner() throws Exception {
        AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "file",
                        "",
                        "platform_config",
                        "platform_",
                        "");
        WorkflowAssetService service = new WorkflowAssetService(storage, registry);
        invokeLoad(service);
        PlatformAuthService.Principal owner =
                new PlatformAuthService.Principal("user_a", "a@example.com", "A", "org_a", "BUILDER");
        PlatformAuthService.Principal other =
                new PlatformAuthService.Principal("user_b", "b@example.com", "B", "org_b", "BUILDER");
        PlatformAuthService.Principal admin =
                new PlatformAuthService.Principal(
                        "admin", "admin@example.com", "Admin", "platform", "PLATFORM_ADMIN");

        Map<String, Object> created =
                service.create(Map.of("workflow_id", "private_flow", "name", "Private"), owner);
        assertEquals("PRIVATE", created.get("visibility"));
        assertEquals("user_a", created.get("owner_id"));
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> service.get("private_flow", other));
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> service.save("private_flow", Map.of("name", "Hijack"), other));
        assertEquals("Private", service.get("private_flow", owner).get("name"));
        assertEquals("Private", service.get("private_flow", admin).get("name"));
    }

    private static void invokeLoad(WorkflowAssetService service) throws Exception {
        Method load = WorkflowAssetService.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(service);
    }
}
