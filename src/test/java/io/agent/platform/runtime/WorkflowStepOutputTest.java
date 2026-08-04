/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkflowStepOutputTest {

    @Test
    void parsesStructuredStatusAndPassesContentForward() {
        WorkflowStepOutput output =
                WorkflowStepOutput.parse(
                        "{\"status\":\"needs_review\",\"content\":\"please review\"}");

        assertEquals("needs_review", output.status());
        assertEquals("please review", output.content());
    }

    @Test
    void supportsJsonWrappedInMarkdownFenceOrExtraText() {
        WorkflowStepOutput output =
                WorkflowStepOutput.parse(
                        "Result:\n```json\n{\"status\": \"complete\", \"content\": \"done\"}\n```");

        assertEquals("complete", output.status());
        assertEquals("done", output.content());
    }

    @Test
    void keepsLegacyMarkerAsCompatibilityFallback() {
        WorkflowStepOutput output =
                WorkflowStepOutput.parse("answer [workflow_status: needs_review]");

        assertEquals("needs_review", output.status());
        assertEquals("answer [workflow_status: needs_review]", output.content());
    }

    @Test
    void plainTextHasNoBranchStatus() {
        WorkflowStepOutput output = WorkflowStepOutput.parse("ordinary answer");

        assertEquals("", output.status());
        assertEquals("ordinary answer", output.content());
    }
}
