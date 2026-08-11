/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * SQLite-backed storage for resource content that is too large or too dynamic for the config
 * tables. The workspace remains a runtime cache so existing skill/tooling APIs can keep working.
 */
@Component
public class PlatformArtifactStore {

    private static final String SKILL_FILES = "platform_skill_file_blobs";
    private static final String TOOL_FILES = "platform_tool_file_blobs";
    private static final String SKILL_PACKAGES = "platform_skill_package_blobs";
    private static final String DOCUMENTS = "platform_knowledge_documents";
    private static final String COLLECTIONS = "platform_knowledge_collections";

    private final PlatformStorageLayer storage;

    public PlatformArtifactStore(PlatformStorageLayer storage) {
        this.storage = storage;
        if (storage.isSqliteEnabled()) {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + SKILL_FILES
                            + " (skill_id TEXT NOT NULL, path TEXT NOT NULL, content BLOB NOT NULL, sha256 TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(skill_id, path))",
                    "CREATE TABLE IF NOT EXISTS "
                            + TOOL_FILES
                            + " (tool_id TEXT NOT NULL, path TEXT NOT NULL, content BLOB NOT NULL, sha256 TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(tool_id, path))",
                    "CREATE TABLE IF NOT EXISTS "
                            + SKILL_PACKAGES
                            + " (package_id TEXT PRIMARY KEY, filename TEXT NOT NULL, content BLOB NOT NULL, sha256 TEXT NOT NULL, updated_at TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + DOCUMENTS
                            + " (doc_id TEXT PRIMARY KEY, payload TEXT NOT NULL, raw_content BLOB, sha256 TEXT, updated_at TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS "
                            + COLLECTIONS
                            + " (collection_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL)");
        }
    }

    public boolean enabled() {
        return storage.isSqliteEnabled();
    }

    public Map<String, byte[]> loadSkillFiles(String skillId) {
        if (!enabled()) {
            return Map.of();
        }
        Map<String, byte[]> result = new LinkedHashMap<>();
        String sql = "SELECT path, content FROM " + SKILL_FILES + " WHERE skill_id = ? ORDER BY path";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, skillId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getString("path"), rows.getBytes("content"));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load persisted skill files: " + skillId, e);
        }
    }

    public void saveSkillFile(String skillId, String path, byte[] content) {
        if (!enabled()) {
            return;
        }
        String sql = "INSERT INTO "
                + SKILL_FILES
                + " (skill_id, path, content, sha256, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(skill_id, path) DO UPDATE SET content = excluded.content, sha256 = excluded.sha256, updated_at = excluded.updated_at";
        execute(
                sql,
                statement -> {
                    statement.setString(1, skillId);
                    statement.setString(2, path);
                    statement.setBytes(3, content == null ? new byte[0] : content);
                    statement.setString(4, sha256(content));
                    statement.setString(5, Instant.now().toString());
                });
    }

    public void deleteSkillFiles(String skillId) {
        if (!enabled()) {
            return;
        }
        execute("DELETE FROM " + SKILL_FILES + " WHERE skill_id = ?", statement -> statement.setString(1, skillId));
    }

    public Map<String, byte[]> loadToolFiles(String toolId) {
        if (!enabled()) {
            return Map.of();
        }
        Map<String, byte[]> result = new LinkedHashMap<>();
        String sql = "SELECT path, content FROM " + TOOL_FILES + " WHERE tool_id = ? ORDER BY path";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toolId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getString("path"), rows.getBytes("content"));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load persisted tool files: " + toolId, e);
        }
    }

    public void saveToolFile(String toolId, String path, byte[] content) {
        if (!enabled()) {
            return;
        }
        String sql = "INSERT INTO "
                + TOOL_FILES
                + " (tool_id, path, content, sha256, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(tool_id, path) DO UPDATE SET content = excluded.content, sha256 = excluded.sha256, updated_at = excluded.updated_at";
        execute(
                sql,
                statement -> {
                    statement.setString(1, toolId);
                    statement.setString(2, path);
                    statement.setBytes(3, content == null ? new byte[0] : content);
                    statement.setString(4, sha256(content));
                    statement.setString(5, Instant.now().toString());
                });
    }

    public void deleteToolFiles(String toolId) {
        if (!enabled()) {
            return;
        }
        execute("DELETE FROM " + TOOL_FILES + " WHERE tool_id = ?", statement -> statement.setString(1, toolId));
    }

    public void saveSkillPackage(String packageId, String filename, byte[] content) {
        if (!enabled()) {
            return;
        }
        String sql = "INSERT INTO "
                + SKILL_PACKAGES
                + " (package_id, filename, content, sha256, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(package_id) DO UPDATE SET filename = excluded.filename, content = excluded.content, sha256 = excluded.sha256, updated_at = excluded.updated_at";
        execute(
                sql,
                statement -> {
                    statement.setString(1, packageId);
                    statement.setString(2, filename == null ? "skill-package.zip" : filename);
                    statement.setBytes(3, content == null ? new byte[0] : content);
                    statement.setString(4, sha256(content));
                    statement.setString(5, Instant.now().toString());
                });
    }

    public Optional<BinaryArtifact> loadSkillPackage(String packageId) {
        if (!enabled()) {
            return Optional.empty();
        }
        String sql = "SELECT filename, content, sha256 FROM " + SKILL_PACKAGES + " WHERE package_id = ?";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, packageId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new BinaryArtifact(
                                rows.getString("filename"),
                                rows.getBytes("content"),
                                rows.getString("sha256")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load persisted skill package: " + packageId, e);
        }
    }

    public void deleteSkillPackage(String packageId) {
        if (!enabled()) {
            return;
        }
        execute("DELETE FROM " + SKILL_PACKAGES + " WHERE package_id = ?", statement -> statement.setString(1, packageId));
    }

    public List<StoredDocument> loadDocuments() {
        if (!enabled()) {
            return List.of();
        }
        List<StoredDocument> result = new ArrayList<>();
        String sql = "SELECT doc_id, payload, raw_content, sha256 FROM " + DOCUMENTS + " ORDER BY updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(
                        new StoredDocument(
                                rows.getString("doc_id"),
                                rows.getString("payload"),
                                rows.getBytes("raw_content"),
                                rows.getString("sha256")));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load persisted knowledge documents", e);
        }
    }

    public void saveDocument(String docId, String payload, byte[] rawContent) {
        if (!enabled()) {
            return;
        }
        String sql = "INSERT INTO "
                + DOCUMENTS
                + " (doc_id, payload, raw_content, sha256, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(doc_id) DO UPDATE SET payload = excluded.payload, raw_content = excluded.raw_content, sha256 = excluded.sha256, updated_at = excluded.updated_at";
        execute(
                sql,
                statement -> {
                    statement.setString(1, docId);
                    statement.setString(2, payload == null ? "{}" : payload);
                    if (rawContent == null) {
                        statement.setBytes(3, null);
                    } else {
                        statement.setBytes(3, rawContent);
                    }
                    statement.setString(4, sha256(rawContent));
                    statement.setString(5, Instant.now().toString());
                });
    }

    public void deleteDocument(String docId) {
        if (!enabled()) {
            return;
        }
        execute("DELETE FROM " + DOCUMENTS + " WHERE doc_id = ?", statement -> statement.setString(1, docId));
    }

    public List<StoredJson> loadCollections() {
        if (!enabled()) {
            return List.of();
        }
        List<StoredJson> result = new ArrayList<>();
        String sql = "SELECT collection_id, payload FROM " + COLLECTIONS + " ORDER BY updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new StoredJson(rows.getString("collection_id"), rows.getString("payload")));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load persisted knowledge collections", e);
        }
    }

    public void saveCollection(String collectionId, String payload) {
        if (!enabled()) {
            return;
        }
        String sql = "INSERT INTO "
                + COLLECTIONS
                + " (collection_id, payload, updated_at) VALUES (?, ?, ?) ON CONFLICT(collection_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at";
        execute(
                sql,
                statement -> {
                    statement.setString(1, collectionId);
                    statement.setString(2, payload == null ? "{}" : payload);
                    statement.setString(3, Instant.now().toString());
                });
    }

    public void deleteCollection(String collectionId) {
        if (!enabled()) {
            return;
        }
        execute("DELETE FROM " + COLLECTIONS + " WHERE collection_id = ?", statement -> statement.setString(1, collectionId));
    }

    private void execute(String sql, SqlBinder binder) {
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist platform artifact", e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content == null ? new byte[0] : content);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                out.append(String.format("%02x", value));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate artifact checksum", e);
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws Exception;
    }

    public record BinaryArtifact(String filename, byte[] content, String sha256) {}

    public record StoredDocument(String docId, String payload, byte[] rawContent, String sha256) {}

    public record StoredJson(String id, String payload) {}
}
