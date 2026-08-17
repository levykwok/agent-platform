/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Ownership and visibility metadata for Agent definitions. */
@Component
public class AgentAssetService {

    private static final String TABLE = "platform_agent_assets";

    private final PlatformStorageLayer storage;
    private final AgentDefinitionRegistry registry;
    private final Map<String, Metadata> assets = new ConcurrentHashMap<>();

    @Autowired
    public AgentAssetService(PlatformStorageLayer storage, AgentDefinitionRegistry registry) {
        this.storage = storage;
        this.registry = registry;
        if (storage.isSqliteEnabled()) {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + TABLE
                            + " (agent_id TEXT PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, org_id TEXT NOT NULL, created_by TEXT NOT NULL, visibility TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        }
    }

    @PostConstruct
    private void load() {
        if (!storage.isSqliteEnabled()) return;
        String sql = "SELECT agent_id,owner_type,owner_id,org_id,created_by,visibility,status,created_at,updated_at FROM " + TABLE;
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Metadata metadata =
                        new Metadata(
                                result.getString("agent_id"),
                                result.getString("owner_type"),
                                result.getString("owner_id"),
                                result.getString("org_id"),
                                result.getString("created_by"),
                                result.getString("visibility"),
                                result.getString("status"),
                                result.getString("created_at"),
                                result.getString("updated_at"));
                assets.put(metadata.agentId(), metadata);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load Agent metadata", error);
        }
        syncLegacyAssets();
    }

    public boolean hasMetadata(String agentId) {
        return metadata(agentId) != null;
    }

    public record Metadata(
            String agentId,
            String ownerType,
            String ownerId,
            String orgId,
            String createdBy,
            String visibility,
            String status,
            String createdAt,
            String updatedAt) {}

    public void syncLegacyAssets() {
        for (AgentDefinition definition : registry.allPublished()) {
            ensureSystemAsset(definition.agentId());
        }
    }

    public List<Map<String, Object>> filterRows(
            List<Map<String, Object>> rows, PlatformAuthService.Principal principal) {
        return rows.stream()
                .filter(row -> canRead(String.valueOf(row.get("agent_id")), principal))
                .map(row -> enrich(row, metadata(String.valueOf(row.get("agent_id")))))
                .toList();
    }

    public Map<String, Object> enrich(
            Map<String, Object> row, Metadata metadata) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        if (metadata != null) {
            result.put("owner_type", metadata.ownerType());
            result.put("owner_id", metadata.ownerId());
            result.put("org_id", metadata.orgId());
            result.put("created_by", metadata.createdBy());
            result.put("visibility", metadata.visibility());
            result.put("asset_status", metadata.status());
            result.put("asset_created_at", metadata.createdAt());
            result.put("asset_updated_at", metadata.updatedAt());
        }
        return result;
    }

    public Metadata metadata(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        Metadata metadata = assets.get(agentId);
        if (metadata == null && registry.findPublished(agentId).isPresent()) {
            ensureSystemAsset(agentId);
            metadata = assets.get(agentId);
        }
        return metadata;
    }

    public void requireReadable(String agentId, PlatformAuthService.Principal principal) {
        if (!canRead(agentId, principal)) {
            throw new PlatformAuthService.AuthException(
                    principal == null ? 401 : 404, "Agent 不存在或当前账号无权访问");
        }
    }

    public void requireWritable(String agentId, PlatformAuthService.Principal principal) {
        Metadata metadata = metadata(agentId);
        if (principal == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录后管理 Agent");
        }
        if (metadata == null) {
            throw new PlatformAuthService.AuthException(404, "Agent 不存在");
        }
        if ("PLATFORM_ADMIN".equals(principal.role())) return;
        if ("ORGANIZATION".equals(metadata.ownerType())
                && "ORG_ADMIN".equals(principal.role())
                && metadata.orgId().equals(principal.orgId())) return;
        if (!metadata.ownerId().equals(principal.userId())) {
            throw new PlatformAuthService.AuthException(403, "没有权限修改该 Agent");
        }
    }

    public void registerNew(String agentId, PlatformAuthService.Principal principal, Map<String, Object> payload) {
        if (principal == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录后创建 Agent");
        }
        if (assets.containsKey(agentId)) {
            throw new PlatformAuthService.AuthException(409, "Agent 已存在");
        }
        String requested = string(payload, "visibility", "PRIVATE").toUpperCase();
        String visibility = normalizeVisibility(requested, principal);
        String ownerType = "USER";
        String ownerId = principal.userId();
        if ("ORGANIZATION".equals(visibility)) ownerType = "ORGANIZATION";
        if ("PUBLIC".equals(visibility)) {
            ownerType = "SYSTEM";
            ownerId = "platform";
        }
        Metadata asset =
                new Metadata(
                        agentId,
                        ownerType,
                        ownerId,
                        principal.orgId(),
                        principal.userId(),
                        visibility,
                        "PUBLISHED",
                        now(),
                        now());
        assets.put(agentId, asset);
        persist(asset);
    }

    /** Registers a just-created user Agent after its runtime definition has been persisted. */
    public void registerUserAsset(
            String agentId, PlatformAuthService.Principal principal, Map<String, Object> payload) {
        if (assets.containsKey(agentId)) return;
        registerNew(agentId, principal, payload);
    }

    public void remove(String agentId, PlatformAuthService.Principal principal) {
        requireWritable(agentId, principal);
        assets.remove(agentId);
        if (storage.isSqliteEnabled()) {
            try (Connection connection = storage.connection();
                    PreparedStatement statement =
                            connection.prepareStatement("DELETE FROM " + TABLE + " WHERE agent_id = ?")) {
                statement.setString(1, agentId);
                statement.executeUpdate();
            } catch (Exception error) {
                throw new IllegalStateException("Failed to delete Agent metadata: " + agentId, error);
            }
        }
    }

    public boolean canRead(String agentId, PlatformAuthService.Principal principal) {
        Metadata metadata = metadata(agentId);
        if (metadata == null) return false;
        if (principal == null) return "PUBLIC".equals(metadata.visibility());
        if ("PLATFORM_ADMIN".equals(principal.role())) return true;
        if ("PUBLIC".equals(metadata.visibility())) return true;
        if ("ORGANIZATION".equals(metadata.visibility())
                && metadata.orgId().equals(principal.orgId())) return true;
        return "PRIVATE".equals(metadata.visibility())
                && metadata.ownerId().equals(principal.userId());
    }

    private void ensureSystemAsset(String agentId) {
        if (assets.containsKey(agentId)) return;
        Metadata metadata =
                new Metadata(
                        agentId,
                        "SYSTEM",
                        "platform",
                        "platform",
                        "platform_admin",
                        "PUBLIC",
                        "PUBLISHED",
                        now(),
                        now());
        assets.put(agentId, metadata);
        persist(metadata);
    }

    private void persist(Metadata metadata) {
        if (!storage.isSqliteEnabled()) return;
        String sql =
                "INSERT INTO "
                        + TABLE
                        + " (agent_id,owner_type,owner_id,org_id,created_by,visibility,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(agent_id) DO UPDATE SET owner_type=excluded.owner_type,owner_id=excluded.owner_id,org_id=excluded.org_id,created_by=excluded.created_by,visibility=excluded.visibility,status=excluded.status,updated_at=excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, metadata.agentId());
            statement.setString(2, metadata.ownerType());
            statement.setString(3, metadata.ownerId());
            statement.setString(4, metadata.orgId());
            statement.setString(5, metadata.createdBy());
            statement.setString(6, metadata.visibility());
            statement.setString(7, metadata.status());
            statement.setString(8, metadata.createdAt());
            statement.setString(9, metadata.updatedAt());
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to persist Agent metadata: " + metadata.agentId(), error);
        }
    }

    private String normalizeVisibility(String requested, PlatformAuthService.Principal principal) {
        if ("PUBLIC".equals(requested) && !"PLATFORM_ADMIN".equals(principal.role())) return "PRIVATE";
        if ("ORGANIZATION".equals(requested)
                && !List.of("PLATFORM_ADMIN", "ORG_ADMIN").contains(principal.role())) return "PRIVATE";
        return List.of("PRIVATE", "ORGANIZATION", "PUBLIC").contains(requested)
                ? requested
                : "PRIVATE";
    }

    private String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload == null ? null : payload.get(key);
        return value == null || String.valueOf(value).isBlank()
                ? fallback
                : String.valueOf(value).trim();
    }

    private String now() {
        return Instant.now().toString();
    }
}
