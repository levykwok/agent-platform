/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class PlatformConfigStore {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    private final Map<ConfigFile, Resource> resources = new EnumMap<>(ConfigFile.class);
    private final PlatformStorageLayer storage;

    public PlatformConfigStore(
            @Value("${company.platform.models.config}") Resource models,
            @Value("${company.platform.providers.config}") Resource providers,
            @Value("${company.platform.agents.config}") Resource agents,
            @Value("${company.platform.tools.config}") Resource tools,
            @Value("${company.platform.mcps.config}") Resource mcps,
            @Value("${company.platform.skills.config}") Resource skills,
            PlatformStorageLayer storage) {
        resources.put(ConfigFile.MODELS, models);
        resources.put(ConfigFile.PROVIDERS, providers);
        resources.put(ConfigFile.AGENTS, agents);
        resources.put(ConfigFile.TOOLS, tools);
        resources.put(ConfigFile.MCPS, mcps);
        resources.put(ConfigFile.SKILLS, skills);
        this.storage = storage;
        if (this.storage.isSqliteEnabled()) {
            initSqliteSchema();
        }
    }

    public <T> T read(ConfigFile file, Class<T> type) throws IOException {
        String content = readContent(file);
        return mapper.readValue(content, type);
    }

    public void write(ConfigFile file, Object value) {
        try {
            String content = mapper.writeValueAsString(value);
            if (!storage.isSqliteEnabled()) {
                writeFile(file, content);
            } else {
                writeSqlite(file, content);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist config " + file, e);
        }
    }

    private String readContent(ConfigFile file) throws IOException {
        if (!storage.isSqliteEnabled()) {
            Resource resource = resource(file);
            ensureExists(file, resource);
            return readResourceContent(resource);
        }
        try {
            String value = readSqlite(file);
            if (value == null || value.isBlank()) {
                String migrated = migrateConfiguredFileIfSqliteEmpty(file, value);
                if (migrated != null && !migrated.isBlank()) {
                    return migrated;
                }
                String seeded = seedContent(file);
                writeSqlite(file, seeded);
                return seeded;
            }
            if (isEmptyConfig(file, value) || isDefaultSeedConfig(file, value)) {
                String migrated = migrateConfiguredFileIfSqliteEmpty(file, value);
                if (migrated != null && !migrated.isBlank()) {
                    return migrated;
                }
            }
            return value;
        } catch (SQLException e) {
            throw new IOException("Failed to read config from sqlite for " + file, e);
        }
    }

    private void writeFile(ConfigFile file, String content) {
        Path path = writablePath(file);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist config " + file + ": " + path, e);
        }
    }

    private String readSqlite(ConfigFile file) throws SQLException {
        String table = storage.sqliteConfigTable();
        String sql = "SELECT content FROM " + table + " WHERE config_key = ? LIMIT 1";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, file.fileName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return "";
                }
                return resultSet.getString("content");
            }
        }
    }

    private void writeSqlite(ConfigFile file, String content) throws IOException {
        String table = storage.sqliteConfigTable();
        String sql =
                "INSERT INTO "
                        + table
                        + "(config_key, content, updated_at) VALUES (?, ?, ?) ON"
                        + " CONFLICT(config_key) DO UPDATE SET content = excluded.content,"
                        + " updated_at = excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, file.fileName());
            statement.setString(2, content);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to persist config " + file + " to sqlite", e);
        }
    }

    private void initSqliteSchema() {
        String createSql =
                "CREATE TABLE IF NOT EXISTS "
                        + storage.sqliteConfigTable()
                        + " (\n"
                        + "    config_key TEXT PRIMARY KEY,\n"
                        + "    content TEXT NOT NULL,\n"
                        + "    updated_at TEXT NOT NULL\n"
                        + ")";
        try {
            storage.initializeSqliteSchema(createSql);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Failed to init sqlite config store", e);
        }
    }

    private String seedContent(ConfigFile file) {
        try {
            InputStream defaultResource =
                    new ClassPathResource(file.defaultResource()).getInputStream();
            try (defaultResource) {
                String content = readInput(defaultResource);
                if (content != null && !content.isBlank()) {
                    return content;
                }
            }
        } catch (IOException ignored) {
            // fallback to empty yaml
        }
        return file.emptyYaml();
    }

    private String migrateConfiguredFileIfSqliteEmpty(ConfigFile file, String sqliteContent)
            throws IOException {
        if (sqliteContent != null
                && !sqliteContent.isBlank()
                && !isEmptyConfig(file, sqliteContent)
                && !isDefaultSeedConfig(file, sqliteContent)) {
            return "";
        }
        Resource resource = resource(file);
        if (!resource.isFile()) {
            return "";
        }
        Path path = resource.getFile().toPath();
        if (!Files.isRegularFile(path)) {
            return "";
        }
        String fileContent = Files.readString(path);
        if (fileContent == null || fileContent.isBlank() || isEmptyConfig(file, fileContent)) {
            return "";
        }
        writeSqlite(file, fileContent);
        return fileContent;
    }

    private boolean isEmptyConfig(ConfigFile file, String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        try {
            Map<?, ?> parsed = mapper.readValue(content, Map.class);
            Object value = parsed.get(file.rootKey());
            if (value instanceof Iterable<?> iterable) {
                return !iterable.iterator().hasNext();
            }
            return parsed.isEmpty();
        } catch (Exception ignored) {
            return content.trim().equals(file.emptyYaml().trim());
        }
    }

    private boolean isDefaultSeedConfig(ConfigFile file, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            String defaultContent = classpathDefaultContent(file);
            if (defaultContent == null || defaultContent.isBlank()) {
                return false;
            }
            Object configured = mapper.readValue(content, Object.class);
            Object defaults = mapper.readValue(defaultContent, Object.class);
            return configured.equals(defaults);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String classpathDefaultContent(ConfigFile file) throws IOException {
        Resource defaults = new ClassPathResource(file.defaultResource());
        if (!defaults.exists()) {
            return "";
        }
        try (InputStream input = defaults.getInputStream()) {
            return readInput(input);
        }
    }

    private static String readInput(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String readResourceContent(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return readInput(input);
        }
    }

    public Path path(ConfigFile file) {
        if (storage.isSqliteEnabled()) {
            return storage.resolveWorkspace(file.fileName());
        }
        return writablePath(file);
    }

    private void ensureExists(ConfigFile file, Resource resource) throws IOException {
        if (!resource.isFile()) {
            return;
        }
        Path path = resource.getFile().toPath();
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Resource defaults = new ClassPathResource(file.defaultResource());
        if (defaults.exists()) {
            try (InputStream input = defaults.getInputStream()) {
                Files.copy(input, path);
            }
            return;
        }
        Files.writeString(path, file.emptyYaml());
    }

    private Resource resource(ConfigFile file) {
        Resource resource = resources.get(file);
        if (resource == null) {
            throw new IllegalStateException("No config resource registered for " + file);
        }
        return resource;
    }

    private Path writablePath(ConfigFile file) {
        Resource resource = resource(file);
        try {
            if (!resource.isFile()) {
                throw new IllegalStateException(
                        "Config " + file + " is not writable. Use a file: resource.");
            }
            return resource.getFile().toPath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve config path for " + file, e);
        }
    }

    public enum ConfigFile {
        MODELS("models.yml", "models: []\n"),
        PROVIDERS("providers.yml", "providers: []\n"),
        AGENTS("agents.yml", "agents: []\n"),
        TOOLS("tools.yml", "tools: []\n"),
        MCPS("mcps.yml", "mcps: []\n"),
        SKILLS("skills.yml", "skills: []\n");

        private final String defaultResource;
        private final String emptyYaml;
        private final String fileName;

        ConfigFile(String defaultResource, String emptyYaml) {
            this.defaultResource = defaultResource;
            this.emptyYaml = emptyYaml;
            this.fileName = defaultResource;
        }

        String defaultResource() {
            return defaultResource;
        }

        String emptyYaml() {
            return emptyYaml;
        }

        String fileName() {
            return fileName;
        }

        String rootKey() {
            int dot = fileName.indexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
    }
}
