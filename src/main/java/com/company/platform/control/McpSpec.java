/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record McpSpec(
        String mcpId,
        String transport,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        Map<String, String> queryParams,
        List<String> enableTools,
        Duration timeout,
        Duration initializationTimeout,
        boolean enabled) {

    public McpSpec {
        mcpId = mcpId == null || mcpId.isBlank() ? url != null ? url : "mcp" : mcpId.strip();
        transport =
                transport == null || transport.isBlank()
                        ? "stdio"
                        : transport.strip().toLowerCase();
        if (args == null) {
            args = List.of();
        } else {
            args = List.copyOf(args);
        }
        if (env == null) {
            env = Map.of();
        } else {
            env = Map.copyOf(env);
        }
        if (headers == null) {
            headers = Map.of();
        } else {
            headers = Map.copyOf(headers);
        }
        if (queryParams == null) {
            queryParams = Map.of();
        } else {
            queryParams = Map.copyOf(queryParams);
        }
        if (enableTools == null) {
            enableTools = List.of();
        } else {
            enableTools = List.copyOf(enableTools);
        }
        command = command == null ? null : command.strip();
        url = url == null ? null : url.strip();
    }
}
