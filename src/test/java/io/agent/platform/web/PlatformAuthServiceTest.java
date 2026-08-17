/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Path;
import java.util.Map;
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
}
