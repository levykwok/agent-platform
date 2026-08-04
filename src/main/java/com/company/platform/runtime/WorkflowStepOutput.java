/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record WorkflowStepOutput(String status, String content) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LEGACY_MARKER =
            Pattern.compile("\\[workflow_status\\s*:\\s*([^\\]]+)]", Pattern.CASE_INSENSITIVE);

    static WorkflowStepOutput parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        WorkflowStepOutput structured = parseJson(text);
        if (structured != null) {
            return structured;
        }
        Matcher marker = LEGACY_MARKER.matcher(text);
        if (marker.find()) {
            return new WorkflowStepOutput(normalize(marker.group(1)), text);
        }
        return new WorkflowStepOutput("", text);
    }

    private static WorkflowStepOutput parseJson(String text) {
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
            return new WorkflowStepOutput(normalize(status), content.isBlank() ? text : content);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
