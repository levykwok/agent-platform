/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

class PlatformConfigStorePipelineMigrationTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void legacyWorkflowSchemaMigratesWithoutDroppingUnknownFields() throws Exception {
        JsonNode root =
                yaml.readTree(
                        "agents:\n"
                                + "  - agentId: legacy-pipeline\n"
                                + "    custom_extension: keep-me\n"
                                + "    orchestration:\n"
                                + "      mode: WORKFLOW\n"
                                + "      custom_policy: keep-too\n"
                                + "      workflow:\n"
                                + "        - stepId: first\n"
                                + "          agentId: worker\n");

        assertTrue(PlatformConfigStore.migrateLegacyAgentPipelineTree(root));

        JsonNode agent = root.path("agents").get(0);
        JsonNode orchestration = agent.path("orchestration");
        assertEquals("PIPELINE", orchestration.path("mode").asText());
        assertTrue(orchestration.path("pipeline").isArray());
        assertFalse(orchestration.has("workflow"));
        assertEquals("keep-me", agent.path("custom_extension").asText());
        assertEquals("keep-too", orchestration.path("custom_policy").asText());
        assertFalse(PlatformConfigStore.migrateLegacyAgentPipelineTree(root));
    }
}
