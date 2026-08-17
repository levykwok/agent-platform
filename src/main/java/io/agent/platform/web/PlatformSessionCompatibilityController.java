/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/platform/session")
public class PlatformSessionCompatibilityController {

    private final PlatformCompatibilityState state;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final PlatformAuthService auth;

    public PlatformSessionCompatibilityController(
            PlatformCompatibilityState state,
            DocumentKnowledgeService documentKnowledgeService,
            PlatformAuthService auth) {
        this.state = state;
        this.documentKnowledgeService = documentKnowledgeService;
        this.auth = auth;
    }

    @GetMapping("/sessions/{sessionId}/attachments")
    public Map<String, Object> attachments(
            @PathVariable("sessionId") String sessionId, ServerHttpRequest request) {
        var current = requirePrincipal(request);
        return map("items", state.attachments(sessionId, current.userId(), current.orgId()));
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadAttachment(
            @RequestPart("file") FilePart file,
            @RequestPart(value = "session_id", required = false) String sessionPart,
            @RequestParam(value = "session_id", required = false) String sessionQuery,
            @RequestPart(value = "domain", required = false) String domainPart,
            @RequestParam(value = "domain", required = false) String domainQuery,
            ServerHttpRequest request) {
        return uploadConversationArtifact(
                file,
                sessionPart,
                sessionQuery,
                domainPart,
                domainQuery,
                "",
                "",
                request,
                "conversation_attachment");
    }

    @PostMapping(
            value = {"/generated-artifacts", "/artifacts"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadGeneratedArtifact(
            @RequestPart("file") FilePart file,
            @RequestPart(value = "session_id", required = false) String sessionPart,
            @RequestParam(value = "session_id", required = false) String sessionQuery,
            @RequestPart(value = "domain", required = false) String domainPart,
            @RequestParam(value = "domain", required = false) String domainQuery,
            @RequestPart(value = "message_id", required = false) String messagePart,
            @RequestParam(value = "message_id", required = false) String messageQuery,
            ServerHttpRequest request) {
        return uploadConversationArtifact(
                file,
                sessionPart,
                sessionQuery,
                domainPart,
                domainQuery,
                messagePart,
                messageQuery,
                request,
                "conversation_generated");
    }

    private Mono<Map<String, Object>> uploadConversationArtifact(
            FilePart file,
            String sessionPart,
            String sessionQuery,
            String domainPart,
            String domainQuery,
            String messagePart,
            String messageQuery,
            ServerHttpRequest request,
            String sourceType) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        String session_id = firstNonBlank(sessionPart, sessionQuery);
        String domain = firstNonBlank(domainPart, domainQuery, "platform");
        String messageId = firstNonBlank(messagePart, messageQuery);
        if (session_id.isBlank()) {
            return Mono.error(new IllegalArgumentException("session_id is required"));
        }
        String orgId = current.orgId();
        if (!state.sessionOwnedBy(session_id, orgId, current.userId())) {
            return Mono.error(new PlatformAuthService.AuthException(404, "会话不存在或当前账号无权访问"));
        }
        if (!documentKnowledgeService.supports(file.filename())) {
            return Mono.error(
                    new IllegalArgumentException(
                            "附件仅支持"
                                + " PDF、Office、Markdown、TXT、CSV（pdf/doc/docx/xls/xlsx/ppt/pptx/md/txt/csv）。"));
        }
        String docId = documentKnowledgeService.newDocumentId();
        var target = documentKnowledgeService.uploadTarget(docId, file.filename());
        return file.transferTo(target)
                .then(
                        Mono.fromCallable(
                                        () -> {
                                            Map<String, Object> document =
                                                    documentKnowledgeService.findReusable(
                                                            target, current, sourceType);
                                            boolean deduplicated = !document.isEmpty();
                                            if (document.isEmpty()) {
                                                document =
                                                        documentKnowledgeService.ingest(
                                                                docId,
                                                                target,
                                                                file.filename(),
                                                                domain,
                                                                current,
                                                                sourceType,
                                                                session_id,
                                                                messageId);
                                            } else {
                                                documentKnowledgeService.discardUpload(docId);
                                            }
                                            Map<String, Object> relationDocument =
                                                    new LinkedHashMap<>(document);
                                            relationDocument.put("source_session_id", session_id);
                                            if (!messageId.isBlank()) {
                                                relationDocument.put("source_message_id", messageId);
                                            }
                                            Map<String, Object> item =
                                                    state.attachDocument(
                                                    session_id,
                                                    relationDocument,
                                                    orgId,
                                                    current.userId());
                                            item = new LinkedHashMap<>(item);
                                            item.put("deduplicated", deduplicated);
                                            return item;
                                        })
                                .subscribeOn(Schedulers.boundedElastic()))
                .map(item -> map("item", item, "attachment_id", item.get("attachment_id")));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    @GetMapping("/attachments/{attachmentId}/status")
    public Map<String, Object> attachmentStatus(
            @PathVariable("attachmentId") String attachmentId, ServerHttpRequest request) {
        var current = requirePrincipal(request);
        return map("item", state.attachment(attachmentId, current.userId(), current.orgId()));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public Map<String, Object> deleteAttachment(
            @PathVariable("attachmentId") String attachmentId, ServerHttpRequest request) {
        var current = requirePrincipal(request);
        Map<String, Object> item =
                state.attachment(attachmentId, current.userId(), current.orgId());
        if (!"not_found".equals(item.get("status"))) {
            state.deleteAttachment(attachmentId);
        }
        return map(
                "ok",
                true,
                "attachment_id",
                attachmentId,
                "document_retained",
                true);
    }

    private PlatformAuthService.Principal requirePrincipal(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst("platform_session");
        PlatformAuthService.Principal current =
                auth.current(cookie == null ? "" : cookie.getValue());
        if (current == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录");
        }
        return current;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
