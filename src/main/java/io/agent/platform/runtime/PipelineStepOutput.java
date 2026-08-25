/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record PipelineStepOutput(String status, String content) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STATUS_MARKER =
            Pattern.compile(
                    "\\[(?:pipeline|workflow)_status\\s*:\\s*([^\\]]+)]",
                    Pattern.CASE_INSENSITIVE);

    static PipelineStepOutput parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        PipelineStepOutput structured = parseJson(text);
        if (structured != null) {
            return structured;
        }
        Matcher marker = STATUS_MARKER.matcher(text);
        if (marker.find()) {
            return new PipelineStepOutput(normalize(marker.group(1)), text);
        }
        return new PipelineStepOutput("", text);
    }

    private static PipelineStepOutput parseJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(text.substring(start, end + 1));
            String status = root.path("status").asText("").trim();
            if (status.isBlank()) {
                return null;
            }
            String content = root.path("content").asText("");
            return new PipelineStepOutput(normalize(status), content.isBlank() ? text : content);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
