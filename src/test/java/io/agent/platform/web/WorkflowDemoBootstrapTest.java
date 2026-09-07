/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.runtime.AgentRuntimeService;
import io.agent.platform.runtime.ChatRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowDemoBootstrapTest {

    @TempDir Path tempDir;

    @Test
    void installsAndRunsBothPublicDemosWithoutAModel() throws Exception {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("workflow-demos.db"),
                        "platform_config",
                        "platform_",
                        "");
        AgentDefinitionRegistry agents = mock(AgentDefinitionRegistry.class);
        WorkflowAssetService workflows = new WorkflowAssetService(storage, agents);
        var load = WorkflowAssetService.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(workflows);
        WorkflowDemoBootstrap bootstrap = new WorkflowDemoBootstrap(workflows, true);
        bootstrap.install();
        AgentRuntimeService runtime =
                new AgentRuntimeService(
                        agents,
                        mock(AgentScopeHarnessFactory.class),
                        mock(PlatformCompatibilityState.class));

        assertEquals(
                "Workflow received: hello",
                runtime.workflow(
                                workflows.requirePublished("workflow-demo-transform"),
                                new ChatRequest("platform", "user", "serial", "hello"))
                        .block()
                        .text());
        assertEquals(
                "[\"left: hello\",\"right: hello\"]",
                runtime.workflow(
                                workflows.requirePublished("workflow-demo-parallel"),
                                new ChatRequest("platform", "user", "parallel", "hello"))
                        .block()
                        .text());
    }
}
