/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Branchable output emitted by a node in the standalone Workflow graph. */
record WorkflowNodeOutput(String status, String content) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STATUS_MARKER =
            Pattern.compile("\\[workflow_status\\s*:\\s*([^\\]]+)]", Pattern.CASE_INSENSITIVE);

    static WorkflowNodeOutput parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode root = JSON.readTree(text.substring(start, end + 1));
                String status = root.path("status").asText("").trim().toLowerCase();
                if (!status.isBlank()) {
                    String content = root.path("content").asText("");
                    return new WorkflowNodeOutput(status, content.isBlank() ? text : content);
                }
            } catch (Exception ignored) {
                // Fall through to the compatibility marker and plain-text behavior.
            }
        }
        Matcher marker = STATUS_MARKER.matcher(text);
        return marker.find()
                ? new WorkflowNodeOutput(marker.group(1).trim().toLowerCase(), text)
                : new WorkflowNodeOutput("", text);
    }
}
