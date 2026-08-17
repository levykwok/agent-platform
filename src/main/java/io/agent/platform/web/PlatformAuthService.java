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
        String email = normalizeEmail(emailValue);
        if (email.isBlank() || password == null || password.isBlank()) {
            throw new AuthException(400, "邮箱和密码不能为空");
        }
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT user_id,email,display_name,password_hash,status,must_change_password"
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
                String passwordHash = result.getString("password_hash");
                if (passwordHash == null || passwordHash.isBlank()) {
                    throw new AuthException(403, "账号尚未完成首次密码设置，请使用邮件中的链接");
                }
                if (!verifyPassword(password, passwordHash)) {
                    throw new AuthException(401, "邮箱或密码错误");
                }
                String userId = result.getString("user_id");
                String sessionToken = createSession(connection, userId);
                try (PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE "
                                        + USERS
                                        + " SET last_login_at = ?, failed_attempts = 0 WHERE user_id = ?")) {
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
            String userId;
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT user_id FROM "
                                    + TOKENS
                                    + " WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?")) {
                statement.setString(1, tokenHash);
                statement.setString(2, now());
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
                                    + USERS
                                    + " SET password_hash = ?, must_change_password = 0, email_verified_at = ?, display_name = COALESCE(NULLIF(?,''),display_name)"
                                    + " WHERE user_id = ?")) {
                update.setString(1, hashPassword(password));
                update.setString(2, now());
                update.setString(3, displayName == null ? "" : displayName.trim());
                update.setString(4, userId);
                update.executeUpdate();
            }
            try (PreparedStatement update =
                    connection.prepareStatement(
                            "UPDATE " + TOKENS + " SET used_at = ? WHERE token_hash = ?")) {
                update.setString(1, now());
                update.setString(2, tokenHash);
                update.executeUpdate();
            }
            String sessionToken = createSession(connection, userId);
            audit(connection, userId, "PASSWORD_SETUP_COMPLETED", "user", userId, "");
            return map("ok", true, "session_token", sessionToken, "user", user(connection, userId));
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
                                        + " m ON m.user_id=u.user_id"
                                        + " WHERE s.token_hash = ? AND s.expires_at > ? AND s.revoked_at IS NULL"
                                        + " AND u.status='ACTIVE' ORDER BY CASE WHEN m.role='PLATFORM_ADMIN' THEN 0 ELSE 1 END LIMIT 1")) {
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

    public Map<String, Object> approve(
            Principal principal, String applicationId, Map<String, Object> payload) {
        requireAdmin(principal);
        String role = string(payload, "role", "BUILDER").toUpperCase();
        if (!List.of("ORG_ADMIN", "BUILDER", "TESTER", "VIEWER").contains(role)) {
            throw new AuthException(400, "不支持的用户角色");
        }
        try (Connection connection = storage.connection()) {
            Map<String, Object> application = application(connection, applicationId);
            if (application == null || !"PENDING".equals(application.get("status"))) {
                throw new AuthException(404, "账号申请不存在或已处理");
            }
            String email = String.valueOf(application.get("email"));
            if (exists(connection, "SELECT 1 FROM " + USERS + " WHERE email = ?", email)) {
                throw new AuthException(409, "该邮箱已经存在账号");
            }
            String userId = "user_" + randomToken(12);
            String orgId = "org_" + randomToken(10);
            String orgName = string(payload, "organization", String.valueOf(application.get("project")));
            if (orgName.isBlank()) orgName = String.valueOf(application.get("display_name")) + "的空间";
            String now = now();
            insertOrganization(connection, orgId, orgName, now);
            try (PreparedStatement user =
                    connection.prepareStatement(
                            "INSERT INTO "
                                    + USERS
                                    + " (user_id,email,display_name,password_hash,status,must_change_password,failed_attempts,created_at)"
                                    + " VALUES (?,?,?,NULL,'ACTIVE',1,0,?)")) {
                user.setString(1, userId);
                user.setString(2, email);
                user.setString(3, String.valueOf(application.get("display_name")));
                user.setString(4, now);
                user.executeUpdate();
            }
            insertMembership(connection, orgId, userId, role, now);
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
                token.setString(4, Instant.now().plus(SETUP_TOKEN_HOURS, ChronoUnit.HOURS).toString());
                token.setString(5, now);
                token.executeUpdate();
            }
            String setupUrl = baseUrl + "/platform/live/access?mode=setup&token=" + rawToken;
            String emailId = queueEmail(connection, email, userId, tokenId, setupUrl, now);
            try (PreparedStatement update =
                    connection.prepareStatement(
                            "UPDATE "
                                    + APPLICATIONS
                                    + " SET status='APPROVED',reviewer_id=?,reviewed_at=?,review_reason=? WHERE application_id=?")) {
                update.setString(1, principal.userId());
                update.setString(2, now);
                update.setString(3, string(payload, "review_reason", ""));
                update.setString(4, applicationId);
                update.executeUpdate();
            }
            audit(connection, principal.userId(), "ACCOUNT_APPLICATION_APPROVED", "application", applicationId, userId);
            Map<String, Object> result =
                    map(
                            "ok", true,
                            "application_id", applicationId,
                            "user_id", userId,
                            "organization_id", orgId,
                            "email_id", emailId,
                            "email_status", mailEnabled ? "PENDING" : "MANUAL_SETUP_REQUIRED");
            if (!mailEnabled) result.put("setup_url", setupUrl);
            dispatchEmail(emailId);
            return result;
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("审核账号申请失败", error);
        }
    }

    public Map<String, Object> reject(Principal principal, String applicationId, Map<String, Object> payload) {
        requireAdmin(principal);
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + APPLICATIONS
                                        + " SET status='REJECTED',reviewer_id=?,review_reason=?,reviewed_at=? WHERE application_id=? AND status='PENDING'")) {
            statement.setString(1, principal.userId());
            statement.setString(2, string(payload, "review_reason", ""));
            statement.setString(3, now());
            statement.setString(4, applicationId);
            if (statement.executeUpdate() == 0) throw new AuthException(404, "账号申请不存在或已处理");
            audit(connection, principal.userId(), "ACCOUNT_APPLICATION_REJECTED", "application", applicationId, "");
            return map("ok", true, "application_id", applicationId, "status", "REJECTED");
        } catch (AuthException error) {
            throw error;
        } catch (Exception error) {
            throw failure("拒绝账号申请失败", error);
        }
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
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT email_id FROM "
                                        + OUTBOX
                                        + " WHERE status IN ('PENDING','FAILED') AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
                                        + " ORDER BY created_at LIMIT 10")) {
            statement.setString(1, now());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) dispatchEmail(result.getString("email_id"));
            }
        } catch (Exception ignored) {
            // Email delivery must not take down the platform scheduler.
        }
    }

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
                                    "UPDATE " + OUTBOX + " SET status='FAILED',attempts=attempts+1,next_attempt_at=?,last_error=? WHERE email_id=?")) {
                update.setString(1, Instant.now().plus(5, ChronoUnit.MINUTES).toString());
                update.setString(2, error.getMessage());
                update.setString(3, emailId);
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
