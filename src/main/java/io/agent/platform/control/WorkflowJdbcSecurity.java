/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.Arrays;
import java.util.List;

/** Explicit allow-list policy for Workflow JDBC nodes. */
public final class WorkflowJdbcSecurity {

    private WorkflowJdbcSecurity() {}

    public static String validateUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (!url.startsWith("jdbc:") || url.contains("{{input}}") || url.contains("${input}")) {
            throw new IllegalArgumentException("Workflow JDBC URL is invalid");
        }
        List<String> prefixes = allowedPrefixes();
        if (prefixes.isEmpty() || prefixes.stream().noneMatch(url::startsWith)) {
            throw new IllegalArgumentException(
                    "Workflow JDBC URL is not allowed; configure AGENT_PLATFORM_WORKFLOW_JDBC_ALLOW_PREFIXES");
        }
        return url;
    }

    public static String credential(String value) {
        String configured = value == null ? "" : value.trim();
        if (configured.isBlank()) return "";
        if (!configured.startsWith("env:")) {
            throw new IllegalArgumentException(
                    "Workflow JDBC credentials must use env: references");
        }
        return WorkflowHttpSecurity.resolveCredential(configured);
    }

    private static List<String> allowedPrefixes() {
        String configured =
                System.getProperty(
                        "agent.platform.workflow.jdbc.allow-prefixes",
                        System.getenv("AGENT_PLATFORM_WORKFLOW_JDBC_ALLOW_PREFIXES"));
        if (configured == null || configured.isBlank()) return List.of();
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank() && value.startsWith("jdbc:"))
                .toList();
    }
}
