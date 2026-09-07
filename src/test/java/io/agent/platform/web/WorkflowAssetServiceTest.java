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

    @Test
    void publishingCannotEmbedAnotherUsersPrivateDependencies() throws Exception {
        AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
        when(registry.findPublished("private-agent"))
                .thenReturn(Optional.of(mock(AgentDefinition.class)));
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "file",
                        "",
                        "platform_config",
                        "platform_",
                        "");
        PlatformAssetAccessService assetAccess = new PlatformAssetAccessService(storage);
        WorkflowAssetService service =
                new WorkflowAssetService(storage, registry, assetAccess);
        invokeLoad(service);
        PlatformAuthService.Principal ownerA =
                new PlatformAuthService.Principal(
                        "user_a", "a@example.com", "A", "org_a", "BUILDER");
        PlatformAuthService.Principal ownerB =
                new PlatformAuthService.Principal(
                        "user_b", "b@example.com", "B", "org_b", "BUILDER");

        service.create(boundaryGraph("private-child", "Private child"), ownerB);
        service.publish("private-child", ownerB);
        service.create(
                dependencyGraph(
                        "foreign-subflow",
                        "subflow.invoke",
                        "private-child",
                        Map.of()),
                ownerA);
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> service.publish("foreign-subflow", ownerA));

        assetAccess.registerNew(
                "AGENT", "private-agent", ownerB, "PRIVATE", "PUBLISHED");
        service.create(
                dependencyGraph(
                        "foreign-agent", "agent.invoke", "private-agent", Map.of()),
                ownerA);
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> service.publish("foreign-agent", ownerA));
    }

    @Test
    void publishedVersionsAreImmutableAndDraftEditsKeepTheActiveRevision() throws Exception {
        AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("versions.db"),
                        "platform_config",
                        "platform_",
                        "");
        WorkflowAssetService service = new WorkflowAssetService(storage, registry);
        invokeLoad(service);
        Map<String, Object> graph =
                Map.of(
                        "workflow_id",
                        "versioned_flow",
                        "name",
                        "Version one",
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
        service.create(graph);
        Map<String, Object> publishedOne = service.publish("versioned_flow");
        assertEquals(1, publishedOne.get("version"));

        service.save("versioned_flow", Map.of("name", "Version two draft"));
        assertEquals("DRAFT", service.get("versioned_flow").get("status"));
        assertEquals("Version one", service.requirePublished("versioned_flow").name());
        assertEquals("Version one", service.requirePublishedVersion("versioned_flow", 1).name());

        Map<String, Object> publishedTwo = service.publish("versioned_flow");
        assertEquals(2, publishedTwo.get("version"));
        assertEquals("Version one", service.requirePublishedVersion("versioned_flow", 1).name());
        assertEquals("Version two draft", service.requirePublishedVersion("versioned_flow", 2).name());
        assertEquals(2, service.versions("versioned_flow", null).size());

        WorkflowAssetService reloaded = new WorkflowAssetService(storage, registry);
        invokeLoad(reloaded);
        assertEquals("Version one", reloaded.requirePublishedVersion("versioned_flow", 1).name());
        assertEquals("Version two draft", reloaded.requirePublished("versioned_flow").name());
    }

    private static void invokeLoad(WorkflowAssetService service) throws Exception {
        Method load = WorkflowAssetService.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(service);
    }

    private static Map<String, Object> boundaryGraph(String workflowId, String name) {
        return Map.of(
                "workflow_id",
                workflowId,
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

    private static Map<String, Object> dependencyGraph(
            String workflowId,
            String nodeType,
            String refId,
            Map<String, Object> config) {
        return Map.of(
                "workflow_id",
                workflowId,
                "name",
                workflowId,
                "nodes",
                List.of(
                        Map.of("nodeId", "input", "type", "workflow.input"),
                        Map.of(
                                "nodeId",
                                "dependency",
                                "type",
                                nodeType,
                                "refId",
                                refId,
                                "config",
                                config),
                        Map.of("nodeId", "output", "type", "workflow.output")),
                "edges",
                List.of(
                        Map.of(
                                "edgeId",
                                "input-dependency",
                                "from",
                                Map.of("nodeId", "input", "portId", "value"),
                                "to",
                                Map.of("nodeId", "dependency", "portId", "value"),
                                "kind",
                                "data"),
                        Map.of(
                                "edgeId",
                                "dependency-output",
                                "from",
                                Map.of("nodeId", "dependency", "portId", "value"),
                                "to",
                                Map.of("nodeId", "output", "portId", "value"),
                                "kind",
                                "data")));
    }
}
