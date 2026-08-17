/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformUserCapabilityServiceTest {

    @TempDir Path tempDir;

    @Test
    void personalRemoteCapabilitiesPersistAndStayPrivate() {
        PlatformStorageLayer storage = storage();
        PlatformUserCapabilityService service =
                new PlatformUserCapabilityService(storage, new PlatformAssetAccessService(storage));
        PlatformAuthService.Principal owner =
                new PlatformAuthService.Principal(
                        "user_a", "a@example.com", "A", "org_a", "BUILDER");
        PlatformAuthService.Principal other =
                new PlatformAuthService.Principal(
                        "user_b", "b@example.com", "B", "org_b", "BUILDER");

        service.createMcp(
                Map.of("mcp_id", "private_mcp", "endpoint", "https://example.com/mcp"), owner);
        service.createTool(
                Map.of(
                        "tool_id", "private_tool",
                        "endpoint", "https://example.com/tool",
                        "method", "POST"),
                owner);
        service.createSkill(
                Map.of("skill_id", "private_skill", "description", "private", "content", "answer"),
                owner);

        assertEquals(1, service.mcps(owner).size());
        assertEquals(1, service.tools(owner).size());
        assertEquals(1, service.skills(owner).size());
        assertFalse(service.findMcp("private_mcp", other).isPresent());
        assertFalse(service.findTool("private_tool", other).isPresent());
        assertFalse(service.findSkill("private_skill", other).isPresent());
        assertThrows(
                PlatformAuthService.AuthException.class,
                () ->
                        service.createMcp(
                                Map.of(
                                        "mcp_id", "bad",
                                        "transport", "stdio",
                                        "command", "sh"),
                                owner));

        PlatformUserCapabilityService reloaded =
                new PlatformUserCapabilityService(storage, new PlatformAssetAccessService(storage));
        assertEquals(1, reloaded.mcps(owner).size());
        assertEquals(1, reloaded.tools(owner).size());
        assertEquals(1, reloaded.skills(owner).size());
    }

    private PlatformStorageLayer storage() {
        return new PlatformStorageLayer(
                tempDir.toString(),
                "sqlite",
                "jdbc:sqlite:" + tempDir.resolve("personal-capabilities.db"),
                "platform_config",
                "platform_",
                "");
    }
}
