/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class YamlMcpRegistry implements McpRegistry {

    private final Map<String, McpSpec> servers = new ConcurrentHashMap<>();
    private final PlatformConfigStore configStore;

    public YamlMcpRegistry(PlatformConfigStore configStore) {
        this.configStore = configStore;
    }

    @PostConstruct
    public void load() throws IOException {
        McpConfigRoot config =
                configStore.read(PlatformConfigStore.ConfigFile.MCPS, McpConfigRoot.class);
        Map<String, McpSpec> loaded = new LinkedHashMap<>();
        for (McpConfig server : config.mcps()) {
            McpSpec spec = toSpec(server);
            if (spec.mcpId() == null || spec.mcpId().isBlank()) {
                throw new IllegalStateException("MCP id cannot be blank");
            }
            validate(spec);
            if (loaded.containsKey(spec.mcpId())) {
                throw new IllegalStateException("Duplicate mcpId in config: " + spec.mcpId());
            }
            loaded.put(spec.mcpId(), spec);
        }
        servers.clear();
        servers.putAll(loaded);
    }

    @Override
    public List<McpSpec> all() {
        return servers.values().stream().toList();
    }

    @Override
    public Optional<McpSpec> find(String mcpId) {
        return Optional.ofNullable(servers.get(mcpId));
    }

    @Override
    public void upsert(McpSpec spec) {
        McpSpec normalized = normalize(spec);
        validate(normalized);
        servers.put(normalized.mcpId(), normalized);
        persist();
    }

    @Override
    public void delete(String mcpId) {
        servers.remove(mcpId);
        persist();
    }

    private McpSpec toSpec(McpConfig cfg) {
        return new McpSpec(
                cfg.mcpId(),
                cfg.transport(),
                cfg.command(),
                cfg.args(),
                cfg.env(),
                cfg.url(),
                cfg.headers(),
                cfg.queryParams(),
                cfg.enableTools(),
                duration(cfg.timeout()),
                duration(cfg.initializationTimeout()),
                cfg.enabled());
    }

    private McpSpec normalize(McpSpec spec) {
        return new McpSpec(
                spec.mcpId(),
                spec.transport(),
                spec.command(),
                spec.args(),
                spec.env(),
                spec.url(),
                spec.headers(),
                spec.queryParams(),
                spec.enableTools(),
                spec.timeout(),
                spec.initializationTimeout(),
                spec.enabled());
    }

    private java.time.Duration duration(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return java.time.Duration.ofMillis(number.longValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        if (text.matches("\\d+")) {
            return java.time.Duration.ofMillis(Long.parseLong(text));
        }
        if (text.endsWith("ms")) {
            return java.time.Duration.ofMillis(
                    Long.parseLong(text.substring(0, text.length() - 2)));
        }
        if (text.endsWith("s")) {
            return java.time.Duration.ofSeconds(
                    Long.parseLong(text.substring(0, text.length() - 1)));
        }
        return java.time.Duration.parse(text);
    }

    private McpConfig toConfig(McpSpec spec) {
        return new McpConfig(
                spec.mcpId(),
                spec.transport(),
                spec.command(),
                spec.args(),
                spec.env(),
                spec.url(),
                spec.headers(),
                spec.queryParams(),
                spec.enableTools(),
                spec.timeout() == null ? null : spec.timeout().toMillis(),
                spec.initializationTimeout() == null
                        ? null
                        : spec.initializationTimeout().toMillis(),
                spec.enabled());
    }

    private void validate(McpSpec spec) {
        if (spec.mcpId() == null || spec.mcpId().isBlank()) {
            throw new IllegalStateException("MCP id cannot be blank");
        }
        if (!"stdio".equals(spec.transport())
                && !"sse".equals(spec.transport())
                && !"http".equals(spec.transport())
                && !"streamable-http".equals(spec.transport())) {
            throw new IllegalStateException(
                    "Unsupported MCP transport for " + spec.mcpId() + ": " + spec.transport());
        }
        if (!"stdio".equals(spec.transport()) && (spec.url() == null || spec.url().isBlank())) {
            throw new IllegalStateException(
                    "MCP " + spec.mcpId() + " requires url for transport " + spec.transport());
        }
        if ("stdio".equals(spec.transport())
                && (spec.command() == null || spec.command().isBlank())) {
            throw new IllegalStateException(
                    "MCP " + spec.mcpId() + " requires command for stdio transport.");
        }
    }

    private void persist() {
        List<McpConfig> list =
                servers.values().stream()
                        .sorted((a, b) -> a.mcpId().compareToIgnoreCase(b.mcpId()))
                        .map(this::toConfig)
                        .collect(Collectors.toList());
        configStore.write(PlatformConfigStore.ConfigFile.MCPS, new McpConfigRoot(list));
    }

    public record McpConfigRoot(List<McpConfig> mcps) {
        public McpConfigRoot {
            mcps = mcps == null ? List.of() : List.copyOf(mcps);
        }
    }

    public record McpConfig(
            String mcpId,
            String transport,
            String command,
            List<String> args,
            java.util.Map<String, String> env,
            String url,
            java.util.Map<String, String> headers,
            java.util.Map<String, String> queryParams,
            List<String> enableTools,
            Long timeout,
            Long initializationTimeout,
            boolean enabled) {}
}
