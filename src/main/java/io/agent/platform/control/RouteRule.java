/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;

public record RouteRule(
        String ruleId,
        String targetAgentId,
        String contains,
        List<String> keywords,
        boolean defaultRoute) {

    public RouteRule(String ruleId, String targetAgentId, String contains) {
        this(ruleId, targetAgentId, contains, List.of(), false);
    }

    public RouteRule {
        keywords =
                keywords == null
                        ? List.of()
                        : keywords.stream()
                                .map(keyword -> keyword == null ? "" : keyword.strip())
                                .filter(keyword -> !keyword.isBlank())
                                .distinct()
                                .toList();
    }

    public boolean matches(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        if (contains != null && !contains.isBlank()) {
            return normalized.contains(contains.toLowerCase());
        }
        return keywords.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase()));
    }
}
