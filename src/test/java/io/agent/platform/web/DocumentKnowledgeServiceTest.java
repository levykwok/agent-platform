/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentKnowledgeServiceTest {

    @Test
    void addsLanguageAndSummaryPolicyWhenDocumentContextExists() {
        DocumentKnowledgeService.Retrieval retrieval =
                new DocumentKnowledgeService.Retrieval("片段内容", List.of(Map.of()));

        String prompt = DocumentKnowledgeService.withContext("总结这份文档", retrieval);

        assertTrue(prompt.contains("用户用中文提问时"));
        assertTrue(prompt.contains("不要自行改写为发布简报"));
        assertTrue(prompt.contains("片段内容"));
    }

    @Test
    void leavesQuestionUnchangedWithoutDocumentContext() {
        assertEquals("总结这份文档", DocumentKnowledgeService.withContext("总结这份文档", null));
    }
}
