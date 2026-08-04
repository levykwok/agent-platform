/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition for a model binding used by the platform.
 */
public record ModelSpec(
        String modelId,
        String kind,
        String type,
        String provider,
        String model,
        String className,
        String modelFactoryClass,
        String apiKey,
        String apiKeyEnv,
        String baseUrl,
        String endpointPath,
        String formatterClass,
        Boolean stream,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Integer maxCompletionTokens,
        Double frequencyPenalty,
        Double presencePenalty,
        Integer thinkingBudget,
        String reasoningEffort,
        Integer topK,
        Long seed,
        Boolean cacheControl,
        Boolean parallelToolCalls,
        Map<String, String> additionalHeaders,
        Map<String, Object> additionalBodyParams,
        Map<String, String> additionalQueryParams,
        Long executionTimeoutMs,
        Integer executionMaxAttempts,
        Long executionInitialBackoffMs,
        Long executionMaxBackoffMs,
        Double executionBackoffMultiplier,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        String proxyPassword,
        Integer dimensions,
        String description,
        boolean enabled,
        List<String> capabilities) {

    public ModelSpec {
        modelId = modelId == null || modelId.isBlank() ? className : modelId.strip();
        kind = kind == null || kind.isBlank() ? "chat" : kind.strip().toLowerCase();
        type = type == null || type.isBlank() ? "provider" : type.strip().toLowerCase();
        provider =
                provider == null || provider.isBlank() ? "openai" : provider.strip().toLowerCase();
        model = model == null || model.isBlank() ? "" : model.strip();
        className = className == null ? "" : className.strip();
        modelFactoryClass = modelFactoryClass == null ? "" : modelFactoryClass.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        apiKeyEnv = apiKeyEnv == null ? "" : apiKeyEnv.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        endpointPath = endpointPath == null ? "" : endpointPath.strip();
        formatterClass = formatterClass == null ? "" : formatterClass.strip();
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
        proxyType = proxyType == null ? "" : proxyType.strip().toLowerCase();
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        proxyUsername = proxyUsername == null ? "" : proxyUsername.strip();
        proxyPassword = proxyPassword == null ? "" : proxyPassword.strip();
        additionalHeaders =
                additionalHeaders == null || additionalHeaders.isEmpty()
                        ? Map.of()
                        : sanitizeStringMap(additionalHeaders);
        additionalBodyParams =
                additionalBodyParams == null || additionalBodyParams.isEmpty()
                        ? Map.of()
                        : sanitizeObjectMap(additionalBodyParams);
        additionalQueryParams =
                additionalQueryParams == null || additionalQueryParams.isEmpty()
                        ? Map.of()
                        : sanitizeStringMap(additionalQueryParams);
        description = description == null ? "" : description.strip();
        capabilities =
                capabilities == null
                        ? List.of()
                        : capabilities.stream()
                                .filter(value -> value != null && !value.isBlank())
                                .map(value -> value.strip().toLowerCase())
                                .distinct()
                                .toList();
    }

    private static Map<String, String> sanitizeStringMap(Map<String, String> source) {
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey() == null ? null : entry.getKey().strip();
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue().strip();
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }

    private static Map<String, Object> sanitizeObjectMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey() == null ? null : entry.getKey().strip();
            if (key == null || key.isBlank()) {
                continue;
            }
            copy.put(key, entry.getValue());
        }
        return Map.copyOf(copy);
    }
}
