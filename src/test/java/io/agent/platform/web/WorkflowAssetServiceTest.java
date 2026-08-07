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
                                        Map.of(
                                                "nodeId",
                                                "research",
                                                "type",
                                                "agent.invoke",
                                                "refId",
                                                "researcher"))));

        assertEquals("DRAFT", created.get("status"));
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

    private static void invokeLoad(WorkflowAssetService service) throws Exception {
        Method load = WorkflowAssetService.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(service);
    }
}
