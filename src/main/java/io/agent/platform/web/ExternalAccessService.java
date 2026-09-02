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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 60;
    private static final int MAX_RATE_LIMIT_PER_MINUTE = 6_000;
    private static final int DEFAULT_ROTATION_GRACE_MINUTES = 15;
    private static final int MAX_ROTATION_GRACE_MINUTES = 1_440;
    private static final Set<String> SUPPORTED_CAPABILITIES = Set.of("chat", "stream");
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
            boolean managed,
            Set<String> allowedAgentIds,
            Set<String> capabilities,
            int rateLimitPerMinute) {

        public ApiClient {
            allowedAgentIds = allowedAgentIds == null ? Set.of() : Set.copyOf(allowedAgentIds);
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }

        public boolean allowsAgent(String agentId) {
            return !managed || allowedAgentIds.isEmpty() || allowedAgentIds.contains(text(agentId));
        }

        public boolean allowsCapability(String capability) {
            return !managed || capabilities.contains(text(capability).toLowerCase());
        }
    }

    public record AccessDecision(
            boolean allowed,
            int httpStatus,
            String code,
            String message,
            int limit,
            int remaining,
            int retryAfterSeconds) {

        static AccessDecision permit(int limit, int remaining, int retryAfterSeconds) {
            return new AccessDecision(true, 200, "", "", limit, remaining, retryAfterSeconds);
        }

        static AccessDecision reject(int status, String code, String message) {
            return new AccessDecision(false, status, code, message, 0, 0, 0);
        }
    }

    public Map<String, Object> createKey(
            PlatformAuthService.Principal principal, String requestedName, String expiresAt) {
        return createKey(
                principal,
                requestedName,
                expiresAt,
                List.of(),
                List.copyOf(SUPPORTED_CAPABILITIES),
                DEFAULT_RATE_LIMIT_PER_MINUTE);
    }

    public Map<String, Object> createKey(
            PlatformAuthService.Principal principal,
            String requestedName,
            String expiresAt,
            List<String> requestedAgentIds,
            List<String> requestedCapabilities,
            Integer requestedRateLimitPerMinute) {
        requirePrincipal(principal);
        String name = normalizeName(requestedName);
        Instant expiry = parseExpiry(expiresAt);
        Set<String> agentIds = normalizeAgentIds(requestedAgentIds);
        Set<String> capabilities = normalizeCapabilities(requestedCapabilities);
        int rateLimitPerMinute = normalizeRateLimit(requestedRateLimitPerMinute);
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
                                    + " (key_id,owner_user_id,org_id,name,key_prefix,secret_hash,status,created_at,expires_at,agent_ids_json,capabilities_json,rate_limit_per_minute) VALUES (?,?,?,?,?,?,'ACTIVE',?,?,?,?,?)")) {
                statement.setString(1, keyId);
                statement.setString(2, principal.userId());
                statement.setString(3, principal.orgId());
                statement.setString(4, name);
                statement.setString(5, prefix);
                statement.setString(6, hash(secret));
                statement.setString(7, createdAt);
                statement.setString(8, expiry == null ? null : expiry.toString());
                statement.setString(9, writeJson(agentIds));
                statement.setString(10, writeJson(capabilities));
                statement.setInt(11, rateLimitPerMinute);
                statement.executeUpdate();
            }
        } catch (PlatformAuthService.AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("创建外部 API Key 失败", error);
        }
        Map<String, Object> result =
                keyContract(
                        keyId,
                        name,
                        prefix,
                        "ACTIVE",
                        createdAt,
                        "",
                        expiry,
                        agentIds,
                        capabilities,
                        rateLimitPerMinute,
                        "",
                        "",
                        "",
                        "");
        result.put("secret", secret);
        result.put("secret_once", true);
        return result;
    }

    public List<Map<String, Object>> listKeys(PlatformAuthService.Principal principal) {
        requirePrincipal(principal);
        String sql =
                "SELECT key_id,name,key_prefix,status,created_at,last_used_at,expires_at,revoked_at,"
                        + "agent_ids_json,capabilities_json,rate_limit_per_minute,rotation_parent_key_id,"
                        + "rotated_to_key_id,rotation_grace_until,revoked_by_user_id FROM "
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
                                    parseInstant(result.getString("expires_at")),
                                    parseStringSet(result.getString("agent_ids_json")),
                                    parseStringSet(result.getString("capabilities_json")),
                                    result.getInt("rate_limit_per_minute"),
                                    result.getString("rotation_parent_key_id"),
                                    result.getString("rotated_to_key_id"),
                                    result.getString("rotation_grace_until"),
                                    result.getString("revoked_by_user_id")));
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
                                        + " SET status='REVOKED',revoked_at=?,revoked_by_user_id=? WHERE key_id=? AND owner_user_id=? AND status IN ('ACTIVE','SUSPENDED')")) {
            statement.setString(1, now());
            statement.setString(2, principal.userId());
            statement.setString(3, keyId);
            statement.setString(4, principal.userId());
            if (statement.executeUpdate() == 0) {
                throw new PlatformAuthService.AuthException(404, "API Key 不存在或已经撤销");
            }
        } catch (PlatformAuthService.AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("撤销外部 API Key 失败", error);
        }
    }

    public Map<String, Object> rotateKey(
            String keyId,
            PlatformAuthService.Principal principal,
            Integer requestedGraceMinutes) {
        requirePrincipal(principal);
        int graceMinutes = normalizeRotationGrace(requestedGraceMinutes);
        String newKeyId = "key_" + UUID.randomUUID().toString().replace("-", "");
        String secret = "ap_live_" + randomSecret();
        String prefix = secret.substring(0, Math.min(18, secret.length()));
        String createdAt = now();
        String name;
        String originalOrgId;
        String originalExpiry;
        Set<String> agentIds;
        Set<String> capabilities;
        int rateLimitPerMinute;
        Instant rotationGrace = Instant.now().plus(graceMinutes, ChronoUnit.MINUTES);
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT org_id,name,expires_at,agent_ids_json,capabilities_json,rate_limit_per_minute"
                                        + " FROM "
                                        + KEYS
                                        + " WHERE key_id=? AND owner_user_id=? AND status='ACTIVE'"
                                        + " AND (rotated_to_key_id IS NULL OR rotated_to_key_id='')")) {
                    statement.setString(1, keyId);
                    statement.setString(2, principal.userId());
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next() || expired(row.getString("expires_at"))) {
                            throw new PlatformAuthService.AuthException(
                                    404, "API Key 不存在、已撤销或已经过期");
                        }
                        originalOrgId = row.getString("org_id");
                        name = row.getString("name");
                        originalExpiry = row.getString("expires_at");
                        agentIds = parseStringSet(row.getString("agent_ids_json"));
                        capabilities = parseStringSet(row.getString("capabilities_json"));
                        rateLimitPerMinute = row.getInt("rate_limit_per_minute");
                    }
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO "
                                        + KEYS
                                        + " (key_id,owner_user_id,org_id,name,key_prefix,secret_hash,status,created_at,expires_at,agent_ids_json,capabilities_json,rate_limit_per_minute,rotation_parent_key_id)"
                                        + " VALUES (?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?)")) {
                    statement.setString(1, newKeyId);
                    statement.setString(2, principal.userId());
                    statement.setString(3, originalOrgId);
                    statement.setString(4, name);
                    statement.setString(5, prefix);
                    statement.setString(6, hash(secret));
                    statement.setString(7, createdAt);
                    statement.setString(8, originalExpiry);
                    statement.setString(9, writeJson(agentIds));
                    statement.setString(10, writeJson(capabilities));
                    statement.setInt(11, rateLimitPerMinute);
                    statement.setString(12, keyId);
                    statement.executeUpdate();
                }
                if (graceMinutes == 0) {
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "UPDATE "
                                            + KEYS
                                            + " SET status='REVOKED',revoked_at=?,revoked_by_user_id=?,rotated_to_key_id=?,rotation_grace_until=? WHERE key_id=?")) {
                        statement.setString(1, createdAt);
                        statement.setString(2, principal.userId());
                        statement.setString(3, newKeyId);
                        statement.setString(4, createdAt);
                        statement.setString(5, keyId);
                        statement.executeUpdate();
                    }
                } else {
                    Instant original = parseInstant(originalExpiry);
                    Instant oldKeyExpiry =
                            original != null && original.isBefore(rotationGrace)
                                    ? original
                                    : rotationGrace;
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "UPDATE "
                                            + KEYS
                                            + " SET expires_at=?,rotated_to_key_id=?,rotation_grace_until=? WHERE key_id=?")) {
                        statement.setString(1, oldKeyExpiry.toString());
                        statement.setString(2, newKeyId);
                        statement.setString(3, oldKeyExpiry.toString());
                        statement.setString(4, keyId);
                        statement.executeUpdate();
                    }
                    rotationGrace = oldKeyExpiry;
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (PlatformAuthService.AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("轮换外部 API Key 失败", error);
        }
        Map<String, Object> result =
                keyContract(
                        newKeyId,
                        name,
                        prefix,
                        "ACTIVE",
                        createdAt,
                        "",
                        parseInstant(originalExpiry),
                        agentIds,
                        capabilities,
                        rateLimitPerMinute,
                        keyId,
                        "",
                        "",
                        "");
        result.put("secret", secret);
        result.put("secret_once", true);
        result.put("previous_key_id", keyId);
        result.put("previous_key_valid_until", rotationGrace.toString());
        return result;
    }

    public List<Map<String, Object>> adminListKeys(
            PlatformAuthService.Principal principal,
            String requestedQuery,
            String requestedStatus,
            int requestedLimit) {
        requirePlatformAdmin(principal);
        int limit = Math.max(1, Math.min(MAX_HISTORY_LIMIT, requestedLimit));
        String query = text(requestedQuery).toLowerCase();
        String status = text(requestedStatus).toUpperCase();
        if (status.isBlank()) status = "ALL";
        if (!Set.of("ALL", "ACTIVE", "SUSPENDED", "REVOKED", "EXPIRED").contains(status)) {
            throw new PlatformAuthService.AuthException(400, "不支持的 API Key 状态筛选");
        }
        String sql =
                "SELECT k.key_id,k.name,k.key_prefix,k.status,k.created_at,k.last_used_at,k.expires_at,k.revoked_at,"
                        + "k.agent_ids_json,k.capabilities_json,k.rate_limit_per_minute,k.rotation_parent_key_id,"
                        + "k.rotated_to_key_id,k.rotation_grace_until,k.revoked_by_user_id,k.owner_user_id,k.org_id,"
                        + "COALESCE(u.email,'') AS owner_email,COALESCE(u.display_name,'') AS owner_name FROM "
                        + KEYS
                        + " k LEFT JOIN platform_users u ON u.user_id=k.owner_user_id"
                        + " WHERE (?='' OR lower(k.name) LIKE ? OR lower(k.key_prefix) LIKE ? OR lower(COALESCE(u.email,'')) LIKE ?)"
                        + " AND (?='ALL' OR (?='ACTIVE' AND k.status='ACTIVE' AND (k.expires_at IS NULL OR k.expires_at>?))"
                        + " OR (?='REVOKED' AND k.status='REVOKED')"
                        + " OR (?='SUSPENDED' AND k.status='SUSPENDED')"
                        + " OR (?='EXPIRED' AND k.status IN ('ACTIVE','SUSPENDED') AND k.expires_at IS NOT NULL AND k.expires_at<=?))"
                        + " ORDER BY k.created_at DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        String pattern = "%" + query + "%";
        String instant = now();
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, query);
            statement.setString(2, pattern);
            statement.setString(3, pattern);
            statement.setString(4, pattern);
            statement.setString(5, status);
            statement.setString(6, status);
            statement.setString(7, instant);
            statement.setString(8, status);
            statement.setString(9, status);
            statement.setString(10, status);
            statement.setString(11, instant);
            statement.setInt(12, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> item =
                            keyContract(
                                    result.getString("key_id"),
                                    result.getString("name"),
                                    result.getString("key_prefix"),
                                    effectiveStatus(
                                            result.getString("status"),
                                            result.getString("expires_at")),
                                    result.getString("created_at"),
                                    result.getString("last_used_at"),
                                    parseInstant(result.getString("expires_at")),
                                    parseStringSet(result.getString("agent_ids_json")),
                                    parseStringSet(result.getString("capabilities_json")),
                                    result.getInt("rate_limit_per_minute"),
                                    result.getString("rotation_parent_key_id"),
                                    result.getString("rotated_to_key_id"),
                                    result.getString("rotation_grace_until"),
                                    result.getString("revoked_by_user_id"));
                    item.put("owner_user_id", result.getString("owner_user_id"));
                    item.put("owner_email", result.getString("owner_email"));
                    item.put("owner_name", result.getString("owner_name"));
                    item.put("org_id", result.getString("org_id"));
                    rows.add(item);
                }
            }
        } catch (Exception error) {
            throw failure("读取全平台 API Key 失败", error);
        }
        return List.copyOf(rows);
    }

    public void adminRevokeKey(String keyId, PlatformAuthService.Principal principal) {
        requirePlatformAdmin(principal);
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + KEYS
                                        + " SET status='REVOKED',revoked_at=?,revoked_by_user_id=? WHERE key_id=? AND status IN ('ACTIVE','SUSPENDED')")) {
            statement.setString(1, now());
            statement.setString(2, principal.userId());
            statement.setString(3, keyId);
            if (statement.executeUpdate() == 0) {
                throw new PlatformAuthService.AuthException(404, "API Key 不存在或已经撤销");
            }
        } catch (PlatformAuthService.AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("管理员撤销外部 API Key 失败", error);
        }
    }

    public void adminSetKeySuspended(
            String keyId, PlatformAuthService.Principal principal, boolean suspended) {
        requirePlatformAdmin(principal);
        String expected = suspended ? "ACTIVE" : "SUSPENDED";
        String target = suspended ? "SUSPENDED" : "ACTIVE";
        String sql =
                "UPDATE "
                        + KEYS
                        + " SET status=? WHERE key_id=? AND status=?"
                        + (suspended ? "" : " AND (expires_at IS NULL OR expires_at>?)");
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target);
            statement.setString(2, keyId);
            statement.setString(3, expected);
            if (!suspended) statement.setString(4, now());
            if (statement.executeUpdate() == 0) {
                throw new PlatformAuthService.AuthException(
                        404,
                        suspended
                                ? "API Key 不存在或当前不可冻结"
                                : "API Key 不存在、未冻结或已经过期");
            }
        } catch (PlatformAuthService.AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure(suspended ? "冻结外部 API Key 失败" : "恢复外部 API Key 失败", error);
        }
    }

    public ApiClient authenticate(String suppliedKey) {
        String supplied = text(suppliedKey);
        if (supplied.isBlank()) return null;
        ApiClient client = findManagedClient(supplied, true);
        if (client != null) touchKey(client.keyId());
        return client;
    }

    public ApiClient identifyManagedCredential(String suppliedKey) {
        String supplied = text(suppliedKey);
        return supplied.isBlank() ? null : findManagedClient(supplied, false);
    }

    private ApiClient findManagedClient(String supplied, boolean requireActive) {
        String suppliedHash = hash(supplied);
        String sql =
                "SELECT k.key_id,k.name,k.key_prefix,k.owner_user_id,k.org_id,k.status,k.expires_at,"
                        + "k.agent_ids_json,k.capabilities_json,k.rate_limit_per_minute,"
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
                if (requireActive
                        && (!"ACTIVE".equals(result.getString("status"))
                                || !"ACTIVE".equals(result.getString("user_status"))
                                || !"ACTIVE".equals(result.getString("membership_status"))
                                || expired(result.getString("expires_at")))) {
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
                                true,
                                parseStringSet(result.getString("agent_ids_json")),
                                parseStringSet(result.getString("capabilities_json")),
                                result.getInt("rate_limit_per_minute"));
            }
        } catch (Exception error) {
            throw failure("验证外部 API Key 失败", error);
        }
        return client;
    }

    public ApiClient legacyClient(String suppliedKey) {
        String prefix = text(suppliedKey);
        if (prefix.length() > 10) prefix = prefix.substring(0, 10);
        return new ApiClient(
                "legacy",
                "Legacy environment key",
                prefix,
                null,
                false,
                Set.of(),
                SUPPORTED_CAPABILITIES,
                0);
    }

    public AccessDecision authorizeInvocation(
            ApiClient client, String agentId, String capability) {
        if (client == null) {
            return AccessDecision.reject(
                    401, "invalid_api_key", "A valid API key is required.");
        }
        if (!client.allowsAgent(agentId)) {
            return AccessDecision.reject(
                    403,
                    "agent_not_allowed",
                    "This API key is not allowed to invoke the requested Agent.");
        }
        if (!client.allowsCapability(capability)) {
            return AccessDecision.reject(
                    403,
                    "capability_not_allowed",
                    "This API key does not allow the requested invocation mode.");
        }
        if (!client.managed() || client.rateLimitPerMinute() <= 0) {
            return AccessDecision.permit(0, 0, 0);
        }
        return consumeRateLimit(client);
    }

    public void recordRejectedRequest(
            ApiClient client,
            String suppliedKey,
            String agentId,
            String callMode,
            String requestPath,
            String sourceIp,
            int httpStatus,
            String errorCode,
            String errorMessage) {
        String timestamp = now();
        String requestId = "deny_" + UUID.randomUUID().toString().replace("-", "");
        String fingerprint =
                text(suppliedKey).isBlank() ? "" : hash(suppliedKey).substring(0, 16);
        String sql =
                "INSERT INTO "
                        + INVOCATIONS
                        + " (request_id,key_id,owner_user_id,org_id,agent_id,call_mode,session_id,request_json,status,http_status,duration_ms,error_code,error_message,started_at,finished_at,request_path,source_ip,credential_fingerprint)"
                        + " VALUES (?,?,?,?,?,?,?,'{}','REJECTED',?,0,?,?,?,?,?,?,?)";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requestId);
            statement.setString(2, client == null ? "" : client.keyId());
            statement.setString(3, client == null ? "anonymous" : ownerUserId(client));
            statement.setString(4, client == null ? "external" : ownerOrgId(client));
            statement.setString(5, truncate(agentId, 160));
            statement.setString(6, truncate(callMode, 32));
            statement.setString(7, "");
            statement.setInt(8, httpStatus);
            statement.setString(9, truncate(errorCode, 80));
            statement.setString(10, truncate(errorMessage, 1_000));
            statement.setString(11, timestamp);
            statement.setString(12, timestamp);
            statement.setString(13, truncate(requestPath, 500));
            statement.setString(14, truncate(sourceIp, 100));
            statement.setString(15, fingerprint);
            statement.executeUpdate();
        } catch (Exception error) {
            LOG.warn("Failed to audit rejected external API request {}", requestId, error);
        }
    }

    private AccessDecision consumeRateLimit(ApiClient client) {
        Instant instant = Instant.now();
        String windowStart = instant.truncatedTo(ChronoUnit.MINUTES).toString();
        int retryAfter = Math.max(1, 60 - (int) (instant.getEpochSecond() % 60));
        int count;
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement =
                        connection.prepareStatement("PRAGMA busy_timeout=5000")) {
                    statement.execute();
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM platform_external_api_rate_limits"
                                        + " WHERE key_id=? AND window_start<?")) {
                    statement.setString(1, client.keyId());
                    statement.setString(2, windowStart);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO platform_external_api_rate_limits"
                                        + " (key_id,window_start,request_count) VALUES (?,?,1)"
                                        + " ON CONFLICT(key_id,window_start) DO UPDATE SET request_count=request_count+1")) {
                    statement.setString(1, client.keyId());
                    statement.setString(2, windowStart);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT request_count FROM platform_external_api_rate_limits"
                                        + " WHERE key_id=? AND window_start=?")) {
                    statement.setString(1, client.keyId());
                    statement.setString(2, windowStart);
                    try (ResultSet result = statement.executeQuery()) {
                        count = result.next() ? result.getInt(1) : client.rateLimitPerMinute() + 1;
                    }
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception error) {
            LOG.warn("Failed to enforce rate limit for external API key {}", client.keyId(), error);
            return new AccessDecision(
                    false,
                    503,
                    "rate_limit_unavailable",
                    "The external API rate limiter is temporarily unavailable.",
                    client.rateLimitPerMinute(),
                    0,
                    retryAfter);
        }
        int remaining = Math.max(0, client.rateLimitPerMinute() - count);
        if (count > client.rateLimitPerMinute()) {
            return new AccessDecision(
                    false,
                    429,
                    "rate_limit_exceeded",
                    "This API key has exceeded its per-minute request limit.",
                    client.rateLimitPerMinute(),
                    0,
                    retryAfter);
        }
        return AccessDecision.permit(client.rateLimitPerMinute(), remaining, retryAfter);
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
                        + "duration_ms,run_id,error_code,error_message,response_excerpt,started_at,finished_at,"
                        + "request_path,source_ip,credential_fingerprint "
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

    public Map<String, Object> adminOverview(
            PlatformAuthService.Principal principal, int requestedLimit) {
        requirePlatformAdmin(principal);
        int limit = Math.max(1, Math.min(MAX_HISTORY_LIMIT, requestedLimit));
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = storage.connection()) {
            result.put("keys", adminKeyStats(connection));
            result.put("invocations", adminInvocationStats(connection));
            result.put("top_agents", adminTopAgents(connection));
            result.put("recent", adminRecentInvocations(connection, limit));
        } catch (Exception error) {
            throw failure("读取外部 API 管理审计失败", error);
        }
        return result;
    }

    private Map<String, Object> adminKeyStats(Connection connection) throws Exception {
        String sql =
                "SELECT COUNT(*) AS total,"
                        + "SUM(CASE WHEN status='ACTIVE' AND (expires_at IS NULL OR expires_at>?) THEN 1 ELSE 0 END) AS active,"
                        + "SUM(CASE WHEN status='REVOKED' THEN 1 ELSE 0 END) AS revoked,"
                        + "SUM(CASE WHEN status='SUSPENDED' AND (expires_at IS NULL OR expires_at>?) THEN 1 ELSE 0 END) AS suspended,"
                        + "SUM(CASE WHEN status IN ('ACTIVE','SUSPENDED') AND expires_at IS NOT NULL AND expires_at<=? THEN 1 ELSE 0 END) AS expired,"
                        + "COUNT(DISTINCT owner_user_id) AS owners FROM "
                        + KEYS;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, now());
            statement.setString(2, now());
            statement.setString(3, now());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Map.of();
                return Map.of(
                        "total", row.getInt("total"),
                        "active", row.getInt("active"),
                        "revoked", row.getInt("revoked"),
                        "suspended", row.getInt("suspended"),
                        "expired", row.getInt("expired"),
                        "owners", row.getInt("owners"));
            }
        }
    }

    private Map<String, Object> adminInvocationStats(Connection connection) throws Exception {
        String sql =
                "SELECT COUNT(*) AS total,"
                        + "SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,"
                        + "SUM(CASE WHEN status IN ('FAILED','CANCELLED','REJECTED') THEN 1 ELSE 0 END) AS failed,"
                        + "SUM(CASE WHEN status='REJECTED' THEN 1 ELSE 0 END) AS rejected,"
                        + "SUM(CASE WHEN status='RUNNING' THEN 1 ELSE 0 END) AS running,"
                        + "SUM(CASE WHEN started_at>=? THEN 1 ELSE 0 END) AS last_24h,"
                        + "COALESCE(AVG(CASE WHEN duration_ms IS NOT NULL THEN duration_ms END),0) AS avg_duration_ms "
                        + "FROM "
                        + INVOCATIONS;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Instant.now().minus(24, ChronoUnit.HOURS).toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Map.of();
                return Map.of(
                        "total", row.getInt("total"),
                        "succeeded", row.getInt("succeeded"),
                        "failed", row.getInt("failed"),
                        "rejected", row.getInt("rejected"),
                        "running", row.getInt("running"),
                        "last_24h", row.getInt("last_24h"),
                        "avg_duration_ms", Math.round(row.getDouble("avg_duration_ms")));
            }
        }
    }

    private List<Map<String, Object>> adminTopAgents(Connection connection) throws Exception {
        String sql =
                "SELECT agent_id,COUNT(*) AS calls,"
                        + "SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,"
                        + "SUM(CASE WHEN status IN ('FAILED','CANCELLED','REJECTED') THEN 1 ELSE 0 END) AS failed,"
                        + "COALESCE(AVG(CASE WHEN duration_ms IS NOT NULL THEN duration_ms END),0) AS avg_duration_ms "
                        + "FROM "
                        + INVOCATIONS
                        + " GROUP BY agent_id ORDER BY calls DESC,agent_id LIMIT 10";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                rows.add(
                        Map.of(
                                "agent_id", row.getString("agent_id"),
                                "calls", row.getInt("calls"),
                                "succeeded", row.getInt("succeeded"),
                                "failed", row.getInt("failed"),
                                "avg_duration_ms", Math.round(row.getDouble("avg_duration_ms"))));
            }
        }
        return List.copyOf(rows);
    }

    private List<Map<String, Object>> adminRecentInvocations(Connection connection, int limit)
            throws Exception {
        String sql =
                "SELECT i.request_id,i.agent_id,i.call_mode,i.status,i.http_status,i.duration_ms,"
                        + "i.run_id,i.error_code,i.started_at,i.owner_user_id,i.request_path,i.source_ip,i.credential_fingerprint,"
                        + "CASE WHEN i.key_id='' THEN 'Unknown credential' ELSE COALESCE(k.name,'Legacy key') END AS key_name,"
                        + "COALESCE(u.email,'') AS owner_email "
                        + "FROM "
                        + INVOCATIONS
                        + " i LEFT JOIN "
                        + KEYS
                        + " k ON k.key_id=i.key_id LEFT JOIN platform_users u ON u.user_id=i.owner_user_id "
                        + "ORDER BY i.started_at DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("request_id", row.getString("request_id"));
                    item.put("agent_id", row.getString("agent_id"));
                    item.put("call_mode", row.getString("call_mode"));
                    item.put("status", row.getString("status"));
                    item.put("http_status", row.getInt("http_status"));
                    item.put("duration_ms", row.getLong("duration_ms"));
                    item.put("run_id", text(row.getString("run_id")));
                    item.put("error_code", text(row.getString("error_code")));
                    item.put("started_at", row.getString("started_at"));
                    item.put("owner_user_id", row.getString("owner_user_id"));
                    item.put("owner_email", text(row.getString("owner_email")));
                    item.put("key_name", row.getString("key_name"));
                    item.put("request_path", text(row.getString("request_path")));
                    item.put("source_ip", text(row.getString("source_ip")));
                    item.put(
                            "credential_fingerprint",
                            text(row.getString("credential_fingerprint")));
                    rows.add(item);
                }
            }
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
        row.put("request_path", text(result.getString("request_path")));
        row.put("source_ip", text(result.getString("source_ip")));
        row.put(
                "credential_fingerprint",
                text(result.getString("credential_fingerprint")));
        return row;
    }

    private void initialize() {
        storage.initializeSqliteSchema(
                "CREATE TABLE IF NOT EXISTS "
                        + KEYS
                        + " (key_id TEXT PRIMARY KEY,owner_user_id TEXT NOT NULL,org_id TEXT NOT NULL,name TEXT NOT NULL,key_prefix TEXT NOT NULL,secret_hash TEXT NOT NULL UNIQUE,status TEXT NOT NULL,created_at TEXT NOT NULL,last_used_at TEXT,expires_at TEXT,revoked_at TEXT,agent_ids_json TEXT NOT NULL DEFAULT '[]',capabilities_json TEXT NOT NULL DEFAULT '[\"chat\",\"stream\"]',rate_limit_per_minute INTEGER NOT NULL DEFAULT 60)");
        ensureColumn(KEYS, "agent_ids_json", "TEXT NOT NULL DEFAULT '[]'");
        ensureColumn(
                KEYS,
                "capabilities_json",
                "TEXT NOT NULL DEFAULT '[\"chat\",\"stream\"]'");
        ensureColumn(
                KEYS,
                "rate_limit_per_minute",
                "INTEGER NOT NULL DEFAULT " + DEFAULT_RATE_LIMIT_PER_MINUTE);
        ensureColumn(KEYS, "rotation_parent_key_id", "TEXT");
        ensureColumn(KEYS, "rotated_to_key_id", "TEXT");
        ensureColumn(KEYS, "rotation_grace_until", "TEXT");
        ensureColumn(KEYS, "revoked_by_user_id", "TEXT");
        storage.initializeSqliteSchema(
                "CREATE INDEX IF NOT EXISTS idx_platform_external_api_keys_owner_status ON "
                        + KEYS
                        + " (owner_user_id,status,created_at)");
        storage.initializeSqliteSchema(
                "CREATE TABLE IF NOT EXISTS "
                        + INVOCATIONS
                        + " (request_id TEXT PRIMARY KEY,key_id TEXT NOT NULL,owner_user_id TEXT NOT NULL,org_id TEXT NOT NULL,agent_id TEXT NOT NULL,call_mode TEXT NOT NULL,session_id TEXT NOT NULL,request_json TEXT NOT NULL,status TEXT NOT NULL,http_status INTEGER,duration_ms INTEGER,run_id TEXT,error_code TEXT,error_message TEXT,response_excerpt TEXT,started_at TEXT NOT NULL,finished_at TEXT,request_path TEXT NOT NULL DEFAULT '',source_ip TEXT NOT NULL DEFAULT '',credential_fingerprint TEXT NOT NULL DEFAULT '')");
        ensureColumn(INVOCATIONS, "request_path", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(INVOCATIONS, "source_ip", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(INVOCATIONS, "credential_fingerprint", "TEXT NOT NULL DEFAULT ''");
        storage.initializeSqliteSchema(
                "CREATE INDEX IF NOT EXISTS idx_platform_external_invocations_owner_started ON "
                        + INVOCATIONS
                        + " (owner_user_id,started_at DESC)");
        storage.initializeSqliteSchema(
                "CREATE INDEX IF NOT EXISTS idx_platform_external_invocations_key_started ON "
                        + INVOCATIONS
                        + " (key_id,started_at DESC)");
        storage.initializeSqliteSchema(
                "CREATE TABLE IF NOT EXISTS platform_external_api_rate_limits"
                        + " (key_id TEXT NOT NULL,window_start TEXT NOT NULL,request_count INTEGER NOT NULL,PRIMARY KEY(key_id,window_start))");
    }

    private void ensureColumn(String table, String column, String definition) {
        if (!storage.isSqliteEnabled()) return;
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement("PRAGMA table_info(" + table + ")");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return;
            }
        } catch (Exception error) {
            throw failure("检查外部 API 数据库字段失败", error);
        }
        storage.initializeSqliteSchema(
                "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private static Map<String, Object> keyContract(
            String keyId,
            String name,
            String prefix,
            String status,
            String createdAt,
            String lastUsedAt,
            Instant expiresAt,
            Set<String> agentIds,
            Set<String> capabilities,
            int rateLimitPerMinute,
            String rotationParentKeyId,
            String rotatedToKeyId,
            String rotationGraceUntil,
            String revokedByUserId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key_id", keyId);
        row.put("name", name);
        row.put("key_prefix", prefix);
        row.put("masked_key", prefix + "••••••••");
        row.put("status", status);
        row.put("created_at", createdAt);
        row.put("last_used_at", text(lastUsedAt));
        row.put("expires_at", expiresAt == null ? "" : expiresAt.toString());
        row.put("allowed_agent_ids", List.copyOf(agentIds == null ? Set.of() : agentIds));
        row.put(
                "capabilities",
                List.copyOf(capabilities == null ? SUPPORTED_CAPABILITIES : capabilities));
        row.put("rate_limit_per_minute", rateLimitPerMinute);
        row.put("rotation_parent_key_id", text(rotationParentKeyId));
        row.put("rotated_to_key_id", text(rotatedToKeyId));
        row.put("rotation_grace_until", text(rotationGraceUntil));
        row.put("revoked_by_user_id", text(revokedByUserId));
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

    private static Set<String> parseStringSet(String value) {
        try {
            List<String> items =
                    value == null || value.isBlank()
                            ? List.of()
                            : JSON.readValue(value, new TypeReference<List<String>>() {});
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (String item : items) {
                String normalized = text(item);
                if (!normalized.isBlank()) result.add(normalized);
            }
            return Set.copyOf(result);
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private static Set<String> normalizeAgentIds(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = text(value);
                if (normalized.isBlank()) continue;
                if (normalized.length() > 160
                        || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                    throw new PlatformAuthService.AuthException(400, "Agent ID 格式无效: " + normalized);
                }
                result.add(normalized);
                if (result.size() > 100) {
                    throw new PlatformAuthService.AuthException(400, "每个 API Key 最多绑定 100 个 Agent");
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> normalizeCapabilities(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> requested = values == null ? List.of() : values;
        for (String value : requested) {
            String normalized = text(value).toLowerCase();
            if (!SUPPORTED_CAPABILITIES.contains(normalized)) {
                throw new PlatformAuthService.AuthException(400, "不支持的 API Key 权限: " + normalized);
            }
            result.add(normalized);
        }
        if (result.isEmpty()) {
            throw new PlatformAuthService.AuthException(400, "API Key 至少需要 chat 或 stream 权限");
        }
        return Set.copyOf(result);
    }

    private static int normalizeRateLimit(Integer value) {
        int normalized = value == null ? DEFAULT_RATE_LIMIT_PER_MINUTE : value;
        if (normalized < 1 || normalized > MAX_RATE_LIMIT_PER_MINUTE) {
            throw new PlatformAuthService.AuthException(
                    400, "每分钟调用上限必须在 1 到 " + MAX_RATE_LIMIT_PER_MINUTE + " 之间");
        }
        return normalized;
    }

    private static int normalizeRotationGrace(Integer value) {
        int normalized = value == null ? DEFAULT_ROTATION_GRACE_MINUTES : value;
        if (normalized < 0 || normalized > MAX_ROTATION_GRACE_MINUTES) {
            throw new PlatformAuthService.AuthException(
                    400, "Key 轮换宽限时间必须在 0 到 " + MAX_ROTATION_GRACE_MINUTES + " 分钟之间");
        }
        return normalized;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("序列化外部 API Key 策略失败", error);
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
        return Set.of("ACTIVE", "SUSPENDED").contains(status) && expired(expiresAt)
                ? "EXPIRED"
                : text(status);
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

    private static void requirePlatformAdmin(PlatformAuthService.Principal principal) {
        requirePrincipal(principal);
        if (!"PLATFORM_ADMIN".equals(principal.role())) {
            throw new PlatformAuthService.AuthException(403, "需要平台管理员权限");
        }
    }

    private static IllegalStateException failure(String message, Exception error) {
        return new IllegalStateException(message, error);
    }
}
