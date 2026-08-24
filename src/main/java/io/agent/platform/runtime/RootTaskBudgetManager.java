/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.adapter.agentscope.AgentExecutionPolicy;
import io.agent.platform.runtime.protocol.TaskContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Shared root-level limits across every nested Agent invocation. */
@Component
public class RootTaskBudgetManager {

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public void start(TaskContext context, AgentExecutionPolicy policy) {
        if (context == null || policy == null) return;
        states.computeIfAbsent(
                context.rootTaskId(),
                ignored ->
                        new State(
                                Instant.now(),
                                context.deadlineAt(),
                                policy.rootTimeoutMs(),
                                policy.rootMaxAgentCalls(),
                                policy.rootMaxTokens(),
                                policy.rootMaxDepth()));
    }

    public void acquireAgent(TaskContext context, AgentExecutionPolicy policy) {
        start(context, policy);
        State state = require(context == null ? "" : context.rootTaskId());
        state.checkTime();
        if (context.depth() > state.maxDepth) {
            throw new RootTaskBudgetExceededException(
                    "Root task max depth exceeded: " + context.depth() + " > " + state.maxDepth);
        }
        int calls = state.agentCalls.incrementAndGet();
        if (calls > state.maxAgentCalls) {
            throw new RootTaskBudgetExceededException(
                    "Root task agent call budget exceeded: " + calls + " > " + state.maxAgentCalls);
        }
    }

    public void acquireModel(String rootTaskId) {
        State state = states.get(rootTaskId);
        if (state == null) return;
        state.checkTime();
        if (state.tokens.get() >= state.maxTokens) {
            throw new RootTaskBudgetExceededException(
                    "Root task token budget exhausted: " + state.tokens.get() + " >= " + state.maxTokens);
        }
        state.modelCalls.incrementAndGet();
    }

    public void recordTokens(String rootTaskId, long tokens) {
        State state = states.get(rootTaskId);
        if (state == null || tokens <= 0) return;
        long total = state.tokens.addAndGet(tokens);
        if (total > state.maxTokens) {
            throw new RootTaskBudgetExceededException(
                    "Root task token budget exceeded: " + total + " > " + state.maxTokens);
        }
    }

    public Map<String, Object> snapshot(String rootTaskId) {
        State state = states.get(rootTaskId);
        return state == null ? Map.of() : state.snapshot();
    }

    public Duration remaining(String rootTaskId) {
        State state = states.get(rootTaskId);
        return state == null ? Duration.ofMinutes(3) : state.remaining();
    }

    public void finish(String rootTaskId) {
        if (rootTaskId != null) states.remove(rootTaskId);
    }

    private State require(String rootTaskId) {
        State state = states.get(rootTaskId);
        if (state == null) throw new IllegalStateException("Root task budget was not initialized");
        return state;
    }

    private static final class State {
        private final Instant startedAt;
        private final Instant explicitDeadline;
        private final long maxDurationMs;
        private final int maxAgentCalls;
        private final long maxTokens;
        private final int maxDepth;
        private final AtomicInteger agentCalls = new AtomicInteger();
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicLong tokens = new AtomicLong();

        private State(
                Instant startedAt,
                Instant explicitDeadline,
                long maxDurationMs,
                int maxAgentCalls,
                long maxTokens,
                int maxDepth) {
            this.startedAt = startedAt;
            this.explicitDeadline = explicitDeadline;
            this.maxDurationMs = maxDurationMs;
            this.maxAgentCalls = maxAgentCalls;
            this.maxTokens = maxTokens;
            this.maxDepth = maxDepth;
        }

        private void checkTime() {
            if (remaining().isZero()) {
                throw new RootTaskBudgetExceededException("Root task total time budget exceeded");
            }
        }

        private Duration remaining() {
            Instant deadline =
                    explicitDeadline == null
                            ? startedAt.plusMillis(maxDurationMs)
                            : explicitDeadline.isBefore(startedAt.plusMillis(maxDurationMs))
                                    ? explicitDeadline
                                    : startedAt.plusMillis(maxDurationMs);
            long millis = Duration.between(Instant.now(), deadline).toMillis();
            return Duration.ofMillis(Math.max(0L, millis));
        }

        private Map<String, Object> snapshot() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("elapsed_ms", Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis()));
            row.put("agent_calls", agentCalls.get());
            row.put("model_calls", modelCalls.get());
            row.put("tokens", tokens.get());
            row.put("max_duration_ms", maxDurationMs);
            row.put("max_agent_calls", maxAgentCalls);
            row.put("max_tokens", maxTokens);
            row.put("max_depth", maxDepth);
            return Map.copyOf(row);
        }
    }
}
