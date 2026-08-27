/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpCookie;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.reactive.server.WebTestClient;

class PlatformAccountControllerTest {

    @TempDir Path tempDir;

    @Test
    void publicAndAdminControllersEnforceTheCompleteAccountLifecycle() {
        PlatformAuthService auth = auth(tempDir);
        WebTestClient client =
                WebTestClient.bindToController(
                                new PlatformAuthController(auth, false),
                                new PlatformAdminAccountController(auth))
                        .webFilter(new PlatformCsrfWebFilter("http://trusted.example"))
                        .build();

        client.get()
                .uri("/platform/admin/accounts/users")
                .exchange()
                .expectStatus()
                .isUnauthorized();

        Map<?, ?> application =
                client.post()
                        .uri("/platform/auth/apply")
                        .bodyValue(
                                Map.of(
                                        "email", "controller@example.com",
                                        "display_name", "Controller User",
                                        "project", "Controller Team"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(application);
        String applicationId = String.valueOf(application.get("application_id"));

        HttpCookie adminCookie = login(client, "admin@example.com", "AdminPassword123!");
        client.post()
                .uri("/platform/admin/accounts/organizations")
                .cookie(adminCookie.getName(), adminCookie.getValue())
                .header("Origin", "https://attacker.example")
                .bodyValue(Map.of("name", "Rejected Organization"))
                .exchange()
                .expectStatus()
                .isForbidden();
        Map<?, ?> organization =
                client.post()
                        .uri("/platform/admin/accounts/organizations")
                        .cookie(adminCookie.getName(), adminCookie.getValue())
                        .header("Origin", "http://trusted.example")
                        .bodyValue(Map.of("name", "Controller Organization"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(organization);
        client.patch()
                .uri(
                        "/platform/admin/accounts/organizations/"
                                + organization.get("org_id"))
                .cookie(adminCookie.getName(), adminCookie.getValue())
                .header("Sec-Fetch-Site", "cross-site")
                .bodyValue(Map.of("name", "Cross Site Rename"))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/platform/admin/accounts/applications?status=PENDING&limit=20")
                .cookie(adminCookie.getName(), adminCookie.getValue())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);

        Map<?, ?> approved =
                client.post()
                        .uri(
                                "/platform/admin/accounts/applications/"
                                        + applicationId
                                        + "/approve")
                        .cookie(adminCookie.getName(), adminCookie.getValue())
                        .bodyValue(Map.of("role", "BUILDER", "organization", "Controller Team"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(approved);
        String userId = String.valueOf(approved.get("user_id"));
        String setupUrl = String.valueOf(approved.get("setup_url"));
        assertFalse(setupUrl.isBlank());
        String token = setupUrl.substring(setupUrl.indexOf("token=") + 6);

        HttpCookie userCookie =
                client.post()
                        .uri("/platform/auth/setup-password")
                        .bodyValue(
                                Map.of(
                                        "token", token,
                                        "password", "ControllerPassword123!",
                                        "display_name", "Controller User"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectCookie()
                        .exists("platform_session")
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseCookies()
                        .getFirst("platform_session");
        assertNotNull(userCookie);

        client.get()
                .uri("/platform/admin/accounts/users")
                .cookie(userCookie.getName(), userCookie.getValue())
                .exchange()
                .expectStatus()
                .isForbidden();

        client.patch()
                .uri("/platform/admin/accounts/users/" + userId)
                .cookie(adminCookie.getName(), adminCookie.getValue())
                .bodyValue(Map.of("display_name", "Updated User", "status", "ACTIVE"))
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("/platform/admin/accounts/users/" + userId + "/revoke-sessions")
                .cookie(adminCookie.getName(), adminCookie.getValue())
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/platform/auth/me")
                .cookie(userCookie.getName(), userCookie.getValue())
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    private HttpCookie login(WebTestClient client, String email, String password) {
        HttpCookie cookie =
                client.post()
                        .uri("/platform/auth/login")
                        .bodyValue(Map.of("email", email, "password", password))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectCookie()
                        .exists("platform_session")
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseCookies()
                        .getFirst("platform_session");
        assertNotNull(cookie);
        return cookie;
    }

    private PlatformAuthService auth(Path workspace) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        workspace.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + workspace.resolve("controller-auth.db"),
                        "platform_config",
                        "platform_",
                        "");
        return new PlatformAuthService(
                storage,
                provider,
                "admin@example.com",
                "AdminPassword123!",
                "http://localhost:8080",
                false,
                false);
    }
}
