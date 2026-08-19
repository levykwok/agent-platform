/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Persistent, runtime-enforced tool policy. A missing policy keeps an explicitly configured tool
 * enabled; a disabled global or agent policy removes it from the model-visible toolkit.
 */
@Component
public class RuntimeToolGovernance {

    private static final String TABLE = "platform_runtime_tool_policies";
    private static final String GLOBAL = "GLOBAL";
    private static final String AGENT = "AGENT";

    private final PlatformStorageLayer storage;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> policies = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> manifests = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong(1);

    public RuntimeToolGovernance(PlatformStorageLayer storage) {
        this.storage = storage;
        if (storage.isSqliteEnabled()) {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + TABLE
                            + " (scope_type TEXT NOT NULL, scope_id TEXT NOT NULL, tool_id TEXT"
                            + " NOT NULL, payload TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY"
                            + " KEY (scope_type, scope_id, tool_id))");
        }
    }

    @PostConstruct
    void load() {
        if (storage.isSqliteEnabled()) {
            loadSqlite();
        } else {
            loadFile();
        }
    }

    public long version() {
        return version.get();
    }

    public boolean isAllowed(String agentId, String toolId, boolean configuredEnabled) {
        if (!configuredEnabled || toolId == null || toolId.isBlank()) {
            return false;
        }
        if (!enabled(global(toolId), true) || !enabled(agent(agentId, toolId), true)) {
            return false;
        }
        // MCP catalog ids are mcp:<server>:<schema-name>, while AgentScope may expose only the
        // schema name. Deny aliases conservatively so a disabled catalog tool cannot reappear.
        if (!toolId.contains(":")) {
            String suffix = ":" + toolId;
            for (Map<String, Object> policy : policies.values()) {
                String policyTool = String.valueOf(policy.getOrDefault("tool_id", ""));
                String scopeType = String.valueOf(policy.getOrDefault("scope_type", ""));
                String scopeId = String.valueOf(policy.getOrDefault("scope_id", ""));
                boolean appliesToAgent =
                        GLOBAL.equals(scopeType)
                                || (AGENT.equals(scopeType)
                                        && scopeId.equals(safe(agentId, "unknown")));
                if (policyTool.startsWith("mcp:")
                        && policyTool.endsWith(suffix)
                        && appliesToAgent
                        && !enabled(policy, true)) {
                    return false;
                }
            }
        }
        return true;
    }

    public Map<String, Object> global(String toolId) {
        return copy(policies.get(key(GLOBAL, "platform", toolId)));
    }

    public Map<String, Object> agent(String agentId, String toolId) {
        return copy(policies.get(key(AGENT, safe(agentId, "unknown"), toolId)));
    }

    public synchronized Map<String, Object> saveGlobal(
            String toolId, Map<String, Object> payload) {
        return save(GLOBAL, "platform", toolId, payload);
    }

    public synchronized Map<String, Object> saveAgent(
            String agentId, String toolId, Map<String, Object> payload) {
        return save(AGENT, safe(agentId, "unknown"), toolId, payload);
    }

    public Map<String, Object> enrichGlobal(String toolId, Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row == null ? Map.of() : row);
        Map<String, Object> policy = global(toolId);
        if (!policy.isEmpty()) {
            result.putAll(policy);
        }
        result.put("runtime_enabled", enabled(policy, true));
        return result;
    }

    public void recordManifest(
            String agentId, String tenantId, String userId, List<Map<String, Object>> rows) {
        manifests.put(
                manifestKey(agentId, tenantId, userId),
                rows == null
                        ? List.of()
                        : rows.stream().map(RuntimeToolGovernance::copy).toList());
    }

    public List<Map<String, Object>> manifest(String agentId, String tenantId, String userId) {
        return manifests.getOrDefault(manifestKey(agentId, tenantId, userId), List.of()).stream()
                .map(RuntimeToolGovernance::copy)
                .toList();
    }

    public void clearManifests(String agentId) {
        String prefix = safe(agentId, "unknown") + ":";
        manifests.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private Map<String, Object> save(
            String scopeType, String scopeId, String toolId, Map<String, Object> payload) {
        String normalizedToolId = safe(toolId, "");
        if (normalizedToolId.isBlank()) {
            throw new IllegalArgumentException("tool_id is required");
        }
        Map<String, Object> row =
                new LinkedHashMap<>(payload == null ? Map.of() : payload);
        // Browser forms intentionally send nullable optional fields (for example domain=null).
        // Map.copyOf rejects null keys/values, so normalize them before the immutable snapshot.
        row.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        row.put("scope_type", scopeType);
        row.put("scope_id", scopeId);
        row.put("tool_id", normalizedToolId);
        row.putIfAbsent("binding_status", "enabled");
        row.putIfAbsent("binding_visibility", "discoverable");
        row.put("runtime_enabled", enabled(row, true));
        row.put("updated_at", Instant.now().toString());
        policies.put(key(scopeType, scopeId, normalizedToolId), Map.copyOf(row));
        persist(scopeType, scopeId, normalizedToolId, row);
        version.incrementAndGet();
        manifests.clear();
        return copy(row);
    }

    private boolean enabled(Map<String, Object> policy, boolean fallback) {
        if (policy == null || policy.isEmpty()) {
            return fallback;
        }
        Object explicit = policy.get("runtime_enabled");
        if (explicit instanceof Boolean value) {
            return value;
        }
        explicit = policy.get("enabled");
        if (explicit instanceof Boolean value) {
            return value;
        }
        String status = String.valueOf(policy.getOrDefault("binding_status", "enabled"));
        return !List.of("disabled", "blocked", "denied", "off")
                .contains(status.strip().toLowerCase());
    }

    private void persist(
            String scopeType, String scopeId, String toolId, Map<String, Object> row) {
        if (!storage.isSqliteEnabled()) {
            persistFile();
            return;
        }
        String sql =
                "INSERT INTO "
                        + TABLE
                        + " (scope_type, scope_id, tool_id, payload, updated_at) VALUES (?, ?, ?,"
                        + " ?, ?) ON CONFLICT(scope_type, scope_id, tool_id) DO UPDATE SET payload"
                        + " = excluded.payload, updated_at = excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeType);
            statement.setString(2, scopeId);
            statement.setString(3, toolId);
            statement.setString(4, mapper.writeValueAsString(row));
            statement.setString(5, String.valueOf(row.get("updated_at")));
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to persist runtime tool policy", error);
        }
    }

    private void loadSqlite() {
        String sql = "SELECT scope_type, scope_id, tool_id, payload FROM " + TABLE;
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                Map<String, Object> row =
                        mapper.readValue(
                                resultSet.getString("payload"),
                                new TypeReference<Map<String, Object>>() {});
                policies.put(
                        key(
                                resultSet.getString("scope_type"),
                                resultSet.getString("scope_id"),
                                resultSet.getString("tool_id")),
                        Map.copyOf(row));
            }
            version.incrementAndGet();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load runtime tool policies", error);
        }
    }

    private void loadFile() {
        Path file = policyFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Map<String, Object>> rows =
                    mapper.readValue(
                            Files.readString(file),
                            new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> row : rows) {
                policies.put(
                        key(
                                String.valueOf(row.getOrDefault("scope_type", GLOBAL)),
                                String.valueOf(row.getOrDefault("scope_id", "platform")),
                                String.valueOf(row.getOrDefault("tool_id", ""))),
                        Map.copyOf(row));
            }
            version.incrementAndGet();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load runtime tool policies", error);
        }
    }

    private void persistFile() {
        Path file = policyFile();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            List<Map<String, Object>> rows =
                    new ArrayList<>(policies.values().stream().map(RuntimeToolGovernance::copy).toList());
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to persist runtime tool policies", error);
        }
    }

    private Path policyFile() {
        return storage.resolveWorkspace("cache", "runtime-tool-policies.json");
    }

    private static String key(String scopeType, String scopeId, String toolId) {
        return safe(scopeType, GLOBAL)
                + ":"
                + safe(scopeId, "platform")
                + ":"
                + safe(toolId, "");
    }

    private static String manifestKey(String agentId, String tenantId, String userId) {
        return safe(agentId, "unknown")
                + ":"
                + safe(tenantId, "platform")
                + ":"
                + safe(userId, "anonymous");
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static Map<String, Object> copy(Map<String, Object> row) {
        return row == null || row.isEmpty() ? Map.of() : new LinkedHashMap<>(row);
    }
}
