/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.adapter.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.McpRegistry;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.RuntimeToolGovernance;
import io.agent.platform.control.SkillRegistry;
import io.agent.platform.control.ToolRegistry;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.scheduled.ScheduledTaskService;
import io.agent.platform.scheduled.ScheduledTaskTools;
import io.agent.platform.web.PlatformUserCapabilityService;
import io.agent.platform.web.WorkflowAssetService;
import io.agent.platform.web.WorkflowToolRegistry;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

class AgentCapabilityAssemblerTest {

    @Test
    void supervisorRegistersScheduleProviderWithoutTreatingMethodNamesAsToolAssetIds() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        WorkflowToolRegistry workflowTools = mock(WorkflowToolRegistry.class);
        RuntimeToolGovernance governance = mock(RuntimeToolGovernance.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntime> runtimeProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ScheduledTaskTools> scheduleProvider = mock(ObjectProvider.class);

        when(toolRegistry.find(anyString())).thenReturn(Optional.empty());
        when(workflowTools.find(anyString())).thenReturn(Optional.empty());
        when(governance.isAllowed(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(scheduleProvider.getObject())
                .thenReturn(new ScheduledTaskTools(mock(ScheduledTaskService.class)));

        AgentCapabilityAssembler assembler =
                new AgentCapabilityAssembler(
                        toolRegistry,
                        mock(McpRegistry.class),
                        mock(SkillRegistry.class),
                        mock(Environment.class),
                        mock(PlatformStorageLayer.class),
                        mock(WorkflowAssetService.class),
                        workflowTools,
                        runtimeProvider,
                        mock(PlatformUserCapabilityService.class),
                        scheduleProvider,
                        governance);
        AgentDefinition definition =
                new AgentDefinition(
                        "main",
                        "v1",
                        "main",
                        "model",
                        Map.of(),
                        "prompt",
                        true,
                        Path.of("workspace", "main"),
                        List.of(),
                        List.of(),
                        List.of(),
                        new OrchestrationPolicy(
                                OrchestrationMode.SUPERVISOR,
                                List.of(),
                                List.of(),
                                List.of()));

        Toolkit toolkit = new Toolkit();
        assembler.applyToolsAndMcps(toolkit, definition);

        assertEquals(
                List.of(
                        "schedule_create",
                        "schedule_delete",
                        "schedule_get",
                        "schedule_get_runs",
                        "schedule_list",
                        "schedule_pause",
                        "schedule_resume",
                        "schedule_run_now"),
                toolkit.getToolNames().stream().sorted().toList());
    }
}
