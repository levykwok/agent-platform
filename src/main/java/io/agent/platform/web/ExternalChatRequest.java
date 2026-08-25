/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.runtime.ChatImage;
import io.agent.platform.runtime.ChatRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Stable request contract for callers outside the platform UI. */
public record ExternalChatRequest(
        String tenantId,
        String userId,
        String sessionId,
        String message,
        List<ChatImage> images,
        List<InlineFile> files) {

    private static final int MAX_FILES = 8;
    private static final int MAX_FILE_CHARS = 200_000;
    private static final int MAX_TOTAL_FILE_CHARS = 500_000;

    public ExternalChatRequest {
        images = images == null ? List.of() : List.copyOf(images);
        files = files == null ? List.of() : validateFiles(files);
    }

    /** Convert both JSON naming styles without exposing the runtime request type as the API contract. */
    public static ExternalChatRequest from(Map<String, Object> payload) {
        if (payload == null) {
            return new ExternalChatRequest(null, null, null, null, List.of(), List.of());
        }
        return new ExternalChatRequest(
                payloadValue(payload, "tenant_id", "tenantId"),
                payloadValue(payload, "user_id", "userId"),
                payloadValue(payload, "session_id", "sessionId"),
                payloadValue(payload, "message"),
                images(payload.get("images")),
                files(payload.get("files")));
    }

    public ChatRequest toRuntimeRequest() {
        return new ChatRequest(
                defaultValue(tenantId, "external"),
                defaultValue(userId, "external-user"),
                defaultValue(sessionId, "session_" + UUID.randomUUID()),
                messageWithFiles(),
                null,
                images);
    }

    private String messageWithFiles() {
        if (files.isEmpty()) {
            return message.strip();
        }
        StringBuilder prompt = new StringBuilder(message.strip());
        prompt.append("\n\n[Attached text files]");
        for (InlineFile file : files) {
            prompt.append("\n\n--- File: ")
                    .append(file.name())
                    .append(" (")
                    .append(file.mediaType())
                    .append(") ---\n")
                    .append(file.content());
        }
        return prompt.toString();
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

    private static List<InlineFile> files(Object raw) {
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        List<InlineFile> files = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("Each file must be an object.");
            }
            files.add(
                    new InlineFile(
                            value(row, "name", "filename"),
                            value(row, "media_type", "mediaType"),
                            value(row, "content", "text")));
        }
        return files;
    }

    private static List<InlineFile> validateFiles(List<InlineFile> files) {
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("At most " + MAX_FILES + " files are allowed.");
        }
        int totalChars = 0;
        List<InlineFile> validated = new ArrayList<>();
        for (InlineFile file : files) {
            if (file == null) {
                throw new IllegalArgumentException("File must not be null.");
            }
            InlineFile normalized = new InlineFile(file.name(), file.mediaType(), file.content());
            if (normalized.content().length() > MAX_FILE_CHARS) {
                throw new IllegalArgumentException(
                        "File " + normalized.name() + " exceeds " + MAX_FILE_CHARS + " characters.");
            }
            totalChars += normalized.content().length();
            if (totalChars > MAX_TOTAL_FILE_CHARS) {
                throw new IllegalArgumentException(
                        "Attached files exceed " + MAX_TOTAL_FILE_CHARS + " characters in total.");
            }
            validated.add(normalized);
        }
        return List.copyOf(validated);
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

    /** Text attachment embedded into the runtime prompt. Binary files must use a future upload API. */
    public record InlineFile(String name, String mediaType, String content) {
        public InlineFile {
            name = sanitizeName(name);
            mediaType = mediaType == null || mediaType.isBlank() ? "text/plain" : mediaType.strip();
            content = content == null ? "" : content;
            if (!isTextMediaType(mediaType)) {
                throw new IllegalArgumentException("Inline file media_type must be textual: " + mediaType);
            }
        }

        private static String sanitizeName(String value) {
            String normalized = value == null ? "attachment.txt" : value.strip();
            normalized = normalized.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "_");
            if (normalized.isBlank()) {
                normalized = "attachment.txt";
            }
            return normalized.length() > 180 ? normalized.substring(0, 180) : normalized;
        }

        private static boolean isTextMediaType(String value) {
            String normalized = value.toLowerCase(Locale.ROOT);
            return normalized.startsWith("text/")
                    || normalized.equals("application/json")
                    || normalized.equals("application/xml")
                    || normalized.equals("application/yaml")
                    || normalized.equals("application/x-yaml")
                    || normalized.equals("application/csv");
        }
    }
}
