/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agent.platform.adapter.agentscope.AgentExecutionPolicy;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.runtime.protocol.TaskContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RootTaskBudgetManagerTest {

    @Test
    void accumulatesAgentCallsAndTokensAcrossChildren() {
        RootTaskBudgetManager manager = new RootTaskBudgetManager();
        AgentExecutionPolicy policy = policy(2, 100, 2);
        TaskContext root = TaskContext.root("root-1", "caller", "root", null);
        manager.start(root, policy);

        manager.acquireAgent(root, policy);
        manager.acquireAgent(root.child("root", "child", "one"), policy);
        manager.recordTokens(root.rootTaskId(), 90);

        assertEquals(2, manager.snapshot(root.rootTaskId()).get("agent_calls"));
        assertEquals(90L, manager.snapshot(root.rootTaskId()).get("tokens"));
        assertThrows(
                RootTaskBudgetExceededException.class,
                () -> manager.acquireAgent(root.child("root", "other", "two"), policy));
        assertThrows(
                RootTaskBudgetExceededException.class,
                () -> manager.recordTokens(root.rootTaskId(), 11));
    }

    @Test
    void rejectsNestedDepthBeyondRootLimit() {
        RootTaskBudgetManager manager = new RootTaskBudgetManager();
        AgentExecutionPolicy policy = policy(10, 1000, 1);
        TaskContext root = TaskContext.root("root-2", "caller", "root", null);
        TaskContext grandchild =
                root.child("root", "child", "one").child("child", "grandchild", "two");

        manager.start(root, policy);
        assertThrows(
                RootTaskBudgetExceededException.class,
                () -> manager.acquireAgent(grandchild, policy));
    }

    private static AgentExecutionPolicy policy(int calls, long tokens, int depth) {
        AgentDefinition definition =
                new AgentDefinition(
                        "root",
                        "v1",
                        "root",
                        "",
                        Map.of(
                                "runtime",
                                Map.of(
                                        "root_max_agent_calls", calls,
                                        "root_max_tokens", tokens,
                                        "root_max_depth", depth,
                                        "root_timeout_ms", 60_000)),
                        "",
                        true,
                        Path.of("target", "budget-test"),
                        List.of(),
                        List.of(),
                        List.of(),
                        OrchestrationPolicy.single());
        return AgentExecutionPolicy.from(definition);
    }
}
