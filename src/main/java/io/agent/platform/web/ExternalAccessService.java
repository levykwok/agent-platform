/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.platform.control.PlatformStorageLayer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Managed external API keys and durable, owner-scoped invocation history. */
@Component
public class ExternalAccessService {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalAccessService.class);

    private static final String KEYS = "platform_external_api_keys";
    private static final String INVOCATIONS = "platform_external_invocations";
    private static final int MAX_ACTIVE_KEYS = 10;
    private static final int MAX_HISTORY_LIMIT = 200;
    private static final int MAX_MESSAGE_CHARS = 20_000;
    private static final int MAX_RESPONSE_CHARS = 4_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformStorageLayer storage;

    public ExternalAccessService(PlatformStorageLayer storage) {
        this.storage = storage;
        initialize();
    }

    public record ApiClient(
            String keyId,
            String keyName,
            String keyPrefix,
            PlatformAuthService.Principal principal,
            boolean managed) {}

    public Map<String, Object> createKey(
            PlatformAuthService.Principal principal, String requestedName, String expiresAt) {
        requirePrincipal(principal);
        String name = normalizeName(requestedName);
        Instant expiry = parseExpiry(expiresAt);
        String keyId = "key_" + UUID.randomUUID().toString().replace("-", "");
        String secret = "ap_live_" + randomSecret();
        String prefix = secret.substring(0, Math.min(18, secret.length()));
        String createdAt = now();
        try (Connection connection = storage.connection()) {
            if (countActiveKeys(connection, principal.userId()) >= MAX_ACTIVE_KEYS) {
                throw new PlatformAuthService.AuthException(
                        409, "每个账号最多保留 " + MAX_ACTIVE_KEYS + " 个启用的 API Key");
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO "
                                    + KEYS
                                    + " (key_id,owner_user_id,org_id,name,key_prefix,secret_hash,status,created_at,expires_at) VALUES (?,?,?,?,?,?,'ACTIVE',?,?)")) {
                statement.setString(1, keyId);
                statement.setString(2, principal.userId());
                statement.setString(3, principal.orgId());
                statement.setString(4, name);
                statement.setString(5, prefix);
                statement.setString(6, hash(secret));
                statement.setString(7, createdAt);
                statement.setString(8, expiry == null ? null : expiry.toString());
                statement.executeUpdate();
            }
        } catch (PlatformAuthService.AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("创建外部 API Key 失败", error);
        }
        Map<String, Object> result = keyContract(keyId, name, prefix, "ACTIVE", createdAt, "", expiry);
        result.put("secret", secret);
        result.put("secret_once", true);
        return result;
    }

    public List<Map<String, Object>> listKeys(PlatformAuthService.Principal principal) {
        requirePrincipal(principal);
        String sql =
                "SELECT key_id,name,key_prefix,status,created_at,last_used_at,expires_at,revoked_at FROM "
                        + KEYS
                        + " WHERE owner_user_id=? ORDER BY created_at DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal.userId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(
                            keyContract(
                                    result.getString("key_id"),
                                    result.getString("name"),
                                    result.getString("key_prefix"),
                                    effectiveStatus(
                                            result.getString("status"),
                                            result.getString("expires_at")),
                                    result.getString("created_at"),
                                    result.getString("last_used_at"),
                                    parseInstant(result.getString("expires_at"))));
                    String revoked = text(result.getString("revoked_at"));
                    if (!revoked.isBlank()) rows.get(rows.size() - 1).put("revoked_at", revoked);
                }
            }
        } catch (Exception error) {
            throw failure("读取外部 API Key 失败", error);
        }
        return List.copyOf(rows);
    }

    public void revokeKey(String keyId, PlatformAuthService.Principal principal) {
        requirePrincipal(principal);
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + KEYS
                                        + " SET status='REVOKED',revoked_at=? WHERE key_id=? AND owner_user_id=? AND status='ACTIVE'")) {
            statement.setString(1, now());
            statement.setString(2, keyId);
            statement.setString(3, principal.userId());
            if (statement.executeUpdate() == 0) {
                throw new PlatformAuthService.AuthException(404, "API Key 不存在或已经撤销");
            }
        } catch (PlatformAuthService.AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("撤销外部 API Key 失败", error);
        }
    }

    public ApiClient authenticate(String suppliedKey) {
        String supplied = text(suppliedKey);
        if (supplied.isBlank()) return null;
        String suppliedHash = hash(supplied);
        String sql =
                "SELECT k.key_id,k.name,k.key_prefix,k.owner_user_id,k.org_id,k.status,k.expires_at,"
                        + "u.email,u.display_name,u.status AS user_status,m.role,m.status AS membership_status "
                        + "FROM "
                        + KEYS
                        + " k JOIN platform_users u ON u.user_id=k.owner_user_id "
                        + "JOIN platform_memberships m ON m.user_id=k.owner_user_id AND m.org_id=k.org_id "
                        + "WHERE k.secret_hash=?";
        ApiClient client = null;
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, suppliedHash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                if (!"ACTIVE".equals(result.getString("status"))
                        || !"ACTIVE".equals(result.getString("user_status"))
                        || !"ACTIVE".equals(result.getString("membership_status"))
                        || expired(result.getString("expires_at"))) {
                    return null;
                }
                client =
                        new ApiClient(
                                result.getString("key_id"),
                                result.getString("name"),
                                result.getString("key_prefix"),
                                new PlatformAuthService.Principal(
                                        result.getString("owner_user_id"),
                                        result.getString("email"),
                                        result.getString("display_name"),
                                        result.getString("org_id"),
                                        result.getString("role")),
                                true);
            }
        } catch (Exception error) {
            throw failure("验证外部 API Key 失败", error);
        }
        touchKey(client.keyId());
        return client;
    }

    public ApiClient legacyClient(String suppliedKey) {
        String prefix = text(suppliedKey);
        if (prefix.length() > 10) prefix = prefix.substring(0, 10);
        return new ApiClient(
                "legacy", "Legacy environment key", prefix, null, false);
    }

    public void beginInvocation(
            String requestId,
            ApiClient client,
            String agentId,
            String callMode,
            String sessionId,
            Map<String, Object> safeRequest) {
        String userId = ownerUserId(client);
        String orgId = ownerOrgId(client);
        String sql =
                "INSERT INTO "
                        + INVOCATIONS
                        + " (request_id,key_id,owner_user_id,org_id,agent_id,call_mode,session_id,request_json,status,started_at) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requestId);
            statement.setString(2, client == null ? "" : client.keyId());
            statement.setString(3, userId);
            statement.setString(4, orgId);
            statement.setString(5, text(agentId));
            statement.setString(6, text(callMode));
            statement.setString(7, text(sessionId));
            statement.setString(8, JSON.writeValueAsString(safeRequest == null ? Map.of() : safeRequest));
            statement.setString(9, "RUNNING");
            statement.setString(10, now());
            statement.executeUpdate();
        } catch (Exception error) {
            throw failure("记录外部调用失败", error);
        }
    }

    public void completeInvocation(
            String requestId,
            String runId,
            String status,
            int httpStatus,
            long durationMs,
            String responseExcerpt,
            String errorCode,
            String errorMessage) {
        String sql =
                "UPDATE "
                        + INVOCATIONS
                        + " SET run_id=?,status=?,http_status=?,duration_ms=?,response_excerpt=?,error_code=?,error_message=?,finished_at=? WHERE request_id=?";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, text(runId));
            statement.setString(2, text(status).toUpperCase());
            statement.setInt(3, httpStatus);
            statement.setLong(4, Math.max(0L, durationMs));
            statement.setString(5, truncate(responseExcerpt, MAX_RESPONSE_CHARS));
            statement.setString(6, text(errorCode));
            statement.setString(7, truncate(errorMessage, 1_000));
            statement.setString(8, now());
            statement.setString(9, requestId);
            statement.executeUpdate();
        } catch (Exception error) {
            LOG.warn("Failed to complete external invocation {}", requestId, error);
        }
    }

    public List<Map<String, Object>> invocationHistory(
            PlatformAuthService.Principal principal, int requestedLimit) {
        requirePrincipal(principal);
        int limit = Math.max(1, Math.min(MAX_HISTORY_LIMIT, requestedLimit));
        String sql =
                "SELECT request_id,key_id,agent_id,call_mode,session_id,request_json,status,http_status,"
                        + "duration_ms,run_id,error_code,error_message,response_excerpt,started_at,finished_at "
                        + "FROM "
                        + INVOCATIONS
                        + " WHERE owner_user_id=? ORDER BY started_at DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal.userId());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(invocationContract(result));
            }
        } catch (Exception error) {
            throw failure("读取外部调用历史失败", error);
        }
        return List.copyOf(rows);
    }

    public Map<String, Object> safeRequest(
            ExternalChatRequest request,
            String effectiveUserId,
            String effectiveTenantId,
            String effectiveSessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", text(effectiveTenantId));
        result.put("user_id", request.clientUserId());
        result.put("runtime_user_id", text(effectiveUserId));
        result.put("session_id", text(effectiveSessionId));
        result.put("message", truncate(request.message(), MAX_MESSAGE_CHARS));
        result.put("image_count", request.images().size());
        result.put(
                "files",
                request.files().stream()
                        .map(
                                file ->
                                        Map.of(
                                                "name", file.name(),
                                                "media_type", file.mediaType(),
                                                "characters", file.content().length()))
                        .toList());
        result.put("attachments_replayable", request.images().isEmpty() && request.files().isEmpty());
        return result;
    }

    private Map<String, Object> invocationContract(ResultSet result) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        String runId = text(result.getString("run_id"));
        row.put("request_id", result.getString("request_id"));
        row.put("key_id", result.getString("key_id"));
        row.put("agent_id", result.getString("agent_id"));
        row.put("call_mode", result.getString("call_mode"));
        row.put("session_id", result.getString("session_id"));
        row.put("request", parseJson(result.getString("request_json")));
        row.put("status", result.getString("status"));
        row.put("http_status", result.getInt("http_status"));
        row.put("duration_ms", result.getLong("duration_ms"));
        row.put("run_id", runId);
        row.put("observe_url", runId.isBlank() ? "" : "/platform/live/runs?run_id=" + runId);
        row.put("error_code", text(result.getString("error_code")));
        row.put("error_message", text(result.getString("error_message")));
        row.put("response_excerpt", text(result.getString("response_excerpt")));
        row.put("started_at", result.getString("started_at"));
        row.put("finished_at", text(result.getString("finished_at")));
        return row;
    }

    private void initialize() {
        storage.initializeSqliteSchema(
                "CREATE TABLE IF NOT EXISTS "
                        + KEYS
                        + " (key_id TEXT PRIMARY KEY,owner_user_id TEXT NOT NULL,org_id TEXT NOT NULL,name TEXT NOT NULL,key_prefix TEXT NOT NULL,secret_hash TEXT NOT NULL UNIQUE,status TEXT NOT NULL,created_at TEXT NOT NULL,last_used_at TEXT,expires_at TEXT,revoked_at TEXT)");
        storage.initializeSqliteSchema(
                "CREATE INDEX IF NOT EXISTS idx_platform_external_api_keys_owner_status ON "
                        + KEYS
                        + " (owner_user_id,status,created_at)");
        storage.initializeSqliteSchema(
                "CREATE TABLE IF NOT EXISTS "
                        + INVOCATIONS
                        + " (request_id TEXT PRIMARY KEY,key_id TEXT NOT NULL,owner_user_id TEXT NOT NULL,org_id TEXT NOT NULL,agent_id TEXT NOT NULL,call_mode TEXT NOT NULL,session_id TEXT NOT NULL,request_json TEXT NOT NULL,status TEXT NOT NULL,http_status INTEGER,duration_ms INTEGER,run_id TEXT,error_code TEXT,error_message TEXT,response_excerpt TEXT,started_at TEXT NOT NULL,finished_at TEXT)");
        storage.initializeSqliteSchema(
                "CREATE INDEX IF NOT EXISTS idx_platform_external_invocations_owner_started ON "
                        + INVOCATIONS
                        + " (owner_user_id,started_at DESC)");
    }

    private static Map<String, Object> keyContract(
            String keyId,
            String name,
            String prefix,
            String status,
            String createdAt,
            String lastUsedAt,
            Instant expiresAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key_id", keyId);
        row.put("name", name);
        row.put("key_prefix", prefix);
        row.put("masked_key", prefix + "••••••••");
        row.put("status", status);
        row.put("created_at", createdAt);
        row.put("last_used_at", text(lastUsedAt));
        row.put("expires_at", expiresAt == null ? "" : expiresAt.toString());
        return row;
    }

    private static int countActiveKeys(Connection connection, String userId) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM "
                                + KEYS
                                + " WHERE owner_user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)")) {
            statement.setString(1, userId);
            statement.setString(2, now());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private void touchKey(String keyId) {
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE " + KEYS + " SET last_used_at=? WHERE key_id=?")) {
            statement.setString(1, now());
            statement.setString(2, keyId);
            statement.executeUpdate();
        } catch (Exception error) {
            LOG.warn("Failed to update last_used_at for external API key {}", keyId, error);
        }
    }

    private static Map<String, Object> parseJson(String value) {
        try {
            return value == null || value.isBlank()
                    ? Map.of()
                    : JSON.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Instant parseExpiry(String value) {
        String normalized = text(value);
        if (normalized.isBlank()) return null;
        Instant expiry = parseInstant(normalized);
        if (expiry == null || !expiry.isAfter(Instant.now())) {
            throw new PlatformAuthService.AuthException(400, "API Key 过期时间必须是未来的 ISO-8601 时间");
        }
        return expiry;
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean expired(String value) {
        Instant expiry = parseInstant(value);
        return expiry != null && !expiry.isAfter(Instant.now());
    }

    private static String effectiveStatus(String status, String expiresAt) {
        return "ACTIVE".equals(status) && expired(expiresAt) ? "EXPIRED" : text(status);
    }

    private static String normalizeName(String value) {
        String name = text(value);
        if (name.isBlank()) name = "External integration";
        if (name.length() > 80) {
            throw new PlatformAuthService.AuthException(400, "API Key 名称不能超过 80 个字符");
        }
        return name;
    }

    private static String ownerUserId(ApiClient client) {
        return client != null && client.principal() != null
                ? client.principal().userId()
                : "external-legacy";
    }

    private static String ownerOrgId(ApiClient client) {
        return client != null && client.principal() != null
                ? client.principal().orgId()
                : "external";
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String truncate(String value, int limit) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static void requirePrincipal(PlatformAuthService.Principal principal) {
        if (principal == null) throw new PlatformAuthService.AuthException(401, "请先登录");
    }

    private static IllegalStateException failure(String message, Exception error) {
        return new IllegalStateException(message, error);
    }
}
