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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.runtime.ChatRequest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalAccessServiceTest {

    @TempDir Path tempDir;

    @Test
    void managedKeysAreHashedRevocableAndOwnerScoped() throws Exception {
        PlatformStorageLayer storage = storage();
        initializeAccounts(storage);
        ExternalAccessService service = new ExternalAccessService(storage);
        PlatformAuthService.Principal owner = principal("user_a", "org_a");
        PlatformAuthService.Principal other = principal("user_b", "org_b");

        Map<String, Object> created = service.createKey(owner, "CI smoke", "");
        String secret = String.valueOf(created.get("secret"));
        assertTrue(secret.startsWith("ap_live_"));
        assertFalse(service.listKeys(owner).get(0).containsKey("secret"));
        assertNotNull(
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2), () -> service.authenticate(secret)));

        ExternalChatRequest request =
                ExternalChatRequest.from(
                        Map.of(
                                "tenant_id", "forged-org",
                                "user_id", "admin",
                                "session_id", "client-session",
                                "message", "hello"));
        ChatRequest runtime =
                request.toRuntimeRequest(
                        owner.orgId(), "external:key:admin", "client-session", "run_1", "agent_1");
        assertEquals("org_a", runtime.tenantId());
        assertEquals("external:key:admin", runtime.userId());
        assertEquals("run_1", runtime.taskContext().rootTaskId());

        ExternalAccessService.ApiClient client = service.authenticate(secret);
        service.beginInvocation(
                "req_1",
                client,
                "agent_1",
                "stream",
                "client-session",
                service.safeRequest(
                        request, runtime.userId(), runtime.tenantId(), runtime.sessionId()));
        service.completeInvocation(
                "req_1", "run_1", "SUCCEEDED", 200, 123, "done", "", "");

        List<Map<String, Object>> ownerHistory = service.invocationHistory(owner, 50);
        assertEquals(1, ownerHistory.size());
        assertEquals("run_1", ownerHistory.get(0).get("run_id"));
        assertEquals("/platform/live/runs?run_id=run_1", ownerHistory.get(0).get("observe_url"));
        assertTrue(service.invocationHistory(other, 50).isEmpty());

        service.revokeKey(String.valueOf(created.get("key_id")), owner);
        assertNull(service.authenticate(secret));
        assertEquals("REVOKED", service.listKeys(owner).get(0).get("status"));
    }

    @Test
    void managedKeyPoliciesRestrictAgentsCapabilitiesAndRate() throws Exception {
        PlatformStorageLayer storage = storage();
        initializeAccounts(storage);
        ExternalAccessService service = new ExternalAccessService(storage);
        PlatformAuthService.Principal owner = principal("user_a", "org_a");

        Map<String, Object> created =
                service.createKey(
                        owner,
                        "Restricted client",
                        Instant.now().plusSeconds(3600).toString(),
                        List.of("agent_allowed"),
                        List.of("chat"),
                        2);
        ExternalAccessService.ApiClient client =
                service.authenticate(String.valueOf(created.get("secret")));
        assertNotNull(client);
        assertTrue(client.allowsAgent("agent_allowed"));
        assertFalse(client.allowsAgent("agent_denied"));
        assertTrue(client.allowsCapability("chat"));
        assertFalse(client.allowsCapability("stream"));

        assertEquals(
                "agent_not_allowed",
                service.authorizeInvocation(client, "agent_denied", "chat").code());
        assertEquals(
                "capability_not_allowed",
                service.authorizeInvocation(client, "agent_allowed", "stream").code());
        assertTrue(service.authorizeInvocation(client, "agent_allowed", "chat").allowed());
        assertTrue(service.authorizeInvocation(client, "agent_allowed", "chat").allowed());
        ExternalAccessService.AccessDecision limited =
                service.authorizeInvocation(client, "agent_allowed", "chat");
        assertFalse(limited.allowed());
        assertEquals(429, limited.httpStatus());
        assertEquals("rate_limit_exceeded", limited.code());

        Map<String, Object> listed = service.listKeys(owner).get(0);
        assertEquals(List.of("agent_allowed"), listed.get("allowed_agent_ids"));
        assertEquals(List.of("chat"), listed.get("capabilities"));
        assertEquals(2, listed.get("rate_limit_per_minute"));
        assertThrows(
                PlatformAuthService.AuthException.class,
                () ->
                        service.createKey(
                                owner,
                                "Invalid",
                                "",
                                List.of(),
                                List.of(),
                                10));
    }

    @Test
    void existingKeyTableIsMigratedAndAdminOverviewAggregatesInvocations() throws Exception {
        PlatformStorageLayer storage = storage();
        initializeAccounts(storage);
        storage.initializeSqliteSchema(
                "CREATE TABLE platform_external_api_keys"
                        + " (key_id TEXT PRIMARY KEY,owner_user_id TEXT NOT NULL,org_id TEXT NOT NULL,name TEXT NOT NULL,key_prefix TEXT NOT NULL,secret_hash TEXT NOT NULL UNIQUE,status TEXT NOT NULL,created_at TEXT NOT NULL,last_used_at TEXT,expires_at TEXT,revoked_at TEXT)");

        ExternalAccessService service = new ExternalAccessService(storage);
        PlatformAuthService.Principal owner = principal("user_a", "org_a");
        Map<String, Object> created = service.createKey(owner, "Admin stats", "");
        ExternalAccessService.ApiClient client =
                service.authenticate(String.valueOf(created.get("secret")));
        service.beginInvocation(
                "req_admin",
                client,
                "agent_1",
                "chat",
                "session_1",
                Map.of("message", "safe"));
        service.completeInvocation(
                "req_admin", "run_admin", "SUCCEEDED", 200, 25, "done", "", "");

        Map<String, Object> overview =
                service.adminOverview(principal("admin", "platform", "PLATFORM_ADMIN"), 10);
        assertEquals(1, ((Map<?, ?>) overview.get("keys")).get("total"));
        assertEquals(1, ((Map<?, ?>) overview.get("invocations")).get("succeeded"));
        assertEquals(1, ((List<?>) overview.get("recent")).size());

        try (Connection connection = storage.connection();
                Statement statement = connection.createStatement();
                ResultSet columns =
                        statement.executeQuery("PRAGMA table_info(platform_external_api_keys)")) {
            boolean foundPolicyColumn = false;
            while (columns.next()) {
                if ("rate_limit_per_minute".equals(columns.getString("name"))) {
                    foundPolicyColumn = true;
                }
            }
            assertTrue(foundPolicyColumn);
        }
    }

    @Test
    void rejectedRequestsAreAuditedWithoutPersistingCredentialsOrBodies() {
        PlatformStorageLayer storage = storage();
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    initializeAccounts(storage);
                    ExternalAccessService service = new ExternalAccessService(storage);
                    PlatformAuthService.Principal owner = principal("user_a", "org_a");
                    PlatformAuthService.Principal admin =
                            principal("admin", "platform", "PLATFORM_ADMIN");
                    Map<String, Object> created = service.createKey(owner, "Audit client", "");
                    String secret = String.valueOf(created.get("secret"));
                    ExternalAccessService.ApiClient client = service.authenticate(secret);

                    service.recordRejectedRequest(
                            client,
                            secret,
                            "agent_denied",
                            "sync",
                            "/api/v1/agents/agent_denied/chat",
                            "127.0.0.1",
                            403,
                            "agent_not_allowed",
                            "denied");
                    service.recordRejectedRequest(
                            null,
                            "invalid-secret-value",
                            "",
                            "read",
                            "/api/v1/agents",
                            "127.0.0.2",
                            401,
                            "invalid_api_key",
                            "invalid");

                    Map<String, Object> ownerAudit = service.invocationHistory(owner, 10).get(0);
                    assertEquals("REJECTED", ownerAudit.get("status"));
                    assertEquals(403, ownerAudit.get("http_status"));
                    assertEquals(Map.of(), ownerAudit.get("request"));
                    assertEquals(16, String.valueOf(ownerAudit.get("credential_fingerprint")).length());
                    assertFalse(ownerAudit.toString().contains(secret));

                    Map<String, Object> overview = service.adminOverview(admin, 10);
                    assertEquals(2, ((Map<?, ?>) overview.get("invocations")).get("rejected"));
                    assertFalse(overview.toString().contains("invalid-secret-value"));
                });
    }

    @Test
    void administratorsCanFilterAndRevokeKeysAcrossOwners() throws Exception {
        PlatformStorageLayer storage = storage();
        initializeAccounts(storage);
        ExternalAccessService service = new ExternalAccessService(storage);
        PlatformAuthService.Principal ownerA = principal("user_a", "org_a");
        PlatformAuthService.Principal ownerB = principal("user_b", "org_b");
        PlatformAuthService.Principal admin =
                principal("admin", "platform", "PLATFORM_ADMIN");
        service.createKey(ownerA, "A integration", "");
        Map<String, Object> keyB = service.createKey(ownerB, "B integration", "");

        PlatformAuthService.AuthException denied =
                assertThrows(
                        PlatformAuthService.AuthException.class,
                        () -> service.adminListKeys(ownerA, "", "ALL", 100));
        assertEquals(403, denied.status());
        List<Map<String, Object>> filtered =
                service.adminListKeys(admin, "b@example.com", "ACTIVE", 100);
        assertEquals(1, filtered.size());
        assertEquals("user_b", filtered.get(0).get("owner_user_id"));

        service.adminSetKeySuspended(String.valueOf(keyB.get("key_id")), admin, true);
        assertNull(service.authenticate(String.valueOf(keyB.get("secret"))));
        assertNotNull(service.identifyManagedCredential(String.valueOf(keyB.get("secret"))));
        assertEquals(
                "SUSPENDED",
                service.adminListKeys(admin, "B integration", "SUSPENDED", 100)
                        .get(0)
                        .get("status"));
        service.adminSetKeySuspended(String.valueOf(keyB.get("key_id")), admin, false);
        assertNotNull(service.authenticate(String.valueOf(keyB.get("secret"))));
        service.adminRevokeKey(String.valueOf(keyB.get("key_id")), admin);
        assertNull(service.authenticate(String.valueOf(keyB.get("secret"))));
        Map<String, Object> revoked =
                service.adminListKeys(admin, "B integration", "REVOKED", 100).get(0);
        assertEquals("admin", revoked.get("revoked_by_user_id"));
    }

    @Test
    void ownerCanRotateAKeyWhileOldAndNewSecretsOverlap() throws Exception {
        PlatformStorageLayer storage = storage();
        initializeAccounts(storage);
        ExternalAccessService service = new ExternalAccessService(storage);
        PlatformAuthService.Principal owner = principal("user_a", "org_a");
        Map<String, Object> original =
                service.createKey(
                        owner,
                        "Rotating client",
                        Instant.now().plusSeconds(3600).toString(),
                        List.of("agent_allowed"),
                        List.of("chat"),
                        17);
        String originalSecret = String.valueOf(original.get("secret"));

        Map<String, Object> rotated =
                service.rotateKey(
                        String.valueOf(original.get("key_id")),
                        principal("user_a", "another_active_org"),
                        15);
        String rotatedSecret = String.valueOf(rotated.get("secret"));
        assertNotNull(service.authenticate(originalSecret));
        ExternalAccessService.ApiClient rotatedClient = service.authenticate(rotatedSecret);
        assertNotNull(rotatedClient);
        assertEquals(Set.of("agent_allowed"), rotatedClient.allowedAgentIds());
        assertEquals(Set.of("chat"), rotatedClient.capabilities());
        assertEquals(17, rotatedClient.rateLimitPerMinute());
        assertEquals(original.get("key_id"), rotated.get("previous_key_id"));

        List<Map<String, Object>> keys = service.listKeys(owner);
        Map<String, Object> newRow =
                keys.stream()
                        .filter(item -> rotated.get("key_id").equals(item.get("key_id")))
                        .findFirst()
                        .orElseThrow();
        Map<String, Object> oldRow =
                keys.stream()
                        .filter(item -> original.get("key_id").equals(item.get("key_id")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(original.get("key_id"), newRow.get("rotation_parent_key_id"));
        assertEquals(rotated.get("key_id"), oldRow.get("rotated_to_key_id"));
        assertFalse(String.valueOf(oldRow.get("rotation_grace_until")).isBlank());
    }

    private PlatformStorageLayer storage() {
        return new PlatformStorageLayer(
                tempDir.toString(),
                "sqlite",
                "jdbc:sqlite:" + tempDir.resolve("external-access.db"),
                "platform_config",
                "platform_",
                "");
    }

    private static void initializeAccounts(PlatformStorageLayer storage) throws Exception {
        storage.initializeSqliteSchema(
                "CREATE TABLE platform_users (user_id TEXT PRIMARY KEY,email TEXT,display_name TEXT,status TEXT)",
                "CREATE TABLE platform_memberships (org_id TEXT,user_id TEXT,role TEXT,status TEXT,PRIMARY KEY(org_id,user_id))");
        try (Connection connection = storage.connection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO platform_users(user_id,email,display_name,status) VALUES (?,?,?,'ACTIVE')")) {
                insertUser(statement, "user_a", "a@example.com", "A");
                insertUser(statement, "user_b", "b@example.com", "B");
                insertUser(statement, "admin", "admin@example.com", "Admin");
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO platform_memberships(org_id,user_id,role,status) VALUES (?,?,'BUILDER','ACTIVE')")) {
                insertMembership(statement, "org_a", "user_a");
                insertMembership(statement, "org_b", "user_b");
                statement.setString(1, "platform");
                statement.setString(2, "admin");
                statement.executeUpdate();
            }
        }
    }

    private static void insertUser(
            PreparedStatement statement, String userId, String email, String displayName)
            throws Exception {
        statement.setString(1, userId);
        statement.setString(2, email);
        statement.setString(3, displayName);
        statement.executeUpdate();
    }

    private static void insertMembership(
            PreparedStatement statement, String orgId, String userId) throws Exception {
        statement.setString(1, orgId);
        statement.setString(2, userId);
        statement.executeUpdate();
    }

    private static PlatformAuthService.Principal principal(String userId, String orgId) {
        return principal(userId, orgId, "BUILDER");
    }

    private static PlatformAuthService.Principal principal(
            String userId, String orgId, String role) {
        return new PlatformAuthService.Principal(
                userId, userId + "@example.com", userId, orgId, role);
    }
}
