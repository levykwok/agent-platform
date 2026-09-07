/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowToolRegistryTest {

    @TempDir Path tempDir;

    @Test
    void sqliteRegistryKeepsItsPinnedImmutableWorkflowRevision() throws Exception {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("workflow-tools.db"),
                        "platform_config",
                        "platform_",
                        "");
        AgentDefinitionRegistry agents = mock(AgentDefinitionRegistry.class);
        WorkflowAssetService workflows = new WorkflowAssetService(storage, agents);
        invokeLoad(workflows);
        workflows.create(graph("Pinned one"));
        workflows.publish("pinned_flow");

        WorkflowToolRegistry registry = new WorkflowToolRegistry(workflows, agents, storage);
        invokeLoad(registry);
        registry.register(
                Map.of(
                        "tool_id", "pinned_tool",
                        "workflow_id", "pinned_flow",
                        "name", "Pinned tool"));

        workflows.save("pinned_flow", Map.of("name", "Pinned two"));
        workflows.publish("pinned_flow");
        assertEquals(1, registry.workflowForAgent("pinned_tool", "any_agent").version());
        assertEquals("Pinned one", registry.workflowForAgent("pinned_tool", "any_agent").name());

        WorkflowAssetService reloadedWorkflows = new WorkflowAssetService(storage, agents);
        invokeLoad(reloadedWorkflows);
        WorkflowToolRegistry reloaded =
                new WorkflowToolRegistry(reloadedWorkflows, agents, storage);
        invokeLoad(reloaded);
        assertEquals(1, reloaded.workflowForAgent("pinned_tool", "any_agent").version());
        assertEquals(1, reloaded.all().size());
        reloadedWorkflows.unpublish("pinned_flow");
        assertThrows(
                IllegalArgumentException.class,
                () -> reloaded.workflowForAgent("pinned_tool", "any_agent"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        reloaded.register(
                                Map.of(
                                        "tool_id",
                                        "unpublished_tool",
                                        "workflow_id",
                                        "pinned_flow",
                                        "workflow_version",
                                        1)));
    }

    @Test
    void privateRegistrationsAreIsolatedFromOtherUsers() throws Exception {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("isolated-tools.db"),
                        "platform_config",
                        "platform_",
                        "");
        AgentDefinitionRegistry agents = mock(AgentDefinitionRegistry.class);
        WorkflowAssetService workflows = new WorkflowAssetService(storage, agents);
        invokeLoad(workflows);
        var owner =
                new PlatformAuthService.Principal(
                        "owner", "owner@example.com", "Owner", "org-a", "BUILDER");
        var stranger =
                new PlatformAuthService.Principal(
                        "stranger", "stranger@example.com", "Stranger", "org-b", "BUILDER");
        when(agents.findPublished("owner-agent"))
                .thenReturn(java.util.Optional.of(mock(AgentDefinition.class)));
        when(agents.findPublished("foreign-agent"))
                .thenReturn(java.util.Optional.of(mock(AgentDefinition.class)));
        PlatformAssetAccessService assetAccess = new PlatformAssetAccessService(storage);
        assetAccess.registerNew(
                "AGENT", "owner-agent", owner, "PRIVATE", "PUBLISHED");
        assetAccess.registerNew(
                "AGENT", "foreign-agent", stranger, "PRIVATE", "PUBLISHED");
        workflows.create(graph("Private flow"), owner);
        workflows.publish("pinned_flow", owner);
        WorkflowToolRegistry registry =
                new WorkflowToolRegistry(workflows, agents, storage, assetAccess);
        invokeLoad(registry);

        registry.register(
                Map.of(
                        "tool_id", "private_tool",
                        "workflow_id", "pinned_flow",
                        "visibility", "PRIVATE",
                        "allowed_agents", List.of("owner-agent")),
                owner);

        assertEquals(1, registry.rows(owner).size());
        assertTrue(registry.rows(stranger).isEmpty());
        assertThrows(
                PlatformAuthService.AuthException.class,
                () ->
                        registry.register(
                                Map.of(
                                        "tool_id",
                                        "foreign_binding",
                                        "workflow_id",
                                        "pinned_flow",
                                        "allowed_agents",
                                        List.of("foreign-agent")),
                                owner));
    }

    private static Map<String, Object> graph(String name) {
        return Map.of(
                "workflow_id",
                "pinned_flow",
                "name",
                name,
                "nodes",
                List.of(
                        Map.of(
                                "nodeId",
                                "input",
                                "type",
                                "workflow.input",
                                "config",
                                Map.of("schema", Map.of("type", "string"))),
                        Map.of(
                                "nodeId",
                                "output",
                                "type",
                                "workflow.output",
                                "config",
                                Map.of("schema", Map.of("type", "string")))),
                "edges",
                List.of(
                        Map.of(
                                "edgeId",
                                "input-output",
                                "from",
                                Map.of("nodeId", "input", "portId", "value"),
                                "to",
                                Map.of("nodeId", "output", "portId", "value"),
                                "kind",
                                "data")));
    }

    private static void invokeLoad(Object service) throws Exception {
        Method load = service.getClass().getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(service);
    }
}
