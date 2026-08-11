/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

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
public class YamlToolRegistry implements ToolRegistry {

    private final Map<String, ToolSpec> tools = new ConcurrentHashMap<>();
    private final PlatformConfigStore configStore;

    public YamlToolRegistry(PlatformConfigStore configStore) {
        this.configStore = configStore;
    }

    @PostConstruct
    public void load() throws IOException {
        ToolConfigRoot config =
                configStore.read(PlatformConfigStore.ConfigFile.TOOLS, ToolConfigRoot.class);
        Map<String, ToolSpec> loaded = new LinkedHashMap<>();
        for (ToolConfig tool : config.tools()) {
            ToolSpec spec = toSpec(tool);
            if (spec.toolId() == null || spec.toolId().isBlank()) {
                throw new IllegalStateException("Tool id cannot be blank");
            }
            validate(spec);
            if (loaded.containsKey(spec.toolId())) {
                throw new IllegalStateException("Duplicate toolId in config: " + spec.toolId());
            }
            loaded.put(spec.toolId(), spec);
        }
        tools.clear();
        tools.putAll(loaded);
    }

    @Override
    public List<ToolSpec> all() {
        return tools.values().stream().toList();
    }

    @Override
    public Optional<ToolSpec> find(String toolId) {
        return Optional.ofNullable(tools.get(toolId));
    }

    @Override
    public void upsert(ToolSpec spec) {
        ToolSpec normalized = normalize(spec);
        validate(normalized);
        tools.put(normalized.toolId(), normalized);
        persist();
    }

    private ToolSpec toSpec(ToolConfig tool) {
        return new ToolSpec(
                tool.toolId(),
                safe(tool.type(), "java"),
                normalizeLegacyClassName(tool.className()),
                tool.description(),
                tool.enabled(),
                tool.scriptPath(),
                tool.parameterSchema(),
                tool.timeoutMs());
    }

    private String normalizeLegacyClassName(String className) {
        if ("com.company.platform.tool.PlatformWebTools".equals(className)) {
            return "io.agent.platform.tool.PlatformWebTools";
        }
        return className;
    }

    private ToolSpec normalize(ToolSpec spec) {
        return new ToolSpec(
                spec.toolId(),
                spec.type(),
                spec.className(),
                spec.description(),
                spec.enabled(),
                spec.scriptPath(),
                spec.parameterSchema(),
                spec.timeoutMs());
    }

    private ToolConfig toConfig(ToolSpec spec) {
        return new ToolConfig(
                spec.toolId(),
                spec.type(),
                spec.className(),
                spec.description(),
                spec.enabled(),
                spec.scriptPath(),
                spec.parameterSchema(),
                spec.timeoutMs());
    }

    private void validate(ToolSpec spec) {
        if (spec.toolId() == null || spec.toolId().isBlank()) {
            throw new IllegalStateException("Tool id cannot be blank");
        }
        if ("java".equals(spec.type())
                && (spec.className() == null || spec.className().isBlank())) {
            throw new IllegalStateException(
                    "Tool definition must have className: " + spec.toolId());
        }
        if ("python".equals(spec.type())
                && (spec.scriptPath() == null || spec.scriptPath().isBlank())) {
            throw new IllegalStateException(
                    "Python tool definition must have scriptPath: " + spec.toolId());
        }
        if (!"java".equals(spec.type())
                && !"python".equals(spec.type())
                && !"remote".equals(spec.type())
                && !"local".equals(spec.type())
                && !"mcp".equals(spec.type())) {
            throw new IllegalStateException(
                    "Unsupported tool type for " + spec.toolId() + ": " + spec.type());
        }
    }

    private void persist() {
        List<ToolConfig> list =
                tools.values().stream()
                        .sorted((a, b) -> a.toolId().compareToIgnoreCase(b.toolId()))
                        .map(this::toConfig)
                        .collect(Collectors.toList());
        configStore.write(PlatformConfigStore.ConfigFile.TOOLS, new ToolConfigRoot(list));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record ToolConfigRoot(List<ToolConfig> tools) {
        public ToolConfigRoot {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public record ToolConfig(
            String toolId,
            String type,
            String className,
            String description,
            boolean enabled,
            String scriptPath,
            Map<String, Object> parameterSchema,
            long timeoutMs) {}
}
