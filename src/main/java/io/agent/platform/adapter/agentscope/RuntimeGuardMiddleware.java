/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.adapter.agentscope;

import io.agent.platform.web.PlatformCompatibilityState;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Enforces per-invocation wall-clock and total tool-call limits. */
final class RuntimeGuardMiddleware implements MiddlewareBase {

    private static final String COUNTER_KEY = "agent_platform_tool_call_counter";

    private final PlatformCompatibilityState state;
    private final String agentId;
    private final int maxToolCalls;
    private final Duration timeout;

    RuntimeGuardMiddleware(
            PlatformCompatibilityState state,
            String agentId,
            int maxToolCalls,
            Duration timeout) {
        this.state = state;
        this.agentId = agentId;
        this.maxToolCalls = maxToolCalls;
        this.timeout = timeout;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        context.put(COUNTER_KEY, new AtomicInteger());
        return next.apply(input)
                .timeout(timeout)
                .doFinally(ignored -> context.put(COUNTER_KEY, null));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        List<String> names =
                input.toolCalls() == null
                        ? List.of()
                        : input.toolCalls().stream().map(call -> call.getName()).toList();
        AtomicInteger counter = context.get(COUNTER_KEY, AtomicInteger.class);
        if (counter == null) {
            counter = new AtomicInteger();
            context.put(COUNTER_KEY, counter);
        }
        int total = counter.addAndGet(names.size());
        Map<String, Object> audit =
                Map.of(
                        "agent_id", agentId,
                        "session_id", safe(context.getSessionId()),
                        "user_id", safe(context.getUserId()),
                        "tool_names", names,
                        "tool_call_count", names.size(),
                        "tool_call_total", total,
                        "tool_call_budget", maxToolCalls);
        if (total > maxToolCalls) {
            state.appendAuditEvent("tool.call.rejected", agentId, audit);
            return Flux.error(
                    new IllegalStateException(
                            "Tool call budget exceeded for "
                                    + agentId
                                    + ": "
                                    + total
                                    + "/"
                                    + maxToolCalls));
        }
        state.appendAuditEvent("tool.call.started", agentId, audit);
        return next.apply(input)
                .doOnComplete(
                        () -> state.appendAuditEvent("tool.call.completed", agentId, audit));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
