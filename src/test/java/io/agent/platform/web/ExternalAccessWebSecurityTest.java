/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.reactive.server.WebTestClient;

class ExternalAccessWebSecurityTest {

    @TempDir Path tempDir;

    private PlatformStorageLayer storage;
    private ExternalAccessService service;
    private PlatformAuthService.Principal ownerA;
    private PlatformAuthService.Principal ownerB;
    private PlatformAuthService.Principal admin;

    @BeforeEach
    void setUp() throws Exception {
        storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("external-web-security.db"),
                        "platform_config",
                        "platform_",
                        "");
        initializeAccounts(storage);
        service = new ExternalAccessService(storage);
        ownerA = principal("user_a", "org_a", "BUILDER");
        ownerB = principal("user_b", "org_b", "BUILDER");
        admin = principal("admin", "platform", "PLATFORM_ADMIN");
    }

    @Test
    void externalFilterAuditsAuthenticationAuthorizationAndRateLimitRejections() {
        Map<String, Object> created =
                service.createKey(
                        ownerA,
                        "Restricted web client",
                        "",
                        List.of("agent_allowed"),
                        List.of("chat"),
                        1);
        String secret = String.valueOf(created.get("secret"));
        WebTestClient client =
                WebTestClient.bindToController(new ExternalProbeController())
                        .webFilter(new ExternalApiKeyWebFilter("true", "", service))
                        .configureClient()
                        .responseTimeout(Duration.ofSeconds(30))
                        .build();

        client.get()
                .uri("/api/v1/agents")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("invalid_api_key");
        client.post()
                .uri("/api/v1/agents/agent_denied/chat")
                .header("X-API-Key", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "denied"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("agent_not_allowed");
        client.post()
                .uri("/api/v1/agents/agent_allowed/chat/stream")
                .header("Authorization", "Bearer " + secret)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "denied"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("capability_not_allowed");
        client.post()
                .uri("/api/v1/agents/agent_allowed/chat")
                .header("X-API-Key", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "allowed"))
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("/api/v1/agents/agent_allowed/chat")
                .header("X-API-Key", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "limited"))
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .exists("Retry-After")
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("rate_limit_exceeded");

        List<Map<String, Object>> ownerAudit = service.invocationHistory(ownerA, 20);
        assertEquals(3, ownerAudit.size());
        assertEquals(3, ownerAudit.stream().filter(row -> "REJECTED".equals(row.get("status"))).count());
        assertFalse(ownerAudit.toString().contains(secret));
        Map<String, Object> overview = service.adminOverview(admin, 20);
        assertEquals(4, ((Map<?, ?>) overview.get("invocations")).get("rejected"));
        assertFalse(overview.toString().contains(secret));
    }

    @Test
    void managementEndpointsEnforceOwnerAndAdministratorBoundaries() {
        Map<String, Object> keyA = service.createKey(ownerA, "Owner A", "");
        Map<String, Object> keyB = service.createKey(ownerB, "Owner B", "");
        PlatformAuthService auth = mock(PlatformAuthService.class);
        when(auth.current(anyString()))
                .thenAnswer(
                        invocation -> {
                            Object session = invocation.getArgument(0);
                            return switch (String.valueOf(session)) {
                                    case "owner-a-session" -> ownerA;
                                    case "owner-b-session" -> ownerB;
                                    case "admin-session" -> admin;
                                    default -> null;
                                };
                        });
        doAnswer(
                        invocation -> {
                            PlatformAuthService.Principal principal = invocation.getArgument(0);
                            if (principal == null || !"PLATFORM_ADMIN".equals(principal.role())) {
                                throw new PlatformAuthService.AuthException(
                                        principal == null ? 401 : 403,
                                        "需要平台管理员权限");
                            }
                            return null;
                        })
                .when(auth)
                .requireAdmin(any());
        WebTestClient client =
                WebTestClient.bindToController(
                                new ExternalAccessManagementController(auth, service))
                        .controllerAdvice(new PlatformAuthExceptionHandler())
                        .configureClient()
                        .responseTimeout(Duration.ofSeconds(30))
                        .build();

        client.get()
                .uri("/platform/frontend/external-api/admin/keys")
                .cookie("platform_session", "owner-a-session")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri(
                        builder ->
                                builder.path("/platform/frontend/external-api/admin/keys")
                                        .queryParam("query", "b@example.com")
                                        .queryParam("status", "ACTIVE")
                                        .build())
                .cookie("platform_session", "admin-session")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.items[0].owner_user_id")
                .isEqualTo("user_b")
                .jsonPath("$.items[0].secret")
                .doesNotExist();
        client.post()
                .uri("/platform/frontend/external-api/keys/{keyId}/revoke", keyB.get("key_id"))
                .cookie("platform_session", "owner-a-session")
                .exchange()
                .expectStatus()
                .isNotFound();
        client.post()
                .uri("/platform/frontend/external-api/keys/{keyId}/rotate", keyA.get("key_id"))
                .cookie("platform_session", "owner-a-session")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("grace_minutes", 15))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.item.secret")
                .exists()
                .jsonPath("$.item.previous_key_id")
                .isEqualTo(keyA.get("key_id"));
        client.post()
                .uri("/platform/frontend/external-api/admin/keys/{keyId}/suspend", keyB.get("key_id"))
                .cookie("platform_session", "admin-session")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("SUSPENDED");
        assertNull(service.authenticate(String.valueOf(keyB.get("secret"))));
        client.post()
                .uri("/platform/frontend/external-api/admin/keys/{keyId}/resume", keyB.get("key_id"))
                .cookie("platform_session", "admin-session")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("ACTIVE");
        client.post()
                .uri("/platform/frontend/external-api/admin/keys/{keyId}/revoke", keyB.get("key_id"))
                .cookie("platform_session", "admin-session")
                .exchange()
                .expectStatus()
                .isOk();
        assertNull(service.authenticate(String.valueOf(keyB.get("secret"))));
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
                            "INSERT INTO platform_memberships(org_id,user_id,role,status) VALUES (?,?,?,'ACTIVE')")) {
                insertMembership(statement, "org_a", "user_a", "BUILDER");
                insertMembership(statement, "org_b", "user_b", "BUILDER");
                insertMembership(statement, "platform", "admin", "PLATFORM_ADMIN");
            }
        }
    }

    private static void insertUser(
            PreparedStatement statement, String userId, String email, String name)
            throws Exception {
        statement.setString(1, userId);
        statement.setString(2, email);
        statement.setString(3, name);
        statement.executeUpdate();
    }

    private static void insertMembership(
            PreparedStatement statement, String orgId, String userId, String role)
            throws Exception {
        statement.setString(1, orgId);
        statement.setString(2, userId);
        statement.setString(3, role);
        statement.executeUpdate();
    }

    private static PlatformAuthService.Principal principal(
            String userId, String orgId, String role) {
        return new PlatformAuthService.Principal(
                userId, userId + "@example.com", userId, orgId, role);
    }

    @RestController
    @RequestMapping("/api/v1")
    static class ExternalProbeController {
        @GetMapping("/agents")
        Map<String, Object> agents() {
            return Map.of("items", List.of());
        }

        @PostMapping("/agents/{agentId}/chat")
        Map<String, Object> chat(@PathVariable("agentId") String agentId) {
            return Map.of("agent_id", agentId, "ok", true);
        }

        @PostMapping("/agents/{agentId}/chat/stream")
        Map<String, Object> stream(@PathVariable("agentId") String agentId) {
            return Map.of("agent_id", agentId, "ok", true);
        }
    }
}
