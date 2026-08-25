/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PipelineStepOutputTest {

    @Test
    void parsesStructuredStatusAndPassesContentForward() {
        PipelineStepOutput output =
                PipelineStepOutput.parse(
                        "{\"status\":\"needs_review\",\"content\":\"please review\"}");

        assertEquals("needs_review", output.status());
        assertEquals("please review", output.content());
    }

    @Test
    void supportsJsonWrappedInMarkdownFenceOrExtraText() {
        PipelineStepOutput output =
                PipelineStepOutput.parse(
                        "Result:\n```json\n{\"status\": \"complete\", \"content\": \"done\"}\n```");

        assertEquals("complete", output.status());
        assertEquals("done", output.content());
    }

    @Test
    void keepsLegacyMarkerAsCompatibilityFallback() {
        PipelineStepOutput output =
                PipelineStepOutput.parse("answer [workflow_status: needs_review]");

        assertEquals("needs_review", output.status());
        assertEquals("answer [workflow_status: needs_review]", output.content());
    }

    @Test
    void plainTextHasNoBranchStatus() {
        PipelineStepOutput output = PipelineStepOutput.parse("ordinary answer");

        assertEquals("", output.status());
        assertEquals("ordinary answer", output.content());
    }
}
