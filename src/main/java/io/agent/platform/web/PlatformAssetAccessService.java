/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.PlatformStorageLayer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Common ownership and visibility metadata for platform-managed assets. */
@Component
public class PlatformAssetAccessService {

    private static final String TABLE = "platform_asset_metadata";
    private final PlatformStorageLayer storage;
    private final Map<String, Metadata> assets = new ConcurrentHashMap<>();

    public PlatformAssetAccessService(PlatformStorageLayer storage) {
        this.storage = storage;
        if (storage.isSqliteEnabled()) {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + TABLE
                            + " (asset_type TEXT NOT NULL, asset_id TEXT NOT NULL, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, org_id TEXT NOT NULL, created_by TEXT NOT NULL, visibility TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(asset_type, asset_id))");
            load();
        }
    }

    public record Metadata(
            String assetType,
            String assetId,
            String ownerType,
            String ownerId,
            String orgId,
            String createdBy,
            String visibility,
            String status,
            String createdAt,
            String updatedAt) {}

    public synchronized Metadata metadata(String assetType, String assetId) {
        return assets.get(key(assetType, assetId));
    }

    public synchronized void remove(String assetType, String assetId) {
        String type = normalize(assetType);
        String id = normalize(assetId);
        if (type.isBlank() || id.isBlank()) return;
        assets.remove(key(type, id));
        if (!storage.isSqliteEnabled()) return;
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM " + TABLE + " WHERE asset_type = ? AND asset_id = ?")) {
            statement.setString(1, type);
            statement.setString(2, id);
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to remove platform asset metadata", error);
        }
    }

    /** Existing registry entries are platform-owned public assets. */
    public synchronized Metadata ensurePublic(String assetType, String assetId) {
        String normalizedType = normalize(assetType);
        String normalizedId = normalize(assetId);
        if (normalizedType.isBlank() || normalizedId.isBlank()) return null;
        Metadata existing = assets.get(key(normalizedType, normalizedId));
        if (existing != null) return existing;
        Metadata created =
                new Metadata(
                        normalizedType,
                        normalizedId,
                        "SYSTEM",
                        "platform",
                        "platform",
                        "platform_admin",
                        "PUBLIC",
                        "PUBLISHED",
                        now(),
                        now());
        assets.put(key(normalizedType, normalizedId), created);
        persist(created);
        return created;
    }

    public synchronized Metadata registerNew(
            String assetType,
            String assetId,
            PlatformAuthService.Principal principal,
            String requestedVisibility,
            String status) {
        if (principal == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录后创建资产");
        }
        String type = normalize(assetType);
        String id = normalize(assetId);
        String visibility = normalizeVisibility(requestedVisibility, principal);
        String ownerType = "USER";
        String ownerId = principal.userId();
        if ("ORGANIZATION".equals(visibility)) ownerType = "ORGANIZATION";
        if ("PUBLIC".equals(visibility)) {
            ownerType = "SYSTEM";
            ownerId = "platform";
        }
        Metadata metadata =
                new Metadata(
                        type,
                        id,
                        ownerType,
                        ownerId,
                        principal.orgId(),
                        principal.userId(),
                        visibility,
                        normalize(status).isBlank() ? "PUBLISHED" : normalize(status),
                        now(),
                        now());
        assets.put(key(type, id), metadata);
        persist(metadata);
        return metadata;
    }

    public List<Map<String, Object>> filterRows(
            String assetType,
            List<Map<String, Object>> rows,
            String idKey,
            PlatformAuthService.Principal principal) {
        return rows.stream()
                .filter(
                        row -> {
                            String id = String.valueOf(row.getOrDefault(idKey, ""));
                            return canRead(assetType, id, principal);
                        })
                .map(
                        row -> {
                            Map<String, Object> copy = new LinkedHashMap<>(row);
                            Metadata metadata = metadata(assetType, String.valueOf(row.get(idKey)));
                            if (metadata != null) {
                                copy.put("owner_type", metadata.ownerType());
                                copy.put("owner_id", metadata.ownerId());
                                copy.put("org_id", metadata.orgId());
                                copy.put("created_by", metadata.createdBy());
                                copy.put("visibility", metadata.visibility());
                                copy.put("asset_status", metadata.status());
                            }
                            return copy;
                        })
                .toList();
    }

    public boolean canRead(
            String assetType, String assetId, PlatformAuthService.Principal principal) {
        Metadata metadata = metadata(assetType, assetId);
        if (metadata == null) metadata = ensurePublic(assetType, assetId);
        if (metadata == null) return false;
        if (principal == null) return "PUBLIC".equals(metadata.visibility());
        if ("PLATFORM_ADMIN".equals(principal.role())) return true;
        if ("PUBLIC".equals(metadata.visibility())) return true;
        if ("ORGANIZATION".equals(metadata.visibility())
                && metadata.orgId().equals(principal.orgId())) return true;
        return "PRIVATE".equals(metadata.visibility())
                && metadata.ownerId().equals(principal.userId());
    }

    public void requireReadable(
            String assetType, String assetId, PlatformAuthService.Principal principal) {
        if (!canRead(assetType, assetId, principal)) {
            throw new PlatformAuthService.AuthException(
                    principal == null ? 401 : 404, "资产不存在或当前账号无权访问");
        }
    }

    public void requireWritable(
            String assetType, String assetId, PlatformAuthService.Principal principal) {
        Metadata metadata = metadata(assetType, assetId);
        if (principal == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录后管理资产");
        }
        if (metadata == null) {
            throw new PlatformAuthService.AuthException(404, "资产不存在");
        }
        if ("PLATFORM_ADMIN".equals(principal.role())) return;
        if ("ORGANIZATION".equals(metadata.ownerType())
                && "ORG_ADMIN".equals(principal.role())
                && metadata.orgId().equals(principal.orgId())) return;
        if (metadata.ownerId().equals(principal.userId())) return;
        throw new PlatformAuthService.AuthException(403, "没有权限修改该资产");
    }

    private void load() {
        String sql =
                "SELECT asset_type,asset_id,owner_type,owner_id,org_id,created_by,visibility,status,created_at,updated_at FROM "
                        + TABLE;
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Metadata metadata =
                        new Metadata(
                                result.getString("asset_type"),
                                result.getString("asset_id"),
                                result.getString("owner_type"),
                                result.getString("owner_id"),
                                result.getString("org_id"),
                                result.getString("created_by"),
                                result.getString("visibility"),
                                result.getString("status"),
                                result.getString("created_at"),
                                result.getString("updated_at"));
                assets.put(key(metadata.assetType(), metadata.assetId()), metadata);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load platform asset metadata", error);
        }
    }

    private void persist(Metadata metadata) {
        if (!storage.isSqliteEnabled()) return;
        String sql =
                "INSERT INTO "
                        + TABLE
                        + " (asset_type,asset_id,owner_type,owner_id,org_id,created_by,visibility,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(asset_type,asset_id) DO UPDATE SET owner_type=excluded.owner_type,owner_id=excluded.owner_id,org_id=excluded.org_id,created_by=excluded.created_by,visibility=excluded.visibility,status=excluded.status,updated_at=excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, metadata.assetType());
            statement.setString(2, metadata.assetId());
            statement.setString(3, metadata.ownerType());
            statement.setString(4, metadata.ownerId());
            statement.setString(5, metadata.orgId());
            statement.setString(6, metadata.createdBy());
            statement.setString(7, metadata.visibility());
            statement.setString(8, metadata.status());
            statement.setString(9, metadata.createdAt());
            statement.setString(10, metadata.updatedAt());
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to persist platform asset metadata", error);
        }
    }

    private static String key(String type, String id) {
        return normalize(type) + "\u0000" + normalize(id);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeVisibility(
            String requested, PlatformAuthService.Principal principal) {
        String value = normalize(requested).toUpperCase();
        if ("PUBLIC".equals(value) && !"PLATFORM_ADMIN".equals(principal.role())) return "PRIVATE";
        if ("ORGANIZATION".equals(value)
                && !List.of("PLATFORM_ADMIN", "ORG_ADMIN").contains(principal.role())) return "PRIVATE";
        return List.of("PRIVATE", "ORGANIZATION", "PUBLIC").contains(value) ? value : "PRIVATE";
    }

    private static String now() {
        return Instant.now().toString();
    }
}
