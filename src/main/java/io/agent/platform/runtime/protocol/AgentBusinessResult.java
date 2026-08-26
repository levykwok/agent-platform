/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable business result exchanged at every nested Agent boundary. */
public record AgentBusinessResult(
        String status,
        Object data,
        String summary,
        List<Map<String, Object>> artifacts,
        TaskError error) {

    public static final String VERSION = "agent.result.v1";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String PARTIAL = "partial";
    private static final ObjectMapper JSON = new ObjectMapper();

    public AgentBusinessResult {
        status = normalizeStatus(status);
        summary = summary == null ? "" : summary;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static AgentBusinessResult fromText(String value) {
        String text = value == null ? "" : value.strip();
        AgentBusinessResult structured = parseStructured(text);
        return structured == null
                ? new AgentBusinessResult(
                        SUCCEEDED, Map.of("text", value == null ? "" : value), value, List.of(), null)
                : structured;
    }

    public static AgentBusinessResult failed(TaskError error) {
        String message = error == null ? "Task failed" : error.message();
        return new AgentBusinessResult(FAILED, Map.of(), message, List.of(), error);
    }

    public Map<String, Object> contract() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contract_version", VERSION);
        value.put("status", status);
        value.put("data", data);
        value.put("summary", summary);
        value.put("artifacts", artifacts);
        value.put("error", error);
        return value;
    }

    public static AgentBusinessResult fromContract(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            Object data = map.get("data");
            List<Map<String, Object>> artifacts = new ArrayList<>();
            if (map.get("artifacts") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> artifact) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        artifact.forEach((key, item) -> copy.put(String.valueOf(key), item));
                        artifacts.add(java.util.Collections.unmodifiableMap(copy));
                    }
                }
            }
            TaskError error =
                    map.get("error") instanceof Map<?, ?> rawError
                            ? JSON.convertValue(rawError, TaskError.class)
                            : null;
            return new AgentBusinessResult(
                    String.valueOf(map.containsKey("status") ? map.get("status") : SUCCEEDED),
                    data,
                    String.valueOf(map.containsKey("summary") ? map.get("summary") : ""),
                    artifacts,
                    error);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static AgentBusinessResult parseStructured(String text) {
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(text);
            if (!root.isObject()
                    || !root.has("status")
                    || !root.has("data")
                    || !root.has("summary")
                    || !root.has("artifacts")) {
                return null;
            }
            Object data = JSON.convertValue(root.get("data"), Object.class);
            List<Map<String, Object>> artifacts = new ArrayList<>();
            if (root.path("artifacts").isArray()) {
                for (JsonNode artifact : root.path("artifacts")) {
                    if (artifact.isObject()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = JSON.convertValue(artifact, Map.class);
                        artifacts.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row)));
                    }
                }
            }
            TaskError error =
                    root.path("error").isObject()
                            ? JSON.convertValue(root.path("error"), TaskError.class)
                            : null;
            return new AgentBusinessResult(
                    root.path("status").asText(SUCCEEDED),
                    data,
                    root.path("summary").asText(""),
                    artifacts,
                    error);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeStatus(String value) {
        String status = value == null ? "" : value.strip().toLowerCase();
        return switch (status) {
            case "success", "succeed", "succeeded", "completed", "ok" -> SUCCEEDED;
            case "partial", "partial_success" -> PARTIAL;
            case "failure", "fail", "failed", "error" -> FAILED;
            default -> SUCCEEDED;
        };
    }
}
