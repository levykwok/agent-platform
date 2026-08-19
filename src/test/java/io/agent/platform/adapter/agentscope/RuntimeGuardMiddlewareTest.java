/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.adapter.agentscope;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agent.platform.web.PlatformCompatibilityState;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class RuntimeGuardMiddlewareTest {

    @Test
    void rejectsBatchThatExceedsHardToolCallBudget() {
        PlatformCompatibilityState state = mock(PlatformCompatibilityState.class);
        RuntimeGuardMiddleware middleware =
                new RuntimeGuardMiddleware(state, "agent-a", 1, Duration.ofSeconds(10));
        ActingInput twoCalls =
                new ActingInput(List.of(tool("one"), tool("two")));

        assertThrows(
                RuntimeException.class,
                () ->
                        middleware
                                .onActing(
                                        mock(Agent.class),
                                        RuntimeContext.builder()
                                                .userId("user-a")
                                                .sessionId("session-a")
                                                .build(),
                                        twoCalls,
                                        ignored -> Flux.empty())
                                .blockLast());
        verify(state)
                .appendAuditEvent(
                        eq("tool.call.rejected"), eq("agent-a"), org.mockito.ArgumentMatchers.<Map<String, Object>>any());
    }

    private ToolUseBlock tool(String name) {
        return ToolUseBlock.builder().id(name).name(name).input(Map.of()).build();
    }
}
