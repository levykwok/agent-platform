/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

class PlatformAuthServiceTest {

    @TempDir Path tempDir;

    @Test
    void applicationApprovalSetupAndLoginFlowUsesOneTimeToken() {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("platform-auth.db"),
                        "platform_config",
                        "platform_",
                        "");
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> mailSender = mock(ObjectProvider.class);
        PlatformAuthService auth =
                new PlatformAuthService(
                        storage,
                        mailSender,
                        "admin@example.com",
                        "AdminPassword123!",
                        "http://localhost:8080",
                        false,
                        false);

        Map<String, Object> application =
                auth.apply(
                        Map.of(
                                "email", "user@example.com",
                                "display_name", "Test User",
                                "project", "Sandbox",
                                "reason", "Agent testing"));
        assertEquals("PENDING", application.get("status"));

        Map<String, Object> adminLogin = auth.login("admin@example.com", "AdminPassword123!");
        PlatformAuthService.Principal admin =
                auth.current(String.valueOf(adminLogin.get("session_token")));
        assertNotNull(admin);
        assertEquals("PLATFORM_ADMIN", admin.role());

        Map<String, Object> approved =
                auth.approve(
                        admin,
                        String.valueOf(application.get("application_id")),
                        Map.of("organization", "Sandbox", "role", "BUILDER"));
        String setupUrl = String.valueOf(approved.get("setup_url"));
        String token = setupUrl.substring(setupUrl.indexOf("token=") + "token=".length());
        Map<String, Object> setup = auth.setupPassword(token, "UserPassword123!", "Test User");
        PlatformAuthService.Principal user =
                auth.current(String.valueOf(setup.get("session_token")));
        assertNotNull(user);
        assertEquals("BUILDER", user.role());

        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> auth.setupPassword(token, "AnotherPassword123!", "Test User"));
        assertNotNull(auth.login("user@example.com", "UserPassword123!"));
        assertNull(auth.current("not-a-real-session"));
    }

    @Test
    void repeatedLoginFailuresLockAccountAndSuccessfulAdminUpdateUnlocksIt() {
        PlatformAuthService auth = auth(tempDir.resolve("lock"), false, null);
        PlatformAuthService.Principal admin = principal(auth);

        for (int attempt = 0; attempt < 5; attempt++) {
            PlatformAuthService.AuthException error =
                    assertThrows(
                            PlatformAuthService.AuthException.class,
                            () -> auth.login("admin@example.com", "wrong-password", "10.0.0.1"));
            assertEquals(401, error.status());
        }
        PlatformAuthService.AuthException locked =
                assertThrows(
                        PlatformAuthService.AuthException.class,
                        () -> auth.login("admin@example.com", "AdminPassword123!", "10.0.0.1"));
        assertEquals(429, locked.status());

        auth.updateUser(
                admin,
                admin.userId(),
                Map.of("display_name", "Platform Admin", "status", "ACTIVE"));
        assertNotNull(auth.login("admin@example.com", "AdminPassword123!", "10.0.0.2"));
    }

    @Test
    void publicApplicationEndpointIsDurablyRateLimitedByClientAddress() {
        PlatformAuthService auth = auth(tempDir.resolve("rate-limit"), false, null);
        for (int index = 0; index < 10; index++) {
            assertEquals(
                    "PENDING",
                    auth.apply(
                                    Map.of(
                                            "email", "applicant" + index + "@example.com",
                                            "display_name", "Applicant " + index),
                                    "192.0.2.10")
                            .get("status"));
        }
        PlatformAuthService.AuthException limited =
                assertThrows(
                        PlatformAuthService.AuthException.class,
                        () ->
                                auth.apply(
                                        Map.of(
                                                "email", "overflow@example.com",
                                                "display_name", "Overflow"),
                                        "192.0.2.10"));
        assertEquals(429, limited.status());
    }

    @Test
    void concurrentApprovalCreatesExactlyOneAccountAndOneTerminalDecision() throws Exception {
        PlatformAuthService auth = auth(tempDir.resolve("concurrent"), false, null);
        PlatformAuthService.Principal admin = principal(auth);
        String applicationId =
                String.valueOf(
                        auth.apply(
                                        Map.of(
                                                "email", "concurrent@example.com",
                                                "display_name", "Concurrent User"),
                                        "198.51.100.10")
                                .get("application_id"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> approval =
                () -> {
                    ready.countDown();
                    start.await();
                    try {
                        return auth.approve(admin, applicationId, Map.of("role", "BUILDER"));
                    } catch (RuntimeException error) {
                        return error;
                    }
                };
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            futures.add(executor.submit(approval));
            futures.add(executor.submit(approval));
            ready.await();
            start.countDown();
            List<Object> results = List.of(futures.get(0).get(), futures.get(1).get());
            assertEquals(1, results.stream().filter(Map.class::isInstance).count());
            assertEquals(1, results.stream().filter(RuntimeException.class::isInstance).count());
            assertEquals(
                    1,
                    ((Number) auth.users(admin, "concurrent@example.com", "", 20, 0).get("total"))
                            .intValue());
            assertEquals(
                    "APPROVED",
                    ((Map<?, ?>)
                                    ((List<?>)
                                                    auth.applicationPage(
                                                                    admin,
                                                                    "concurrent@example.com",
                                                                    "",
                                                                    20,
                                                                    0)
                                                            .get("items"))
                                            .get(0))
                            .get("status"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void adminCanManageUserMembershipSessionsPasswordAndUsage() throws Exception {
        PlatformAuthService auth = auth(tempDir.resolve("management"), false, null);
        PlatformAuthService.Principal admin = principal(auth);
        Map<String, Object> application =
                auth.apply(
                        Map.of(
                                "email", "managed@example.com",
                                "display_name", "Managed User",
                                "project", "Managed Org"),
                        "203.0.113.5");
        Map<String, Object> approved =
                auth.approve(
                        admin,
                        String.valueOf(application.get("application_id")),
                        Map.of("role", "BUILDER"));
        String userId = String.valueOf(approved.get("user_id"));
        String initialToken = token(approved);
        Map<String, Object> setup =
                auth.setupPassword(initialToken, "ManagedPassword123!", "Managed User");
        String initialSessionToken = String.valueOf(setup.get("session_token"));
        assertNotNull(auth.current(initialSessionToken));

        List<?> sessions = (List<?>) auth.userSessions(admin, userId).get("items");
        assertEquals(1, sessions.size());
        String sessionId = String.valueOf(((Map<?, ?>) sessions.get(0)).get("session_id"));
        auth.revokeUserSession(admin, userId, sessionId);
        assertNull(auth.current(initialSessionToken));

        try (var connection = storage(tempDir.resolve("management")).connection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS platform_agent_runs (run_id TEXT PRIMARY KEY,payload TEXT NOT NULL,updated_at TEXT NOT NULL)");
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO platform_agent_runs(run_id,payload,updated_at) VALUES (?,?,?)")) {
                insert.setString(1, "run_managed");
                insert.setString(2, "{\"user_id\":\"" + userId + "\"}");
                insert.setString(3, Instant.now().toString());
                insert.executeUpdate();
            }
        }

        Map<String, Object> users = auth.users(admin, "managed@example.com", "ACTIVE", 20, 0);
        assertEquals(1, ((Number) users.get("total")).intValue());
        Map<?, ?> row = (Map<?, ?>) ((List<?>) users.get("items")).get(0);
        assertEquals(0, ((Number) row.get("active_sessions")).intValue());
        assertNotNull(row.get("asset_usage"));
        assertEquals(1, ((Number) ((Map<?, ?>) row.get("asset_usage")).get("runs")).intValue());

        Map<String, Object> membership =
                auth.setMembership(
                        admin,
                        userId,
                        Map.of("role", "TESTER", "organization", "QA Team"));
        assertEquals("TESTER", membership.get("role"));
        assertFalse(String.valueOf(membership.get("org_id")).isBlank());
        assertTrue(
                auth.revokeUserSessions(admin, userId).containsKey("revoked_sessions"));

        Map<String, Object> reset = auth.resetUserPassword(admin, userId);
        assertFalse(token(reset).isBlank());
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> auth.login("managed@example.com", "ManagedPassword123!", "203.0.113.6"));
        auth.setupPassword(token(reset), "ReplacementPassword123!", "Managed User");
        assertNotNull(
                auth.login(
                        "managed@example.com", "ReplacementPassword123!", "203.0.113.7"));

        auth.updateUser(
                admin, userId, Map.of("display_name", "Managed User", "status", "SUSPENDED"));
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> auth.login("managed@example.com", "ReplacementPassword123!", "203.0.113.8"));
        auth.updateUser(
                admin, userId, Map.of("display_name", "Managed User", "status", "ACTIVE"));
        assertNotNull(auth.organizations(admin));
        assertTrue(((List<?>) auth.emailOutbox(admin, "", 50, 0).get("items")).size() >= 2);
    }

    @Test
    void failedMailMovesToDeadAfterConfiguredAutomaticAttempts() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp unavailable"))
                .when(sender)
                .send(any(org.springframework.mail.SimpleMailMessage.class));
        PlatformAuthService auth = auth(tempDir.resolve("mail"), true, sender);
        PlatformAuthService.Principal admin = principal(auth);
        Map<String, Object> application =
                auth.apply(
                        Map.of("email", "mail@example.com", "display_name", "Mail User"),
                        "203.0.113.9");
        auth.approve(
                admin,
                String.valueOf(application.get("application_id")),
                Map.of("role", "BUILDER"));
        PlatformStorageLayer storage = storage(tempDir.resolve("mail"));
        for (int attempt = 1; attempt < 5; attempt++) {
            try (var connection = storage.connection();
                    PreparedStatement update =
                            connection.prepareStatement(
                                    "UPDATE platform_email_outbox SET next_attempt_at=? WHERE status='FAILED'")) {
                update.setString(1, Instant.EPOCH.toString());
                update.executeUpdate();
            }
            auth.retryEmailOutbox();
        }
        List<?> rows = (List<?>) auth.emailOutbox(admin, "", 20, 0).get("items");
        assertEquals("DEAD", ((Map<?, ?>) rows.get(0)).get("status"));
    }

    @Test
    void administratorCanCreateRenameAndSafelySuspendOrganizations() {
        PlatformAuthService auth = auth(tempDir.resolve("organizations"), false, null);
        PlatformAuthService.Principal admin = principal(auth);
        Map<String, Object> created = auth.createOrganization(admin, Map.of("name", "Quality Lab"));
        String orgId = String.valueOf(created.get("org_id"));
        assertEquals("ACTIVE", created.get("status"));
        auth.updateOrganization(
                admin, orgId, Map.of("name", "Quality Engineering", "status", "ACTIVE"));
        assertTrue(
                auth.organizations(admin).stream()
                        .anyMatch(
                                row ->
                                        orgId.equals(row.get("org_id"))
                                                && "Quality Engineering".equals(row.get("name"))));
        Map<String, Object> application =
                auth.apply(
                        Map.of(
                                "email", "organization-member@example.com",
                                "display_name", "Organization Member"),
                        "198.51.100.44");
        Map<String, Object> approved =
                auth.approve(
                        admin,
                        String.valueOf(application.get("application_id")),
                        Map.of("role", "BUILDER", "organization_id", orgId));
        PlatformAuthService.AuthException occupied =
                assertThrows(
                        PlatformAuthService.AuthException.class,
                        () ->
                                auth.updateOrganization(
                                        admin, orgId, Map.of("status", "SUSPENDED")));
        assertEquals(409, occupied.status());
        auth.setMembership(
                admin,
                String.valueOf(approved.get("user_id")),
                Map.of("role", "BUILDER", "organization", "Moved Members"));
        assertEquals(
                "SUSPENDED",
                auth.updateOrganization(admin, orgId, Map.of("status", "SUSPENDED"))
                        .get("status"));
        PlatformAuthService.AuthException platformRejected =
                assertThrows(
                        PlatformAuthService.AuthException.class,
                        () ->
                                auth.updateOrganization(
                                        admin, "platform", Map.of("status", "SUSPENDED")));
        assertEquals(409, platformRejected.status());
    }

    private PlatformAuthService auth(Path workspace, boolean mailEnabled, JavaMailSender sender) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new PlatformAuthService(
                storage(workspace),
                provider,
                "admin@example.com",
                "AdminPassword123!",
                "http://localhost:8080",
                mailEnabled,
                false);
    }

    private PlatformStorageLayer storage(Path workspace) {
        return new PlatformStorageLayer(
                workspace.toString(),
                "sqlite",
                "jdbc:sqlite:" + workspace.resolve("platform-auth.db"),
                "platform_config",
                "platform_",
                "");
    }

    private PlatformAuthService.Principal principal(PlatformAuthService auth) {
        Map<String, Object> login = auth.login("admin@example.com", "AdminPassword123!", "127.0.0.1");
        return auth.current(String.valueOf(login.get("session_token")));
    }

    private String token(Map<String, Object> response) {
        String setupUrl = String.valueOf(response.get("setup_url"));
        return setupUrl.substring(setupUrl.indexOf("token=") + "token=".length());
    }
}
