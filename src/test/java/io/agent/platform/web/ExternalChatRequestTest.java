/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalChatRequestTest {

    @Test
    void convertsImagesAndTextFilesToRuntimeRequest() {
        ExternalChatRequest request =
                ExternalChatRequest.from(
                        Map.of(
                                "tenant_id", "tenant-a",
                                "user_id", "user-a",
                                "session_id", "session-a",
                                "message", "Summarize the inputs",
                                "images",
                                        List.of(
                                                Map.of(
                                                        "data", "aGVsbG8=",
                                                        "media_type", "image/png")),
                                "files",
                                        List.of(
                                                Map.of(
                                                        "name", "notes.md",
                                                        "media_type", "text/markdown",
                                                        "content", "# Notes\nImportant context"))));

        var runtime = request.toRuntimeRequest();

        assertEquals("tenant-a", runtime.tenantId());
        assertEquals(1, runtime.images().size());
        assertTrue(runtime.message().startsWith("Summarize the inputs"));
        assertTrue(runtime.message().contains("File: notes.md (text/markdown)"));
        assertTrue(runtime.message().contains("Important context"));
    }

    @Test
    void rejectsBinaryInlineFiles() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ExternalChatRequest.from(
                                        Map.of(
                                                "message", "Read this",
                                                "files",
                                                        List.of(
                                                                Map.of(
                                                                        "name", "report.pdf",
                                                                        "media_type", "application/pdf",
                                                                        "content", "binary")))));

        assertTrue(error.getMessage().contains("must be textual"));
    }

    @Test
    void rejectsOversizedInlineFiles() {
        String oversized = "x".repeat(200_001);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ExternalChatRequest.from(
                                        Map.of(
                                                "message", "Read this",
                                                "files",
                                                        List.of(
                                                                Map.of(
                                                                        "name", "large.txt",
                                                                        "media_type", "text/plain",
                                                                        "content", oversized)))));

        assertTrue(error.getMessage().contains("exceeds 200000 characters"));
    }
}
