/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.runtime.protocol.AgentBusinessResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned value carried between Agent-owned Pipeline steps. */
record PipelineValue(
        String contractVersion,
        String transitionStatus,
        AgentBusinessResult result,
        String text) {

    static final String VERSION = "agent.pipeline.value.v1";

    PipelineValue {
        contractVersion =
                contractVersion == null || contractVersion.isBlank()
                        ? VERSION
                        : contractVersion.strip();
        result = result == null ? AgentBusinessResult.fromText(text) : result;
        transitionStatus = normalize(transitionStatus);
        text = text == null || text.isBlank() ? result.summary() : text;
    }

    static PipelineValue initial(String text) {
        AgentBusinessResult result = AgentBusinessResult.fromText(text);
        return new PipelineValue(VERSION, result.status(), result, text);
    }

    static PipelineValue fromResponse(ChatResponse response) {
        String text = response == null ? "" : safe(response.text());
        AgentBusinessResult result =
                response != null
                                && response.task() != null
                                && response.task().result() != null
                        ? response.task().result().businessResult()
                        : AgentBusinessResult.fromText(text);
        String businessText =
                result.summary() == null || result.summary().isBlank() ? text : result.summary();
        PipelineStepOutput legacy = PipelineStepOutput.parse(businessText);
        String transition = transitionStatus(result, legacy.status());
        String display = legacy.status().isBlank() ? businessText : legacy.content();
        return new PipelineValue(VERSION, transition, result, display);
    }

    static PipelineValue fromStream(String text, AgentBusinessResult structured) {
        AgentBusinessResult result =
                structured == null ? AgentBusinessResult.fromText(text) : structured;
        String businessText =
                result.summary() == null || result.summary().isBlank() ? safe(text) : result.summary();
        PipelineStepOutput legacy = PipelineStepOutput.parse(businessText);
        return new PipelineValue(
                VERSION,
                transitionStatus(result, legacy.status()),
                result,
                legacy.status().isBlank() ? businessText : legacy.content());
    }

    Map<String, Object> contract() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contract_version", contractVersion);
        value.put("transition_status", transitionStatus);
        value.put("result", result.contract());
        return Map.copyOf(value);
    }

    private static String transitionStatus(AgentBusinessResult result, String legacyStatus) {
        if (result.data() instanceof Map<?, ?> data) {
            for (String key : List.of("pipeline_status", "transition_status")) {
                Object value = data.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return normalize(String.valueOf(value));
                }
            }
        }
        return legacyStatus == null || legacyStatus.isBlank()
                ? normalize(result.status())
                : normalize(legacyStatus);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
