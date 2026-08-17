/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agent.platform.control.PlatformArtifactStore;
import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentKnowledgeServiceTest {

    @TempDir Path tempDir;

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

    @Test
    void explicitUnauthorizedDocumentScopeDoesNotFallBackToAllReadableDocuments() throws Exception {
        DocumentKnowledgeService service = service();
        PlatformAuthService.Principal alice = principal("user-a", "org-a");
        PlatformAuthService.Principal bob = principal("user-b", "org-b");
        String docId = service.newDocumentId();
        Path file = service.uploadTarget(docId, "private.md");
        Files.writeString(file, "alice-private-secret");
        service.ingest(docId, file, "private.md", "platform", alice, "conversation_attachment", "session-a");

        RetrievalAssertions.assertEmpty(
                service.retrieveScoped(
                        "alice-private-secret",
                        List.of(new DocumentKnowledgeService.RequestedDocument(docId, "v1")),
                        4,
                        bob));
        RetrievalAssertions.assertEmpty(
                service.retrieveScoped(
                        "alice-private-secret",
                        List.of(new DocumentKnowledgeService.RequestedDocument(docId, "v2")),
                        4,
                        alice));
        assertFalse(
                service.retrieveScoped(
                                "alice-private-secret",
                                List.of(new DocumentKnowledgeService.RequestedDocument(docId, "v1")),
                                4,
                                alice)
                        .citations()
                        .isEmpty());
    }

    @Test
    void sameOwnedConversationContentCanBeReused() throws Exception {
        DocumentKnowledgeService service = service();
        PlatformAuthService.Principal alice = principal("user-a", "org-a");
        String docId = service.newDocumentId();
        Path first = service.uploadTarget(docId, "first.md");
        Files.writeString(first, "same content");
        service.ingest(docId, first, "first.md", "platform", alice, "conversation_attachment", "session-a");

        Path second = tempDir.resolve("second.md");
        Files.writeString(second, "same content");
        Map<String, Object> reused = service.findReusable(second, alice, "conversation_attachment");

        assertEquals(docId, reused.get("doc_id"));
        assertTrue(String.valueOf(reused.get("content_sha256")).length() == 64);
    }

    private DocumentKnowledgeService service() {
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + tempDir.resolve("knowledge-test.db"),
                        "platform_config",
                        "platform_",
                        "");
        QdrantKnowledgeIndex qdrant = mock(QdrantKnowledgeIndex.class);
        when(qdrant.upsert(any(), anyList()))
                .thenReturn(new QdrantKnowledgeIndex.IndexResult("skipped", 0, "test"));
        when(qdrant.search(anyString(), anyList(), anyInt()))
                .thenReturn(new QdrantKnowledgeIndex.SearchResult(false, List.of(), "test"));
        return new DocumentKnowledgeService(
                storage,
                new DocumentExtractionService(),
                qdrant,
                new PlatformArtifactStore(storage));
    }

    private static PlatformAuthService.Principal principal(String userId, String orgId) {
        return new PlatformAuthService.Principal(
                userId, userId + "@example.com", userId, orgId, "BUILDER");
    }

    private static final class RetrievalAssertions {
        private static void assertEmpty(DocumentKnowledgeService.Retrieval retrieval) {
            assertTrue(retrieval.context().isBlank());
            assertTrue(retrieval.citations().isEmpty());
        }
    }
}
