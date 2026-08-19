/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.adapter.agentscope;

import io.agent.platform.control.AgentDefinition;
import java.util.Map;

/** Hard runtime limits and opt-in Harness capabilities. */
public record AgentExecutionPolicy(
        int maxIters,
        int maxToolCalls,
        long timeoutMs,
        int maxSubagents,
        int maxSubagentConcurrency,
        long subagentTimeoutMs,
        boolean fileRead,
        boolean fileWrite,
        boolean shell,
        boolean memoryTools) {

    public static AgentExecutionPolicy from(AgentDefinition definition) {
        Map<String, Object> root = definition.modelPolicy();
        Map<String, Object> runtime = map(root.get("runtime"));
        Map<String, Object> capabilities = map(root.get("capabilities"));
        return new AgentExecutionPolicy(
                integer(runtime, root, "max_iters", 6, 1, 20),
                integer(runtime, root, "max_tool_calls", 12, 0, 100),
                number(runtime, root, "timeout_ms", 120_000L, 1_000L, 600_000L),
                integer(runtime, root, "max_subagents", 4, 0, 16),
                integer(runtime, root, "max_subagent_concurrency", 2, 1, 8),
                number(runtime, root, "subagent_timeout_ms", 90_000L, 1_000L, 300_000L),
                bool(capabilities, root, "file_read", false),
                bool(capabilities, root, "file_write", false),
                bool(capabilities, root, "shell", false),
                bool(capabilities, root, "memory_tools", false));
    }

    private static int integer(
            Map<String, Object> nested,
            Map<String, Object> root,
            String key,
            int fallback,
            int min,
            int max) {
        return (int) number(nested, root, key, fallback, min, max);
    }

    private static long number(
            Map<String, Object> nested,
            Map<String, Object> root,
            String key,
            long fallback,
            long min,
            long max) {
        Object value = nested.containsKey(key) ? nested.get(key) : root.get("runtime_" + key);
        long parsed = fallback;
        try {
            if (value instanceof Number number) {
                parsed = number.longValue();
            } else if (value != null) {
                parsed = Long.parseLong(String.valueOf(value));
            }
        } catch (NumberFormatException ignored) {
            parsed = fallback;
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static boolean bool(
            Map<String, Object> nested,
            Map<String, Object> root,
            String key,
            boolean fallback) {
        Object value = nested.containsKey(key) ? nested.get(key) : root.get("allow_" + key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
