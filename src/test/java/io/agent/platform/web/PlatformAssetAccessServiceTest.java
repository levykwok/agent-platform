/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformAssetAccessServiceTest {

    @TempDir Path tempDir;

    @Test
    void legacyAssetsArePublicAndNewAssetsArePrivateByDefault() {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("asset-access.db"),
                        "platform_config",
                        "platform_",
                        "");
        PlatformAssetAccessService access = new PlatformAssetAccessService(storage);
        PlatformAuthService.Principal owner =
                new PlatformAuthService.Principal(
                        "user_a", "a@example.com", "A", "org_a", "BUILDER");
        PlatformAuthService.Principal other =
                new PlatformAuthService.Principal(
                        "user_b", "b@example.com", "B", "org_b", "BUILDER");

        access.ensurePublic("MCP", "legacy");
        access.registerNew("SKILL", "private_skill", owner, "PUBLIC", "PUBLISHED");

        assertEquals(1, access.filterRows("MCP", List.of(Map.of("id", "legacy")), "id", null).size());
        assertEquals(
                1,
                access.filterRows(
                                "SKILL",
                                List.of(Map.of("skill_id", "private_skill")),
                                "skill_id",
                                owner)
                        .size());
        assertEquals(
                0,
                access.filterRows(
                                "SKILL",
                                List.of(Map.of("skill_id", "private_skill")),
                                "skill_id",
                                other)
                        .size());
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> access.requireReadable("SKILL", "private_skill", other));
    }
}
