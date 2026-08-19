/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeToolGovernanceTest {

    @TempDir Path temp;

    @Test
    void globalAndAgentPoliciesArePersistedAndEnforced() {
        RuntimeToolGovernance first = governance();
        first.load();
        assertTrue(first.isAllowed("writer", "web_fetch", true));

        first.saveGlobal("web_fetch", Map.of("binding_status", "disabled"));
        first.saveAgent("writer", "write_file", Map.of("runtime_enabled", false));

        RuntimeToolGovernance reloaded = governance();
        reloaded.load();
        assertFalse(reloaded.isAllowed("writer", "web_fetch", true));
        assertFalse(reloaded.isAllowed("writer", "write_file", true));
        assertTrue(reloaded.isAllowed("researcher", "write_file", true));
    }

    @Test
    void disabledMcpCatalogIdAlsoDeniesRuntimeSchemaAlias() {
        RuntimeToolGovernance policies = governance();
        policies.load();
        policies.saveGlobal(
                "mcp:demo-search:fetch_url", Map.of("binding_status", "disabled"));

        assertFalse(policies.isAllowed("researcher", "fetch_url", true));
    }

    @Test
    void agentMcpAliasPolicyDoesNotAffectAnotherAgent() {
        RuntimeToolGovernance policies = governance();
        policies.load();
        policies.saveAgent(
                "researcher",
                "mcp:demo-search:fetch_url",
                Map.of("binding_status", "disabled"));

        assertFalse(policies.isAllowed("researcher", "fetch_url", true));
        assertTrue(policies.isAllowed("writer", "fetch_url", true));
    }

    @Test
    void browserPayloadMayContainNullableOptionalFields() {
        RuntimeToolGovernance policies = governance();
        policies.load();
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("domain", null);
        payload.put("binding_status", "enabled");

        Map<String, Object> saved = policies.saveGlobal("web_fetch", payload);

        assertTrue(Boolean.TRUE.equals(saved.get("runtime_enabled")));
        assertFalse(saved.containsKey("domain"));
    }

    private RuntimeToolGovernance governance() {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        temp.toString(), "file", "", "platform_config", "platform_", "");
        return new RuntimeToolGovernance(storage);
    }
}
