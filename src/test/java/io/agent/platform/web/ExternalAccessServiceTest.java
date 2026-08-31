/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.runtime.ChatRequest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO platform_memberships(org_id,user_id,role,status) VALUES (?,?,'BUILDER','ACTIVE')")) {
                insertMembership(statement, "org_a", "user_a");
                insertMembership(statement, "org_b", "user_b");
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
        return new PlatformAuthService.Principal(
                userId, userId + "@example.com", userId, orgId, "BUILDER");
    }
}
