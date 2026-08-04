/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRequestTest {

    @Test
    void recognizesImageInputs() {
        ChatRequest request =
                new ChatRequest(
                        "tenant",
                        "user",
                        "session",
                        "describe it",
                        null,
                        List.of(new ChatImage("https://example.test/image.png", "", "image/png")));

        assertTrue(request.hasImages());
        assertFalse(new ChatRequest("tenant", "user", "session", "text only").hasImages());
    }

    @Test
    void imageRequiresExactlyOneSourceAndImageMediaType() {
        assertThrows(IllegalArgumentException.class, () -> new ChatImage("", "", "image/png"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatImage("https://example.test/a.png", "data", "image/png"));
        assertThrows(
                IllegalArgumentException.class, () -> new ChatImage("", "data", "application/pdf"));
    }
}
