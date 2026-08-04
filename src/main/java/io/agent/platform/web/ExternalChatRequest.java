/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.runtime.ChatImage;
import io.agent.platform.runtime.ChatRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable request contract for callers outside the platform UI. */
public record ExternalChatRequest(
        String tenantId,
        String userId,
        String sessionId,
        String message,
        List<ChatImage> images) {

    public ExternalChatRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** Convert both JSON naming styles without exposing the runtime request type as the API contract. */
    public static ExternalChatRequest from(Map<String, Object> payload) {
        if (payload == null) {
            return new ExternalChatRequest(null, null, null, null, List.of());
        }
        return new ExternalChatRequest(
                payloadValue(payload, "tenant_id", "tenantId"),
                payloadValue(payload, "user_id", "userId"),
                payloadValue(payload, "session_id", "sessionId"),
                payloadValue(payload, "message"),
                images(payload.get("images")));
    }

    public ChatRequest toRuntimeRequest() {
        return new ChatRequest(
                defaultValue(tenantId, "external"),
                defaultValue(userId, "external-user"),
                defaultValue(sessionId, "session_" + UUID.randomUUID()),
                message.strip(),
                null,
                images);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String payloadValue(Map<String, Object> payload, String... names) {
        for (String name : names) {
            Object value = payload.get(name);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static List<ChatImage> images(Object raw) {
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        List<ChatImage> images = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("Each image must be an object.");
            }
            String url = value(row, "url");
            String data = value(row, "data");
            String mediaType = value(row, "media_type", "mediaType");
            images.add(new ChatImage(url, data, mediaType));
        }
        return List.copyOf(images);
    }

    private static String value(Map<?, ?> payload, String... names) {
        for (String name : names) {
            Object value = payload.get(name);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
