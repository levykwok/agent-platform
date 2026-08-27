/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.PlatformStorageLayer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Persistent account application, password setup and session service. */
@Component
public class PlatformAuthService {

    private static final String USERS = "platform_users";
    private static final String ORGS = "platform_organizations";
    private static final String MEMBERSHIPS = "platform_memberships";
    private static final String APPLICATIONS = "platform_account_applications";
    private static final String TOKENS = "platform_password_setup_tokens";
    private static final String SESSIONS = "platform_sessions";
    private static final String OUTBOX = "platform_email_outbox";
    private static final String RATE_LIMITS = "platform_auth_rate_limits";
    // platform_audit_events is already used by the legacy runtime event stream.
    // Keep account lifecycle audit rows in a separate table so copied databases
    // remain backward-compatible with both schemas.
    private static final String AUDIT = "platform_account_audit_events";
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int SESSION_DAYS = 7;
    private static final int SETUP_TOKEN_HOURS = 24;

    private final PlatformStorageLayer storage;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;
    private final String baseUrl;
    private final boolean mailEnabled;
    private final boolean secureCookie;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicBoolean initialized = new AtomicBoolean();

    @Value("${agent.platform.auth.login.max-failed-attempts:5}")
    private int loginMaxFailedAttempts = 5;

    @Value("${agent.platform.auth.login.lock-minutes:15}")
    private int loginLockMinutes = 15;

    @Value("${agent.platform.auth.login.ip-attempts-per-window:30}")
    private int loginIpAttemptsPerWindow = 30;

    @Value("${agent.platform.auth.login.ip-window-minutes:15}")
    private int loginIpWindowMinutes = 15;

    @Value("${agent.platform.auth.apply.ip-attempts-per-hour:10}")
    private int applyIpAttemptsPerHour = 10;

    @Value("${agent.platform.auth.apply.email-attempts-per-day:3}")
    private int applyEmailAttemptsPerDay = 3;

    @Value("${agent.platform.auth.mail.max-attempts:5}")
    private int mailMaxAttempts = 5;

    @Value("${agent.platform.auth.mail.from:}")
    private String mailFrom = "";

    public PlatformAuthService(
            PlatformStorageLayer storage,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${agent.platform.auth.bootstrap-admin-email:admin@platform.local}")
                    String bootstrapAdminEmail,
            @Value("${agent.platform.auth.bootstrap-admin-password:}")
                    String bootstrapAdminPassword,
            @Value("${agent.platform.auth.base-url:http://localhost:8080}") String baseUrl,
            @Value("${agent.platform.auth.mail.enabled:false}") boolean mailEnabled,
            @Value("${agent.platform.auth.secure-cookie:false}") boolean secureCookie) {
        this.storage = storage;
        this.mailSenderProvider = mailSenderProvider;
        this.bootstrapAdminEmail = normalizeEmail(bootstrapAdminEmail);
        this.bootstrapAdminPassword = bootstrapAdminPassword == null ? "" : bootstrapAdminPassword;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.mailEnabled = mailEnabled;
        this.secureCookie = secureCookie;
        initialize();
    }

    public record Principal(
            String userId,
            String email,
            String displayName,
            String orgId,
            String role) {}

    public static class AuthException extends RuntimeException {
        private final int status;

        public AuthException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public Map<String, Object> apply(Map<String, Object> payload) {
        return apply(payload, "unknown");
    }

    public Map<String, Object> apply(Map<String, Object> payload, String clientAddress) {
        String email = normalizeEmail(string(payload, "email", ""));
        String displayName = string(payload, "display_name", string(payload, "name", ""));
        String project = string(payload, "project", "");
        String reason = string(payload, "reason", "");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new AuthException(400, "请输入有效的邮箱地址");
        }
        if (displayName.isBlank()) {
            throw new AuthException(400, "姓名不能为空");
        }
        consumeRateLimit(
                "ACCOUNT_APPLY_IP",
                safeSubject(clientAddress),
                60L * 60L * 1000L,
                applyIpAttemptsPerHour,
                60L * 60L * 1000L,
                "账号申请过于频繁，请稍后再试");
        consumeRateLimit(
                "ACCOUNT_APPLY_EMAIL",
                email,
                24L * 60L * 60L * 1000L,
                applyEmailAttemptsPerDay,
                24L * 60L * 60L * 1000L,
                "该邮箱的申请过于频繁，请稍后再试");
        String now = now();
        String applicationId = "application_" + randomToken(12);
        try (Connection connection = storage.connection()) {
            initializeOnConnection(connection);
            if (exists(
                    connection,
                    "SELECT 1 FROM " + USERS + " WHERE email = ? AND status <> 'DELETED'",
                    email)) {
                // Do not disclose whether the address belongs to an active user.
                return map("ok", true, "status", "RECEIVED");
            }
            if (exists(
                    connection,
                    "SELECT 1 FROM " + APPLICATIONS + " WHERE email = ? AND status = 'PENDING'",
                    email)) {
                return map("ok", true, "status", "RECEIVED");
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO "
                                    + APPLICATIONS
                                    + " (application_id,email,display_name,project,reason,status,created_at)"
                                    + " VALUES (?,?,?,?,?,'PENDING',?)")) {
                statement.setString(1, applicationId);
                statement.setString(2, email);
                statement.setString(3, displayName.trim());
                statement.setString(4, project);
                statement.setString(5, reason);
                statement.setString(6, now);
                statement.executeUpdate();
            }
            audit(connection, "anonymous", "ACCOUNT_APPLICATION_CREATED", "application", applicationId, email);
            return map("ok", true, "application_id", applicationId, "status", "PENDING");
        } catch (Exception error) {
            throw failure("提交账号申请失败", error);
        }
    }

    public Map<String, Object> login(String emailValue, String password) {
        return login(emailValue, password, "unknown");
    }

    public Map<String, Object> login(
            String emailValue, String password, String clientAddress) {
        String email = normalizeEmail(emailValue);
        if (email.isBlank() || password == null || password.isBlank()) {
            throw new AuthException(400, "邮箱和密码不能为空");
        }
        consumeRateLimit(
                "ACCOUNT_LOGIN_IP",
                safeSubject(clientAddress),
                Math.max(1, loginIpWindowMinutes) * 60L * 1000L,
                loginIpAttemptsPerWindow,
                Math.max(1, loginIpWindowMinutes) * 60L * 1000L,
                "登录请求过于频繁，请稍后再试");
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT user_id,email,display_name,password_hash,status,must_change_password,failed_attempts,locked_until"
                                        + " FROM "
                                        + USERS
                                        + " WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AuthException(401, "邮箱或密码错误");
                }
                String status = result.getString("status");
                if (!"ACTIVE".equals(status)) {
                    throw new AuthException(403, "账号当前不可登录");
                }
                Instant lockedUntil = parseInstant(result.getString("locked_until"));
                if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
                    throw new AuthException(429, "登录失败次数过多，请稍后再试");
                }
                String passwordHash = result.getString("password_hash");
                if (passwordHash == null || passwordHash.isBlank()) {
                    throw new AuthException(403, "账号尚未完成首次密码设置，请使用邮件中的链接");
                }
                if (!verifyPassword(password, passwordHash)) {
                    recordLoginFailure(connection, result.getString("user_id"));
                    throw new AuthException(401, "邮箱或密码错误");
                }
                String userId = result.getString("user_id");
                String sessionToken = createSession(connection, userId);
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + USERS
                                        + " SET last_login_at = ?, failed_attempts = 0, locked_until = NULL WHERE user_id = ?")) {
                    update.setString(1, now());
                    update.setString(2, userId);
                    update.executeUpdate();
                }
                return map(
                        "ok",
                        true,
                        "session_token",
                        sessionToken,
                        "must_change_password",
                        result.getInt("must_change_password") == 1,
                        "user",
                        user(connection, userId));
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("登录失败", error);
        }
    }

    public Map<String, Object> setupPassword(String token, String password, String displayName) {
        if (token == null || token.isBlank()) {
            throw new AuthException(400, "密码设置链接无效");
        }
        validatePassword(password);
        String tokenHash = sha256(token);
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                String userId;
                String usedAt = now();
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT user_id FROM "
                                        + TOKENS
                                        + " WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?")) {
                    statement.setString(1, tokenHash);
                    statement.setString(2, usedAt);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new AuthException(400, "密码设置链接无效或已过期");
                        }
                        userId = result.getString("user_id");
                    }
                }
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + TOKENS
                                        + " SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?")) {
                    update.setString(1, usedAt);
                    update.setString(2, tokenHash);
                    update.setString(3, usedAt);
                    if (update.executeUpdate() != 1) {
                        throw new AuthException(400, "密码设置链接无效或已过期");
                    }
                }
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + USERS
                                        + " SET password_hash = ?, must_change_password = 0, email_verified_at = ?, display_name = COALESCE(NULLIF(?,''),display_name), failed_attempts = 0, locked_until = NULL"
                                        + " WHERE user_id = ? AND status='ACTIVE'")) {
                    update.setString(1, hashPassword(password));
                    update.setString(2, usedAt);
                    update.setString(3, displayName == null ? "" : displayName.trim());
                    update.setString(4, userId);
                    if (update.executeUpdate() != 1) {
                        throw new AuthException(403, "账号当前不可设置密码");
                    }
                }
                String sessionToken = createSession(connection, userId);
                audit(connection, userId, "PASSWORD_SETUP_COMPLETED", "user", userId, "");
                Map<String, Object> response =
                        map(
                                "ok",
                                true,
                                "session_token",
                                sessionToken,
                                "user",
                                user(connection, userId));
                connection.commit();
                return response;
            } catch (Exception error) {
                rollback(connection);
                throw error;
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("设置密码失败", error);
        }
    }

    public Principal current(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return null;
        }
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT u.user_id,u.email,u.display_name,m.org_id,m.role"
                                        + " FROM "
                                        + SESSIONS
                                        + " s JOIN "
                                        + USERS
                                        + " u ON u.user_id=s.user_id JOIN "
                                        + MEMBERSHIPS
                                        + " m ON m.user_id=u.user_id JOIN "
                                        + ORGS
                                        + " o ON o.org_id=m.org_id"
                                        + " WHERE s.token_hash = ? AND s.expires_at > ? AND s.revoked_at IS NULL"
                                        + " AND u.status='ACTIVE' AND m.status='ACTIVE' AND o.status='ACTIVE' ORDER BY CASE WHEN m.role='PLATFORM_ADMIN' THEN 0 ELSE 1 END LIMIT 1")) {
            statement.setString(1, sha256(sessionToken));
            statement.setString(2, now());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new Principal(
                                result.getString("user_id"),
                                result.getString("email"),
                                result.getString("display_name"),
                                result.getString("org_id"),
                                result.getString("role"))
                        : null;
            }
        } catch (Exception error) {
            throw failure("读取登录状态失败", error);
        }
    }

    public void logout(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return;
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE " + SESSIONS + " SET revoked_at = ? WHERE token_hash = ?")) {
            statement.setString(1, now());
            statement.setString(2, sha256(sessionToken));
            statement.executeUpdate();
        } catch (Exception error) {
            throw failure("退出登录失败", error);
        }
    }

    public List<Map<String, Object>> applications(Principal principal) {
        requireAdmin(principal);
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT application_id,email,display_name,project,reason,status,reviewer_id,review_reason,reviewed_at,created_at"
                                        + " FROM "
                                        + APPLICATIONS
                                        + " ORDER BY CASE status WHEN 'PENDING' THEN 0 ELSE 1 END, created_at DESC")) {
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(
                            map(
                                    "application_id", result.getString("application_id"),
                                    "email", result.getString("email"),
                                    "display_name", result.getString("display_name"),
                                    "project", result.getString("project"),
                                    "reason", result.getString("reason"),
                                    "status", result.getString("status"),
                                    "reviewer_id", result.getString("reviewer_id"),
                                    "review_reason", result.getString("review_reason"),
                                    "reviewed_at", result.getString("reviewed_at"),
                                    "created_at", result.getString("created_at")));
                }
                return rows;
            }
        } catch (Exception error) {
            throw failure("读取账号申请失败", error);
        }
    }

    public Map<String, Object> applicationPage(
            Principal principal, String query, String status, int limit, int offset) {
        String needle = query == null ? "" : query.trim().toLowerCase();
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        List<Map<String, Object>> filtered =
                applications(principal).stream()
                        .filter(
                                row ->
                                        normalizedStatus.isBlank()
                                                || normalizedStatus.equals(
                                                        String.valueOf(row.get("status"))))
                        .filter(
                                row ->
                                        needle.isBlank()
                                                || List.of(
                                                                row.get("email"),
                                                                row.get("display_name"),
                                                                row.get("project"),
                                                                row.get("reason"))
                                                        .stream()
                                                        .anyMatch(
                                                                value ->
                                                                        value != null
                                                                                && String.valueOf(value)
                                                                                        .toLowerCase()
                                                                                        .contains(needle)))
                        .toList();
        int from = Math.min(safeOffset, filtered.size());
        int to = Math.min(filtered.size(), from + safeLimit);
        List<Map<String, Object>> rows = filtered.subList(from, to);
        return map(
                "items", rows,
                "applications", rows,
                "total", filtered.size(),
                "limit", safeLimit,
                "offset", safeOffset);
    }

    public Map<String, Object> approve(
            Principal principal, String applicationId, Map<String, Object> payload) {
        requireAdmin(principal);
        String role = string(payload, "role", "BUILDER").toUpperCase();
        if (!List.of("ORG_ADMIN", "BUILDER", "TESTER", "VIEWER").contains(role)) {
            throw new AuthException(400, "不支持的用户角色");
        }
        String emailId = "";
        Map<String, Object> response;
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                String reviewedAt = now();
                try (PreparedStatement reserve =
                        connection.prepareStatement(
                                "UPDATE "
                                        + APPLICATIONS
                                        + " SET status='APPROVING',reviewer_id=?,reviewed_at=?"
                                        + " WHERE application_id=? AND status='PENDING'")) {
                    reserve.setString(1, principal.userId());
                    reserve.setString(2, reviewedAt);
                    reserve.setString(3, applicationId);
                    if (reserve.executeUpdate() != 1) {
                        throw new AuthException(404, "账号申请不存在或已处理");
                    }
                }
                Map<String, Object> application = application(connection, applicationId);
                String email = String.valueOf(application.get("email"));
                if (exists(connection, "SELECT 1 FROM " + USERS + " WHERE email = ?", email)) {
                    throw new AuthException(409, "该邮箱已经存在账号");
                }
                String userId = "user_" + randomToken(12);
                String requestedOrgId = string(payload, "organization_id", "");
                String orgId;
                String orgName =
                        string(
                                payload,
                                "organization",
                                String.valueOf(application.get("project")));
                if (!requestedOrgId.isBlank()) {
                    requireActiveOrganization(connection, requestedOrgId);
                    orgId = requestedOrgId;
                } else {
                    orgId = "org_" + randomToken(10);
                    if (orgName.isBlank()) {
                        orgName = String.valueOf(application.get("display_name")) + "的空间";
                    }
                    insertOrganization(connection, orgId, orgName, reviewedAt);
                }
                try (PreparedStatement user =
                        connection.prepareStatement(
                                "INSERT INTO "
                                        + USERS
                                        + " (user_id,email,display_name,password_hash,status,must_change_password,failed_attempts,created_at)"
                                        + " VALUES (?,?,?,NULL,'ACTIVE',1,0,?)")) {
                    user.setString(1, userId);
                    user.setString(2, email);
                    user.setString(3, String.valueOf(application.get("display_name")));
                    user.setString(4, reviewedAt);
                    user.executeUpdate();
                }
                insertMembership(connection, orgId, userId, role, reviewedAt);
                SetupDelivery delivery = issueSetupToken(connection, userId, email, reviewedAt);
                emailId = delivery.emailId();
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + APPLICATIONS
                                        + " SET status='APPROVED',review_reason=?"
                                        + " WHERE application_id=? AND status='APPROVING'")) {
                    update.setString(1, string(payload, "review_reason", ""));
                    update.setString(2, applicationId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("账号申请状态提交失败");
                    }
                }
                audit(
                        connection,
                        principal.userId(),
                        "ACCOUNT_APPLICATION_APPROVED",
                        "application",
                        applicationId,
                        userId);
                response =
                        map(
                                "ok", true,
                                "application_id", applicationId,
                                "user_id", userId,
                                "organization_id", orgId,
                                "email_id", emailId,
                                "email_status", mailEnabled ? "PENDING" : "MANUAL_SETUP_REQUIRED");
                if (!mailEnabled) response.put("setup_url", delivery.setupUrl());
                connection.commit();
            } catch (Exception error) {
                rollback(connection);
                throw error;
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("审核账号申请失败", error);
        }
        dispatchEmail(emailId);
        return response;
    }

    public Map<String, Object> reject(Principal principal, String applicationId, Map<String, Object> payload) {
        requireAdmin(principal);
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "UPDATE "
                                    + APPLICATIONS
                                    + " SET status='REJECTED',reviewer_id=?,review_reason=?,reviewed_at=? WHERE application_id=? AND status='PENDING'")) {
                statement.setString(1, principal.userId());
                statement.setString(2, string(payload, "review_reason", ""));
                statement.setString(3, now());
                statement.setString(4, applicationId);
                if (statement.executeUpdate() == 0) {
                    throw new AuthException(404, "账号申请不存在或已处理");
                }
                audit(
                        connection,
                        principal.userId(),
                        "ACCOUNT_APPLICATION_REJECTED",
                        "application",
                        applicationId,
                        "");
                connection.commit();
                return map("ok", true, "application_id", applicationId, "status", "REJECTED");
            } catch (Exception error) {
                rollback(connection);
                throw error;
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("拒绝账号申请失败", error);
        }
    }

    public Map<String, Object> users(
            Principal principal, String query, String status, int limit, int offset) {
        requireAdmin(principal);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        String needle = query == null ? "" : query.trim().toLowerCase();
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!normalizedStatus.isBlank()
                && !List.of("ACTIVE", "SUSPENDED", "DELETED").contains(normalizedStatus)) {
            throw new AuthException(400, "不支持的账号状态");
        }
        String filter =
                " WHERE (?='' OR lower(u.email) LIKE ? OR lower(u.display_name) LIKE ?)"
                        + " AND (?='' OR u.status=?)";
        String membershipJoin =
                " LEFT JOIN "
                        + MEMBERSHIPS
                        + " m ON m.rowid=(SELECT m2.rowid FROM "
                        + MEMBERSHIPS
                        + " m2 WHERE m2.user_id=u.user_id AND m2.status='ACTIVE'"
                        + " ORDER BY CASE WHEN m2.role='PLATFORM_ADMIN' THEN 0 ELSE 1 END,m2.created_at LIMIT 1)"
                        + " LEFT JOIN "
                        + ORGS
                        + " o ON o.org_id=m.org_id";
        try (Connection connection = storage.connection()) {
            long total;
            try (PreparedStatement count =
                    connection.prepareStatement("SELECT COUNT(*) FROM " + USERS + " u" + filter)) {
                bindUserFilter(count, needle, normalizedStatus);
                try (ResultSet result = count.executeQuery()) {
                    total = result.next() ? result.getLong(1) : 0L;
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            String sql =
                    "SELECT u.user_id,u.email,u.display_name,u.status,u.must_change_password,u.failed_attempts,u.locked_until,u.last_login_at,u.created_at,"
                            + "COALESCE(m.org_id,'' ) org_id,COALESCE(m.role,'' ) role,COALESCE(o.name,'' ) organization_name,"
                            + "(SELECT COUNT(*) FROM "
                            + SESSIONS
                            + " s WHERE s.user_id=u.user_id AND s.revoked_at IS NULL AND s.expires_at>?) active_sessions"
                            + " FROM "
                            + USERS
                            + " u"
                            + membershipJoin
                            + filter
                            + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, now());
                index = bindUserFilter(statement, index, needle, normalizedStatus);
                statement.setInt(index++, safeLimit);
                statement.setInt(index, safeOffset);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String userId = result.getString("user_id");
                        rows.add(
                                map(
                                        "user_id", userId,
                                        "email", result.getString("email"),
                                        "display_name", result.getString("display_name"),
                                        "status", result.getString("status"),
                                        "must_change_password", result.getInt("must_change_password") == 1,
                                        "failed_attempts", result.getInt("failed_attempts"),
                                        "locked_until", result.getString("locked_until"),
                                        "last_login_at", result.getString("last_login_at"),
                                        "created_at", result.getString("created_at"),
                                        "org_id", result.getString("org_id"),
                                        "organization_name", result.getString("organization_name"),
                                        "role", result.getString("role"),
                                        "active_sessions", result.getInt("active_sessions"),
                                        "asset_usage", assetUsage(connection, userId)));
                    }
                }
            }
            return map(
                    "items", rows,
                    "users", rows,
                    "total", total,
                    "limit", safeLimit,
                    "offset", safeOffset);
        } catch (Exception error) {
            throw failure("读取用户列表失败", error);
        }
    }

    public List<Map<String, Object>> organizations(Principal principal) {
        requireAdmin(principal);
        String sql =
                "SELECT o.org_id,o.name,o.status,o.created_at,COUNT(m.user_id) member_count"
                        + " FROM "
                        + ORGS
                        + " o LEFT JOIN "
                        + MEMBERSHIPS
                        + " m ON m.org_id=o.org_id AND m.status='ACTIVE'"
                        + " GROUP BY o.org_id,o.name,o.status,o.created_at ORDER BY CASE WHEN o.org_id='platform' THEN 0 ELSE 1 END,o.name";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(
                        map(
                                "org_id", result.getString("org_id"),
                                "name", result.getString("name"),
                                "status", result.getString("status"),
                                "member_count", result.getLong("member_count"),
                                "created_at", result.getString("created_at")));
            }
            return rows;
        } catch (Exception error) {
            throw failure("读取组织列表失败", error);
        }
    }

    public Map<String, Object> createOrganization(
            Principal principal, Map<String, Object> payload) {
        requireAdmin(principal);
        String name = string(payload, "name", string(payload, "organization", "")).trim();
        if (name.isBlank()) throw new AuthException(400, "组织名称不能为空");
        if (name.length() > 120) throw new AuthException(400, "组织名称不能超过 120 个字符");
        String orgId = "org_" + randomToken(10);
        try (Connection connection = storage.connection()) {
            insertOrganization(connection, orgId, name, now());
            audit(
                    connection,
                    principal.userId(),
                    "ACCOUNT_ORGANIZATION_CREATED",
                    "organization",
                    orgId,
                    name);
            return map("ok", true, "org_id", orgId, "name", name, "status", "ACTIVE");
        } catch (Exception error) {
            throw failure("创建组织失败", error);
        }
    }

    public Map<String, Object> updateOrganization(
            Principal principal, String orgId, Map<String, Object> payload) {
        requireAdmin(principal);
        String name = string(payload, "name", "").trim();
        String status = string(payload, "status", "").trim().toUpperCase();
        if (name.isBlank() && status.isBlank()) {
            throw new AuthException(400, "没有需要更新的组织字段");
        }
        if (name.length() > 120) throw new AuthException(400, "组织名称不能超过 120 个字符");
        if (!status.isBlank() && !List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw new AuthException(400, "不支持的组织状态");
        }
        if ("platform".equals(orgId) && "SUSPENDED".equals(status)) {
            throw new AuthException(409, "平台公共空间不能停用");
        }
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                if (!exists(connection, "SELECT 1 FROM " + ORGS + " WHERE org_id=?", orgId)) {
                    throw new AuthException(404, "组织不存在");
                }
                if ("SUSPENDED".equals(status)) {
                    try (PreparedStatement members =
                            connection.prepareStatement(
                                    "SELECT COUNT(*) FROM "
                                            + MEMBERSHIPS
                                            + " WHERE org_id=? AND status='ACTIVE'")) {
                        members.setString(1, orgId);
                        try (ResultSet result = members.executeQuery()) {
                            if (result.next() && result.getLong(1) > 0) {
                                throw new AuthException(409, "请先迁移该组织的全部成员，再停用组织");
                            }
                        }
                    }
                }
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + ORGS
                                        + " SET name=CASE WHEN ?='' THEN name ELSE ? END,status=CASE WHEN ?='' THEN status ELSE ? END WHERE org_id=?")) {
                    update.setString(1, name);
                    update.setString(2, name);
                    update.setString(3, status);
                    update.setString(4, status);
                    update.setString(5, orgId);
                    update.executeUpdate();
                }
                audit(
                        connection,
                        principal.userId(),
                        "ACCOUNT_ORGANIZATION_UPDATED",
                        "organization",
                        orgId,
                        "name=" + name + ",status=" + status);
                connection.commit();
                return map("ok", true, "org_id", orgId, "name", name, "status", status);
            } catch (Exception error) {
                rollback(connection);
                throw error;
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("更新组织失败", error);
        }
    }

    public Map<String, Object> updateUser(
            Principal principal, String userId, Map<String, Object> payload) {
        requireAdmin(principal);
        String requestedStatus = string(payload, "status", "").toUpperCase();
        String displayName = string(payload, "display_name", "");
        if (requestedStatus.isBlank() && displayName.isBlank()) {
            throw new AuthException(400, "没有需要更新的账号字段");
        }
        if (!requestedStatus.isBlank()
                && !List.of("ACTIVE", "SUSPENDED").contains(requestedStatus)) {
            throw new AuthException(400, "不支持的账号状态");
        }
        if (principal.userId().equals(userId) && "SUSPENDED".equals(requestedStatus)) {
            throw new AuthException(409, "不能停用当前登录的管理员账号");
        }
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                requireUser(connection, userId);
                if ("SUSPENDED".equals(requestedStatus)
                        && isPlatformAdmin(connection, userId)) {
                    requireAnotherPlatformAdmin(connection, userId);
                }
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + USERS
                                        + " SET display_name=CASE WHEN ?='' THEN display_name ELSE ? END,status=CASE WHEN ?='' THEN status ELSE ? END,failed_attempts=CASE WHEN ?='ACTIVE' THEN 0 ELSE failed_attempts END,locked_until=CASE WHEN ?='ACTIVE' THEN NULL ELSE locked_until END WHERE user_id=?")) {
                    update.setString(1, displayName);
                    update.setString(2, displayName);
                    update.setString(3, requestedStatus);
                    update.setString(4, requestedStatus);
                    update.setString(5, requestedStatus);
                    update.setString(6, requestedStatus);
                    update.setString(7, userId);
                    update.executeUpdate();
                }
                if ("SUSPENDED".equals(requestedStatus)) revokeSessions(connection, userId);
                audit(
                        connection,
                        principal.userId(),
                        "ACCOUNT_UPDATED",
                        "user",
                        userId,
                        "status=" + requestedStatus);
                connection.commit();
                return map("ok", true, "user_id", userId, "status", requestedStatus);
            } catch (Exception error) {
                rollback(connection);
                throw error;
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("更新账号失败", error);
        }
    }

    public Map<String, Object> setMembership(
            Principal principal, String userId, Map<String, Object> payload) {
        requireAdmin(principal);
        if (principal.userId().equals(userId)) {
            throw new AuthException(409, "不能修改当前登录管理员自己的角色或组织");
        }
        String role = string(payload, "role", "BUILDER").toUpperCase();
        if (!List.of("PLATFORM_ADMIN", "ORG_ADMIN", "BUILDER", "TESTER", "VIEWER")
                .contains(role)) {
            throw new AuthException(400, "不支持的用户角色");
        }
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                requireUser(connection, userId);
                boolean wasPlatformAdmin = isPlatformAdmin(connection, userId);
                if (wasPlatformAdmin && !"PLATFORM_ADMIN".equals(role)) {
                    requireAnotherPlatformAdmin(connection, userId);
                }
                String orgId;
                if ("PLATFORM_ADMIN".equals(role)) {
                    orgId = "platform";
                } else {
                    orgId = string(payload, "org_id", "");
                    if (orgId.isBlank()) {
                        String name = string(payload, "organization", "");
                        if (name.isBlank()) throw new AuthException(400, "请选择或填写组织");
                        orgId = "org_" + randomToken(10);
                        insertOrganization(connection, orgId, name, now());
                    } else {
                        requireActiveOrganization(connection, orgId);
                    }
                }
                try (PreparedStatement delete =
                        connection.prepareStatement("DELETE FROM " + MEMBERSHIPS + " WHERE user_id=?")) {
                    delete.setString(1, userId);
                    delete.executeUpdate();
                }
                insertMembership(connection, orgId, userId, role, now());
                audit(
                        connection,
                        principal.userId(),
                        "ACCOUNT_MEMBERSHIP_UPDATED",
                        "user",
                        userId,
                        orgId + ":" + role);
                connection.commit();
                return map("ok", true, "user_id", userId, "org_id", orgId, "role", role);
            } catch (Exception error) {
                rollback(connection);
                throw error;
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("更新用户组织和角色失败", error);
        }
    }

    public Map<String, Object> revokeUserSessions(Principal principal, String userId) {
        requireAdmin(principal);
        try (Connection connection = storage.connection()) {
            requireUser(connection, userId);
            int revoked = revokeSessions(connection, userId);
            audit(
                    connection,
                    principal.userId(),
                    "ACCOUNT_SESSIONS_REVOKED",
                    "user",
                    userId,
                    String.valueOf(revoked));
            return map("ok", true, "user_id", userId, "revoked_sessions", revoked);
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("撤销用户会话失败", error);
        }
    }

    public Map<String, Object> userSessions(Principal principal, String userId) {
        requireAdmin(principal);
        try (Connection connection = storage.connection()) {
            requireUser(connection, userId);
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT session_id,expires_at,revoked_at,created_at,last_seen_at FROM "
                                    + SESSIONS
                                    + " WHERE user_id=? ORDER BY created_at DESC LIMIT 200")) {
                statement.setString(1, userId);
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    Instant current = Instant.now();
                    while (result.next()) {
                        String revokedAt = result.getString("revoked_at");
                        Instant expiresAt = parseInstant(result.getString("expires_at"));
                        String status =
                                revokedAt != null
                                        ? "REVOKED"
                                        : expiresAt == null || !expiresAt.isAfter(current)
                                                ? "EXPIRED"
                                                : "ACTIVE";
                        rows.add(
                                map(
                                        "session_id", result.getString("session_id"),
                                        "status", status,
                                        "expires_at", result.getString("expires_at"),
                                        "revoked_at", revokedAt,
                                        "created_at", result.getString("created_at"),
                                        "last_seen_at", result.getString("last_seen_at")));
                    }
                }
                return map("items", rows, "sessions", rows, "total", rows.size());
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("读取用户会话失败", error);
        }
    }

    public Map<String, Object> revokeUserSession(
            Principal principal, String userId, String sessionId) {
        requireAdmin(principal);
        try (Connection connection = storage.connection()) {
            requireUser(connection, userId);
            try (PreparedStatement update =
                    connection.prepareStatement(
                            "UPDATE "
                                    + SESSIONS
                                    + " SET revoked_at=? WHERE session_id=? AND user_id=? AND revoked_at IS NULL")) {
                update.setString(1, now());
                update.setString(2, sessionId);
                update.setString(3, userId);
                if (update.executeUpdate() != 1) {
                    throw new AuthException(404, "会话不存在或已经失效");
                }
            }
            audit(
                    connection,
                    principal.userId(),
                    "ACCOUNT_SESSION_REVOKED",
                    "session",
                    sessionId,
                    userId);
            return map("ok", true, "user_id", userId, "session_id", sessionId);
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("撤销用户会话失败", error);
        }
    }

    public Map<String, Object> resetUserPassword(Principal principal, String userId) {
        requireAdmin(principal);
        String emailId = "";
        Map<String, Object> response;
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> target = requireUser(connection, userId);
                if (!"ACTIVE".equals(target.get("status"))) {
                    throw new AuthException(409, "只有启用账号可以重置密码");
                }
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + USERS
                                        + " SET password_hash=NULL,must_change_password=1,failed_attempts=0,locked_until=NULL WHERE user_id=?")) {
                    update.setString(1, userId);
                    update.executeUpdate();
                }
                revokeSessions(connection, userId);
                SetupDelivery delivery =
                        issueSetupToken(
                                connection,
                                userId,
                                String.valueOf(target.get("email")),
                                now());
                emailId = delivery.emailId();
                audit(
                        connection,
                        principal.userId(),
                        "ACCOUNT_PASSWORD_RESET_REQUESTED",
                        "user",
                        userId,
                        emailId);
                response =
                        map(
                                "ok", true,
                                "user_id", userId,
                                "email_id", emailId,
                                "email_status", mailEnabled ? "PENDING" : "MANUAL_SETUP_REQUIRED");
                if (!mailEnabled) response.put("setup_url", delivery.setupUrl());
                connection.commit();
            } catch (Exception error) {
                rollback(connection);
                throw error;
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("重置用户密码失败", error);
        }
        dispatchEmail(emailId);
        return response;
    }

    public Map<String, Object> emailOutbox(
            Principal principal, String status, int limit, int offset) {
        requireAdmin(principal);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        String normalized = status == null ? "" : status.trim().toUpperCase();
        String sql =
                "SELECT email_id,kind,recipient,user_id,status,attempts,next_attempt_at,sent_at,last_error,created_at"
                        + " FROM "
                        + OUTBOX
                        + " WHERE (?='' OR status=?) ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = storage.connection()) {
            long total;
            try (PreparedStatement count =
                    connection.prepareStatement(
                            "SELECT COUNT(*) FROM "
                                    + OUTBOX
                                    + " WHERE (?='' OR status=?)")) {
                count.setString(1, normalized);
                count.setString(2, normalized);
                try (ResultSet result = count.executeQuery()) {
                    total = result.next() ? result.getLong(1) : 0L;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, normalized);
                statement.setString(2, normalized);
                statement.setInt(3, safeLimit);
                statement.setInt(4, safeOffset);
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(
                                map(
                                        "email_id", result.getString("email_id"),
                                        "kind", result.getString("kind"),
                                        "recipient", result.getString("recipient"),
                                        "user_id", result.getString("user_id"),
                                        "status", result.getString("status"),
                                        "attempts", result.getInt("attempts"),
                                        "next_attempt_at", result.getString("next_attempt_at"),
                                        "sent_at", result.getString("sent_at"),
                                        "last_error", result.getString("last_error"),
                                        "created_at", result.getString("created_at")));
                    }
                }
                return map(
                        "items", rows,
                        "emails", rows,
                        "total", total,
                        "limit", safeLimit,
                        "offset", safeOffset);
            }
        } catch (Exception error) {
            throw failure("读取邮件队列失败", error);
        }
    }

    public Map<String, Object> retryEmail(Principal principal, String emailId) {
        requireAdmin(principal);
        try (Connection connection = storage.connection();
                PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + OUTBOX
                                        + " SET status='PENDING',attempts=0,next_attempt_at=NULL,last_error=NULL WHERE email_id=? AND status<>'SENT'")) {
            update.setString(1, emailId);
            if (update.executeUpdate() != 1) {
                throw new AuthException(404, "邮件不存在或已经发送成功");
            }
            audit(
                    connection,
                    principal.userId(),
                    "ACCOUNT_EMAIL_RETRY_REQUESTED",
                    "email",
                    emailId,
                    "");
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("重新发送邮件失败", error);
        }
        dispatchEmail(emailId);
        return map("ok", true, "email_id", emailId, "status", mailEnabled ? "PENDING" : "MAIL_DISABLED");
    }

    public void requireAdmin(Principal principal) {
        if (principal == null || !"PLATFORM_ADMIN".equals(principal.role())) {
            throw new AuthException(principal == null ? 401 : 403, "需要平台管理员权限");
        }
    }

    public boolean secureCookie() {
        return secureCookie;
    }

    @Scheduled(fixedDelayString = "${agent.platform.auth.mail.retry-delay-ms:60000}")
    public void retryEmailOutbox() {
        if (!mailEnabled) return;
        List<String> emailIds = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT email_id FROM "
                                        + OUTBOX
                                        + " WHERE status IN ('PENDING','FAILED') AND attempts < ? AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
                                        + " ORDER BY created_at LIMIT 10")) {
            statement.setInt(1, Math.max(1, mailMaxAttempts));
            statement.setString(2, now());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) emailIds.add(result.getString("email_id"));
            }
        } catch (Exception ignored) {
            // Email delivery must not take down the platform scheduler.
            return;
        }
        emailIds.forEach(this::dispatchEmail);
    }

    @Scheduled(
            fixedDelayString = "${agent.platform.auth.cleanup-delay-ms:3600000}",
            initialDelayString = "${agent.platform.auth.cleanup-initial-delay-ms:60000}")
    public void cleanupExpiredAuthState() {
        String now = now();
        String retention = Instant.now().minus(7, ChronoUnit.DAYS).toString();
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "DELETE FROM "
                            + SESSIONS
                            + " WHERE expires_at<'"
                            + now
                            + "' OR (revoked_at IS NOT NULL AND revoked_at<'"
                            + retention
                            + "')");
            statement.executeUpdate(
                    "DELETE FROM "
                            + TOKENS
                            + " WHERE expires_at<'"
                            + retention
                            + "' OR (used_at IS NOT NULL AND used_at<'"
                            + retention
                            + "')");
            try (PreparedStatement limits =
                    connection.prepareStatement(
                            "DELETE FROM "
                                    + RATE_LIMITS
                                    + " WHERE updated_at<? AND blocked_until<?")) {
                limits.setString(1, retention);
                limits.setLong(2, System.currentTimeMillis());
                limits.executeUpdate();
            }
        } catch (Exception ignored) {
            // Cleanup is best effort and must never affect authentication availability.
        }
    }

    private void consumeRateLimit(
            String action,
            String subject,
            long windowMs,
            int maximumAttempts,
            long blockMs,
            String message) {
        int maximum = Math.max(1, maximumAttempts);
        long nowMs = System.currentTimeMillis();
        long windowStart = nowMs;
        String subjectHash = sha256(subject);
        String sql =
                "INSERT INTO "
                        + RATE_LIMITS
                        + " (action,subject_hash,window_started_at,attempts,blocked_until,updated_at)"
                        + " VALUES (?,?,?,1,0,?)"
                        + " ON CONFLICT(action,subject_hash) DO UPDATE SET"
                        + " attempts=CASE WHEN window_started_at<? THEN 1 ELSE attempts+1 END,"
                        + " window_started_at=CASE WHEN window_started_at<? THEN ? ELSE window_started_at END,"
                        + " blocked_until=CASE WHEN blocked_until>? THEN blocked_until"
                        + " WHEN window_started_at<? THEN 0 WHEN attempts+1>? THEN ? ELSE 0 END,"
                        + " updated_at=excluded.updated_at"
                        + " RETURNING attempts,blocked_until";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            long cutoff = nowMs - Math.max(1L, windowMs);
            int index = 1;
            statement.setString(index++, action);
            statement.setString(index++, subjectHash);
            statement.setLong(index++, windowStart);
            statement.setString(index++, now());
            statement.setLong(index++, cutoff);
            statement.setLong(index++, cutoff);
            statement.setLong(index++, windowStart);
            statement.setLong(index++, nowMs);
            statement.setLong(index++, cutoff);
            statement.setInt(index++, maximum);
            statement.setLong(index, nowMs + Math.max(1L, blockMs));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getLong("blocked_until") > nowMs) {
                    throw new AuthException(429, message);
                }
            }
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("更新账号安全限流失败", error);
        }
    }

    private void recordLoginFailure(Connection connection, String userId) throws Exception {
        int maximum = Math.max(1, loginMaxFailedAttempts);
        String lockedUntil =
                Instant.now().plus(Math.max(1, loginLockMinutes), ChronoUnit.MINUTES).toString();
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE "
                                + USERS
                                + " SET failed_attempts=failed_attempts+1,locked_until=CASE WHEN failed_attempts+1>=? THEN ? ELSE locked_until END WHERE user_id=?")) {
            update.setInt(1, maximum);
            update.setString(2, lockedUntil);
            update.setString(3, userId);
            update.executeUpdate();
        }
    }

    private SetupDelivery issueSetupToken(
            Connection connection, String userId, String email, String createdAt)
            throws Exception {
        try (PreparedStatement invalidate =
                connection.prepareStatement(
                        "UPDATE " + TOKENS + " SET used_at=? WHERE user_id=? AND used_at IS NULL")) {
            invalidate.setString(1, createdAt);
            invalidate.setString(2, userId);
            invalidate.executeUpdate();
        }
        String rawToken = randomToken(32);
        String tokenId = "setup_" + randomToken(10);
        try (PreparedStatement token =
                connection.prepareStatement(
                        "INSERT INTO "
                                + TOKENS
                                + " (token_id,user_id,token_hash,expires_at,created_at) VALUES (?,?,?,?,?)")) {
            token.setString(1, tokenId);
            token.setString(2, userId);
            token.setString(3, sha256(rawToken));
            token.setString(
                    4, Instant.now().plus(SETUP_TOKEN_HOURS, ChronoUnit.HOURS).toString());
            token.setString(5, createdAt);
            token.executeUpdate();
        }
        String setupUrl = baseUrl + "/platform/live/access?mode=setup&token=" + rawToken;
        String emailId = queueEmail(connection, email, userId, tokenId, setupUrl, createdAt);
        return new SetupDelivery(emailId, setupUrl);
    }

    private Map<String, Object> requireUser(Connection connection, String userId)
            throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT user_id,email,display_name,status FROM "
                                + USERS
                                + " WHERE user_id=?")) {
            statement.setString(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AuthException(404, "用户不存在");
                return map(
                        "user_id", result.getString("user_id"),
                        "email", result.getString("email"),
                        "display_name", result.getString("display_name"),
                        "status", result.getString("status"));
            }
        }
    }

    private void requireActiveOrganization(Connection connection, String orgId) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT 1 FROM " + ORGS + " WHERE org_id=? AND status='ACTIVE'")) {
            statement.setString(1, orgId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AuthException(404, "组织不存在或已停用");
            }
        }
    }

    private boolean isPlatformAdmin(Connection connection, String userId) throws Exception {
        return exists(
                connection,
                "SELECT 1 FROM "
                        + MEMBERSHIPS
                        + " WHERE user_id=? AND role='PLATFORM_ADMIN' AND status='ACTIVE'",
                userId);
    }

    private void requireAnotherPlatformAdmin(Connection connection, String excludedUserId)
            throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM "
                                + MEMBERSHIPS
                                + " m JOIN "
                                + USERS
                                + " u ON u.user_id=m.user_id"
                                + " WHERE m.role='PLATFORM_ADMIN' AND m.status='ACTIVE' AND u.status='ACTIVE' AND m.user_id<>?")) {
            statement.setString(1, excludedUserId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) < 1) {
                    throw new AuthException(409, "平台必须至少保留一个启用的管理员");
                }
            }
        }
    }

    private int revokeSessions(Connection connection, String userId) throws Exception {
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE "
                                + SESSIONS
                                + " SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL")) {
            update.setString(1, now());
            update.setString(2, userId);
            return update.executeUpdate();
        }
    }

    private Map<String, Object> assetUsage(Connection connection, String userId) throws Exception {
        long agents =
                countIfTable(
                        connection,
                        "platform_agent_assets",
                        "SELECT COUNT(*) FROM platform_agent_assets WHERE created_by=? OR (owner_type='USER' AND owner_id=?)",
                        userId,
                        userId);
        long capabilities =
                countIfTable(
                        connection,
                        "platform_user_capabilities",
                        "SELECT COUNT(*) FROM platform_user_capabilities WHERE owner_id=?",
                        userId);
        long assets =
                countIfTable(
                        connection,
                        "platform_asset_metadata",
                        "SELECT COUNT(*) FROM platform_asset_metadata WHERE created_by=? OR (owner_type='USER' AND owner_id=?)",
                        userId,
                        userId);
        long runs =
                countIfTable(
                        connection,
                        "platform_agent_runs",
                        "SELECT COUNT(*) FROM platform_agent_runs WHERE json_valid(payload) AND json_extract(payload,'$.user_id')=?",
                        userId);
        return map(
                "agents", agents,
                "capabilities", capabilities,
                "platform_assets", assets,
                "runs", runs,
                "total", agents + capabilities + assets);
    }

    private long countIfTable(
            Connection connection, String table, String sql, String... values) throws Exception {
        if (!tableExists(connection, table)) return 0L;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        return exists(
                connection,
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                table);
    }

    private void bindUserFilter(
            PreparedStatement statement, String needle, String status) throws Exception {
        bindUserFilter(statement, 1, needle, status);
    }

    private int bindUserFilter(
            PreparedStatement statement, int index, String needle, String status)
            throws Exception {
        String pattern = "%" + needle + "%";
        statement.setString(index++, needle);
        statement.setString(index++, pattern);
        statement.setString(index++, pattern);
        statement.setString(index++, status);
        statement.setString(index++, status);
        return index;
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
            // Preserve the original transaction error.
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeSubject(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private record SetupDelivery(String emailId, String setupUrl) {}

    private void initialize() {
        if (!initialized.compareAndSet(false, true)) return;
        try (Connection connection = storage.connection()) {
            initializeOnConnection(connection);
            insertOrganizationIfMissing(connection, "platform", "平台公共空间", now());
            if (!bootstrapAdminPassword.isBlank()
                    && !exists(connection, "SELECT 1 FROM " + USERS + " WHERE email = ?", bootstrapAdminEmail)) {
                String userId = "user_platform_admin";
                try (PreparedStatement user =
                        connection.prepareStatement(
                                "INSERT INTO "
                                        + USERS
                                        + " (user_id,email,display_name,password_hash,status,must_change_password,failed_attempts,created_at)"
                                        + " VALUES (?,?,?,?,'ACTIVE',0,0,?)")) {
                    user.setString(1, userId);
                    user.setString(2, bootstrapAdminEmail);
                    user.setString(3, "Platform Admin");
                    user.setString(4, hashPassword(bootstrapAdminPassword));
                    user.setString(5, now());
                    user.executeUpdate();
                }
                insertMembership(connection, "platform", userId, "PLATFORM_ADMIN", now());
            }
        } catch (Exception error) {
            throw failure("初始化账号数据库失败", error);
        }
    }

    private void initializeOnConnection(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + ORGS + " (org_id TEXT PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + USERS + " (user_id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, password_hash TEXT, status TEXT NOT NULL, must_change_password INTEGER NOT NULL DEFAULT 1, email_verified_at TEXT, failed_attempts INTEGER NOT NULL DEFAULT 0, locked_until TEXT, last_login_at TEXT, created_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + MEMBERSHIPS + " (org_id TEXT NOT NULL, user_id TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY (org_id,user_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS " + APPLICATIONS + " (application_id TEXT PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, project TEXT, reason TEXT, status TEXT NOT NULL, reviewer_id TEXT, review_reason TEXT, reviewed_at TEXT, created_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + TOKENS + " (token_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, expires_at TEXT NOT NULL, used_at TEXT, created_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + SESSIONS + " (session_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, expires_at TEXT NOT NULL, revoked_at TEXT, created_at TEXT NOT NULL, last_seen_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + OUTBOX + " (email_id TEXT PRIMARY KEY, kind TEXT NOT NULL, recipient TEXT NOT NULL, user_id TEXT NOT NULL, token_id TEXT NOT NULL, subject TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT, sent_at TEXT, last_error TEXT, created_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + AUDIT + " (event_id TEXT PRIMARY KEY, actor_id TEXT NOT NULL, action TEXT NOT NULL, resource_type TEXT NOT NULL, resource_id TEXT NOT NULL, metadata TEXT, created_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + RATE_LIMITS + " (action TEXT NOT NULL, subject_hash TEXT NOT NULL, window_started_at INTEGER NOT NULL, attempts INTEGER NOT NULL, blocked_until INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL, PRIMARY KEY(action,subject_hash))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_platform_account_applications_status_created ON " + APPLICATIONS + " (status,created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_platform_sessions_user_active ON " + SESSIONS + " (user_id,revoked_at,expires_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_platform_email_outbox_status_retry ON " + OUTBOX + " (status,next_attempt_at)");
        }
    }

    private void insertOrganizationIfMissing(Connection connection, String orgId, String name, String now)
            throws Exception {
        if (!exists(connection, "SELECT 1 FROM " + ORGS + " WHERE org_id = ?", orgId)) {
            insertOrganization(connection, orgId, name, now);
        }
    }

    private void insertOrganization(Connection connection, String orgId, String name, String now)
            throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO " + ORGS + " (org_id,name,status,created_at) VALUES (?,?,'ACTIVE',?)")) {
            statement.setString(1, orgId);
            statement.setString(2, name);
            statement.setString(3, now);
            statement.executeUpdate();
        }
    }

    private void insertMembership(Connection connection, String orgId, String userId, String role, String now)
            throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT OR IGNORE INTO " + MEMBERSHIPS + " (org_id,user_id,role,status,created_at) VALUES (?,?,?,'ACTIVE',?)")) {
            statement.setString(1, orgId);
            statement.setString(2, userId);
            statement.setString(3, role);
            statement.setString(4, now);
            statement.executeUpdate();
        }
    }

    private String queueEmail(Connection connection, String recipient, String userId, String tokenId, String setupUrl, String now)
            throws Exception {
        String emailId = "email_" + randomToken(10);
        String body = "您的 Agent Platform 账号已通过审核。请打开以下链接设置密码（24 小时内有效，且只能使用一次）：\n\n" + setupUrl;
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO "
                                + OUTBOX
                                + " (email_id,kind,recipient,user_id,token_id,subject,body,status,attempts,created_at)"
                                + " VALUES (?,'PASSWORD_SETUP',?,?,?,?,?,'PENDING',0,?)")) {
            statement.setString(1, emailId);
            statement.setString(2, recipient);
            statement.setString(3, userId);
            statement.setString(4, tokenId);
            statement.setString(5, "Agent Platform 账号已通过审核");
            statement.setString(6, body);
            statement.setString(7, now);
            statement.executeUpdate();
        }
        return emailId;
    }

    private void dispatchEmail(String emailId) {
        if (!mailEnabled) return;
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) return;
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT recipient,subject,body,attempts FROM " + OUTBOX + " WHERE email_id = ? AND status <> 'SENT'")) {
            statement.setString(1, emailId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return;
                SimpleMailMessage message = new SimpleMailMessage();
                if (mailFrom != null && !mailFrom.isBlank()) message.setFrom(mailFrom.trim());
                message.setTo(result.getString("recipient"));
                message.setSubject(result.getString("subject"));
                message.setText(result.getString("body"));
                sender.send(message);
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE " + OUTBOX + " SET status='SENT',sent_at=?,attempts=attempts+1,last_error=NULL WHERE email_id=?")) {
                    update.setString(1, now());
                    update.setString(2, emailId);
                    update.executeUpdate();
                }
            }
        } catch (Exception error) {
            try (Connection connection = storage.connection();
                    PreparedStatement update =
                            connection.prepareStatement(
                                    "UPDATE "
                                            + OUTBOX
                                            + " SET status=CASE WHEN attempts+1>=? THEN 'DEAD' ELSE 'FAILED' END,attempts=attempts+1,next_attempt_at=CASE WHEN attempts+1>=? THEN NULL ELSE ? END,last_error=? WHERE email_id=?")) {
                update.setInt(1, Math.max(1, mailMaxAttempts));
                update.setInt(2, Math.max(1, mailMaxAttempts));
                update.setString(3, Instant.now().plus(5, ChronoUnit.MINUTES).toString());
                update.setString(4, error.getMessage());
                update.setString(5, emailId);
                update.executeUpdate();
            } catch (Exception ignored) {
                // Preserve the original delivery failure.
            }
        }
    }

    private Map<String, Object> application(Connection connection, String applicationId) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT application_id,email,display_name,project,reason,status FROM " + APPLICATIONS + " WHERE application_id=?")) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return map(
                        "application_id", result.getString("application_id"),
                        "email", result.getString("email"),
                        "display_name", result.getString("display_name"),
                        "project", result.getString("project"),
                        "reason", result.getString("reason"),
                        "status", result.getString("status"));
            }
        }
    }

    private Map<String, Object> user(Connection connection, String userId) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT user_id,email,display_name,status,must_change_password FROM " + USERS + " WHERE user_id=?")) {
            statement.setString(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Map.of();
                return map(
                        "user_id", result.getString("user_id"),
                        "email", result.getString("email"),
                        "display_name", result.getString("display_name"),
                        "status", result.getString("status"),
                        "must_change_password", result.getInt("must_change_password") == 1);
            }
        }
    }

    private String createSession(Connection connection, String userId) throws Exception {
        String raw = randomToken(32);
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO " + SESSIONS + " (session_id,user_id,token_hash,expires_at,created_at,last_seen_at) VALUES (?,?,?,?,?,?)")) {
            String now = now();
            statement.setString(1, "session_" + randomToken(10));
            statement.setString(2, userId);
            statement.setString(3, sha256(raw));
            statement.setString(4, Instant.now().plus(SESSION_DAYS, ChronoUnit.DAYS).toString());
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
        }
        return raw;
    }

    private void audit(Connection connection, String actorId, String action, String resourceType, String resourceId, String metadata)
            throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO " + AUDIT + " (event_id,actor_id,action,resource_type,resource_id,metadata,created_at) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, "audit_" + randomToken(12));
            statement.setString(2, actorId);
            statement.setString(3, action);
            statement.setString(4, resourceType);
            statement.setString(5, resourceId);
            statement.setString(6, metadata);
            statement.setString(7, now());
            statement.executeUpdate();
        }
    }

    private boolean exists(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        return "pbkdf2$" + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(derive(password, salt, PASSWORD_ITERATIONS));
    }

    private boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 3 || !"pbkdf2".equals(parts[0])) return false;
            byte[] salt = Base64.getUrlDecoder().decode(parts[1]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[2]);
            return MessageDigest.isEqual(expected, derive(password, salt, PASSWORD_ITERATIONS));
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256))
                    .getEncoded();
        } catch (Exception error) {
            throw new IllegalStateException("无法生成密码哈希", error);
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 10) {
            throw new AuthException(400, "密码至少需要 10 位");
        }
    }

    private String randomToken(int bytes) {
        byte[] data = new byte[bytes];
        secureRandom.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String now() {
        return Instant.now().toString();
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String trimTrailingSlash(String value) {
        String result = value == null || value.isBlank() ? "http://localhost:8080" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String string(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private AuthException failure(String message, Exception error) {
        return new AuthException(500, message + ": " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
    }
}
