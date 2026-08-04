/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PlatformStorageLayer {

    private final PersistenceMode persistenceMode;
    private final String sqliteUrl;
    private final String sqliteConfigTable;
    private final String sqliteSessionTablePrefix;
    private final String mcpDiscoveryCacheFile;
    private final Path workspace;

    public PlatformStorageLayer(
            @Value("${agent.platform.workspace}") String workspace,
            @Value("${agent.platform.persistence.mode:file}") String mode,
            @Value("${agent.platform.persistence.sqlite.url:}") String sqliteUrl,
            @Value("${agent.platform.persistence.sqlite.config-table:platform_config}")
                    String sqliteConfigTable,
            @Value("${agent.platform.persistence.sqlite.session-table-prefix:platform_}")
                    String sqliteSessionTablePrefix,
            @Value("${agent.platform.mcp.discovery.cache-file:}") String mcpDiscoveryCacheFile) {
        this.workspace = Path.of(workspace).toAbsolutePath().normalize();
        this.persistenceMode = PersistenceMode.from(mode);
        this.sqliteUrl = effectiveSqliteUrl(sqliteUrl);
        this.sqliteConfigTable = sanitizeName(sqliteConfigTable, "platform_config");
        this.sqliteSessionTablePrefix =
                sanitizeName(sqliteSessionTablePrefix, "platform_")
                        .replaceAll("[^A-Za-z0-9_]", "_");
        this.mcpDiscoveryCacheFile = sanitizeName(mcpDiscoveryCacheFile, "");
    }

    public String sqliteConfigTable() {
        return sqliteConfigTable;
    }

    public String sqliteSessionTablePrefix() {
        return sqliteSessionTablePrefix;
    }

    public boolean isSqliteEnabled() {
        return persistenceMode == PersistenceMode.SQLITE;
    }

    public Path workspace() {
        return workspace;
    }

    public Path resolveWorkspace(String... segments) {
        Path target = workspace;
        if (segments != null) {
            for (String segment : segments) {
                if (segment != null && !segment.isBlank()) {
                    target = target.resolve(segment);
                }
            }
        }
        return target.normalize();
    }

    public Path cacheRoot() {
        return resolveWorkspace("cache");
    }

    public Path agentStateRoot() {
        return resolveWorkspace("agent-state");
    }

    public Path agentDefinitionRoot() {
        return resolveWorkspace("workspace");
    }

    public Path agentDefinitionWorkspace(String agentId) {
        return agentDefinitionRoot().resolve(safeSegment(agentId));
    }

    public Path skillsRoot() {
        return resolveWorkspace("skills");
    }

    public Path toolsRoot() {
        return resolveWorkspace("tools");
    }

    public Path toolCodeRoot() {
        return toolsRoot().resolve("code");
    }

    public Path toolTempRoot() {
        return toolsRoot().resolve("tmp");
    }

    public Path toolCodeDirectory(String toolId) {
        return toolCodeRoot().resolve(safeSegment(toolId));
    }

    public Path toolTempDirectory(String toolId) {
        return toolTempRoot().resolve(safeSegment(toolId));
    }

    public Path resolveRelativeToWorkspace(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return workspace();
        }
        Path path = Path.of(relativePath);
        return path.isAbsolute() ? path.normalize() : resolveWorkspace(relativePath);
    }

    public Path mcpDiscoveryCachePath() {
        if (mcpDiscoveryCacheFile.isBlank()) {
            return cacheRoot().resolve("mcp-discovery.json");
        }
        return resolveRelativeToWorkspace(mcpDiscoveryCacheFile);
    }

    public String toWorkspaceRelative(Path path) {
        Path absolute = path == null ? workspace() : path.toAbsolutePath().normalize();
        if (!absolute.startsWith(workspace())) {
            return absolute.toString();
        }
        return workspace().relativize(absolute).toString().replace('\\', '/');
    }

    public String sqliteUrl() {
        return sqliteUrl;
    }

    public Path skillPackagesDir() {
        return cacheRoot().resolve("skill-packages");
    }

    public Path skillPackagesFile() {
        return cacheRoot().resolve("skill-packages.json");
    }

    public Path domainsFile() {
        return cacheRoot().resolve("domains.json");
    }

    public Path modelSlotsFile() {
        return cacheRoot().resolve("model-slots.json");
    }

    public Path skillDirectory(String skillId) {
        return skillsRoot().resolve(safeSegment(skillId));
    }

    public Path agentStateDirectory(String agentId) {
        return agentStateRoot().resolve(safeSegment(agentId));
    }

    public Path ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return path.normalize();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create directory: " + path, e);
        }
    }

    private String sanitizeName(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private String safeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return "agent";
        }
        return segment.trim();
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(sqliteUrl);
    }

    public void initializeSqliteSchema(String... ddl) {
        if (!isSqliteEnabled()) {
            return;
        }
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                if (sql != null && !sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize sqlite schema.", e);
        }
    }

    private String effectiveSqliteUrl(String configuredUrl) {
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            return configuredUrl.trim();
        }
        return "jdbc:sqlite:" + workspace + "/platform-platform.db";
    }

    enum PersistenceMode {
        FILE,
        SQLITE;

        static PersistenceMode from(String mode) {
            return "sqlite".equalsIgnoreCase(mode) ? SQLITE : FILE;
        }
    }
}
