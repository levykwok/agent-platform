/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelSpecTest {

    @Test
    void normalizesCapabilitiesForPersistentModelDefinitions() {
        ModelSpec spec =
                new ModelSpec(
                        "vision-model",
                        "chat",
                        "provider",
                        "openai",
                        "gpt-vision",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "",
                        null,
                        null,
                        null,
                        null,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "",
                        "",
                        null,
                        "",
                        "",
                        null,
                        "",
                        true,
                        List.of(" Vision ", "vision", "tool_calling"));

        assertEquals(List.of("vision", "tool_calling"), spec.capabilities());
    }
}
