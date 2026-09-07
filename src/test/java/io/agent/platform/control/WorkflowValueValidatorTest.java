/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowValueValidatorTest {

    private final WorkflowValueValidator validator = new WorkflowValueValidator();

    @Test
    void enforcesNestedObjectEnumsRangesPatternsAndAdditionalProperties() {
        Map<String, Object> schema =
                Map.of(
                        "type", "object",
                        "required", List.of("status", "count"),
                        "additionalProperties", false,
                        "properties", Map.of(
                                "status", Map.of("type", "string", "enum", List.of("ok", "failed"), "pattern", "^[a-z]+$"),
                                "count", Map.of("type", "integer", "minimum", 1, "maximum", 5),
                                "tags", Map.of("type", "array", "minItems", 1, "maxItems", 2, "items", Map.of("type", "string", "minLength", 2))));
        WorkflowPort port =
                new WorkflowPort("value", "input", "contract", schema, true, "one", "");

        assertTrue(
                validator.validate(
                                port,
                                ContractValue.of(
                                        "contract",
                                        Map.of("status", "ok", "count", 3, "tags", List.of("ab"))))
                        .valid());
        WorkflowValueValidationResult invalid =
                validator.validate(
                        port,
                        ContractValue.of(
                                "contract",
                                Map.of(
                                        "status", "UNKNOWN",
                                        "count", 9,
                                        "tags", List.of("x", "ok", "extra"),
                                        "secret", true)));
        assertFalse(invalid.valid());
        assertTrue(invalid.errors().size() >= 5, () -> invalid.errors().toString());
    }
}
