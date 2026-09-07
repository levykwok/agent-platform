/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.web.PlatformCompatibilityState;
import io.agent.platform.web.WorkflowAssetService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScheduledWorkflowTaskServiceTest {

    @Test
    void workflowSchedulePinsTheCurrentPublishedVersion() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        PlatformCompatibilityState state = mock(PlatformCompatibilityState.class);
        when(state.newSession(any(), any(), any()))
                .thenReturn(Map.of("session_id", "workflow-session"));
        WorkflowAssetService workflows = mock(WorkflowAssetService.class);
        WorkflowAsset workflow =
                new WorkflowAsset(
                        "daily-flow",
                        7,
                        "Daily flow",
                        "",
                        "platform",
                        "schedule",
                        "PUBLISHED",
                        Map.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        "",
                        "",
                        "");
        when(workflows.requirePublished(any(), any())).thenReturn(workflow);
        ScheduledTaskService service =
                new ScheduledTaskService(
                        store,
                        mock(AgentRuntime.class),
                        mock(AgentDefinitionRegistry.class),
                        state,
                        mock(PlatformStorageLayer.class),
                        mock(ScheduledTaskWebhookService.class),
                        workflows,
                        true,
                        20,
                        120_000,
                        "Asia/Shanghai");

        Map<String, Object> task =
                service.create(
                        Map.of(
                                "workflow_id", "daily-flow",
                                "prompt", "daily input",
                                "cron", "0 0 9 * * *"),
                        "user",
                        "org");

        assertEquals("workflow", task.get("target_type"));
        assertEquals("daily-flow", task.get("workflow_id"));
        assertEquals(7, task.get("workflow_version"));
        assertEquals("workflow:daily-flow", task.get("agent_id"));
    }
}
