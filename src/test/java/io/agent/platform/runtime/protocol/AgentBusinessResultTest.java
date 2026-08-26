/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentBusinessResultTest {

    @Test
    void legacyTextIsNormalizedToStableBusinessContract() {
        AgentBusinessResult result = AgentBusinessResult.fromText("plain answer");

        assertEquals("succeeded", result.status());
        assertEquals("plain answer", result.summary());
        assertEquals("plain answer", ((Map<?, ?>) result.data()).get("text"));
        assertTrue(result.artifacts().isEmpty());
    }

    @Test
    void structuredJsonPreservesDataSummaryAndArtifacts() {
        AgentBusinessResult result =
                AgentBusinessResult.fromText(
                        "{\"status\":\"partial\",\"data\":{\"answer\":42},"
                                + "\"summary\":\"found it\",\"artifacts\":[{\"name\":\"report.md\"}],"
                                + "\"error\":null}");

        assertEquals("partial", result.status());
        assertEquals(42, ((Map<?, ?>) result.data()).get("answer"));
        assertEquals("report.md", result.artifacts().get(0).get("name"));
        assertEquals(AgentBusinessResult.VERSION, result.contract().get("contract_version"));
        assertEquals(result, AgentBusinessResult.fromContract(result.contract()));
    }

    @Test
    void validatesConfiguredDataSchema() {
        Map<String, Object> schema =
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("answer"),
                        "properties",
                        Map.of("answer", Map.of("type", "string")));

        assertTrue(
                AgentResultSchemaValidator.validate(Map.of("answer", "ok"), schema).isEmpty());
        assertFalse(AgentResultSchemaValidator.validate(Map.of("answer", 42), schema).isEmpty());
        assertFalse(AgentResultSchemaValidator.validate(Map.of(), schema).isEmpty());
    }
}
