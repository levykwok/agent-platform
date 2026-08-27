/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentAssetServiceTest {

    @TempDir Path tempDir;

    @Test
    void privateAgentIsIsolatedWhileLegacyAgentIsPublic() {
        AgentDefinitionRegistry registry = mock(AgentDefinitionRegistry.class);
        AgentDefinition legacy = mock(AgentDefinition.class);
        when(legacy.agentId()).thenReturn("legacy");
        when(registry.allPublished()).thenReturn(List.of(legacy));
        when(registry.findPublished("legacy")).thenReturn(Optional.of(legacy));
        when(registry.findPublished("private_agent")).thenReturn(Optional.of(mock(AgentDefinition.class)));
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("agent-assets.db"),
                        "platform_config",
                        "platform_",
                        "");
        AgentAssetService assets = new AgentAssetService(storage, registry);
        PlatformAuthService.Principal owner =
                new PlatformAuthService.Principal("user_a", "a@example.com", "A", "org_a", "BUILDER");
        PlatformAuthService.Principal other =
                new PlatformAuthService.Principal("user_b", "b@example.com", "B", "org_b", "BUILDER");
        PlatformAuthService.Principal viewer =
                new PlatformAuthService.Principal("user_v", "v@example.com", "V", "org_a", "VIEWER");

        assets.registerNew("private_agent", owner, Map.of());
        assertEquals("PRIVATE", assets.metadata("private_agent").visibility());
        assertEquals("legacy", assets.filterRows(List.of(Map.of("agent_id", "legacy")), null).get(0).get("agent_id"));
        assertEquals(1, assets.filterRows(List.of(Map.of("agent_id", "private_agent")), owner).size());
        assertEquals(0, assets.filterRows(List.of(Map.of("agent_id", "private_agent")), other).size());
        assertThrows(PlatformAuthService.AuthException.class, () -> assets.requireReadable("private_agent", other));
        assertDoesNotThrow(() -> assets.requireWritable("private_agent", owner));
        assertThrows(PlatformAuthService.AuthException.class, () -> assets.requireWritable("private_agent", other));
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> assets.registerNew("viewer_agent", viewer, Map.of()));
        assertThrows(
                PlatformAuthService.AuthException.class,
                () -> assets.requireWritable("private_agent", viewer));
    }
}
