/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.control.WorkflowToolRegistration;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.runtime.ChatRequest;
import io.agent.platform.runtime.ChatResponse;
import io.agent.platform.runtime.protocol.TaskContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class WorkflowToolTest {

    @Test
    void preservesCallerIdentityAndCreatesAChildTask() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        when(runtime.workflow(any(), any()))
                .thenAnswer(
                        invocation -> {
                            captured.set(invocation.getArgument(1));
                            return Mono.just(new ChatResponse("workflow:flow", "user-a", "session-a", "ok"));
                        });
        WorkflowAsset workflow =
                new WorkflowAsset(
                        "flow",
                        3,
                        "Flow",
                        "",
                        "platform",
                        "manual",
                        "PUBLISHED",
                        Map.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        "now",
                        "now",
                        "now");
        WorkflowToolRegistration registration =
                new WorkflowToolRegistration(
                        "workflow_tool_flow",
                        "flow",
                        3,
                        "run_flow",
                        "",
                        Map.of(),
                        List.of(),
                        true,
                        "ACTIVE");
        WorkflowTool tool = new WorkflowTool(workflow, registration, runtime);

        TaskContext root = TaskContext.root("root-run", "caller-agent", "caller-agent", null);
        ChatRequest caller = new ChatRequest("tenant-a", "user-a", "session-a", "outer", root);
        RuntimeContext runtimeContext = mock(RuntimeContext.class);
        when(runtimeContext.get(ChatRequest.class)).thenReturn(caller);
        ToolCallParam param = mock(ToolCallParam.class);
        when(param.getInput()).thenReturn(Map.of("query", "inner"));
        when(param.getRuntimeContext()).thenReturn(runtimeContext);

        tool.callAsync(param).block();

        ChatRequest child = captured.get();
        assertEquals("tenant-a", child.tenantId());
        assertEquals("user-a", child.userId());
        assertEquals("root-run", child.taskContext().rootTaskId());
        assertEquals(root.taskId(), child.taskContext().parentTaskId());
        assertEquals("tool:workflow_tool_flow", child.taskContext().stepId());
        assertEquals(1, child.taskContext().depth());
        assertNotEquals(root.taskId(), child.taskContext().taskId());
        assertEquals("inner", child.message());
    }
}
