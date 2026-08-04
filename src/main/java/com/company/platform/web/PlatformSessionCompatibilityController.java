/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/platform/session")
public class PlatformSessionCompatibilityController {

    private final PlatformCompatibilityState state;
    private final DocumentKnowledgeService documentKnowledgeService;

    public PlatformSessionCompatibilityController(
            PlatformCompatibilityState state, DocumentKnowledgeService documentKnowledgeService) {
        this.state = state;
        this.documentKnowledgeService = documentKnowledgeService;
    }

    @GetMapping("/sessions/{sessionId}/attachments")
    public Map<String, Object> attachments(@PathVariable String sessionId) {
        return map("items", state.attachments(sessionId));
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadAttachment(
            @RequestPart("file") FilePart file,
            @RequestParam String session_id,
            @RequestParam(defaultValue = "platform") String domain,
            @RequestHeader(value = "x-org-id", defaultValue = "platform") String orgId) {
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
                                                    documentKnowledgeService.ingest(
                                                            docId,
                                                            target,
                                                            file.filename(),
                                                            domain,
                                                            orgId);
                                            return state.attachDocument(
                                                    session_id, document, orgId);
                                        })
                                .subscribeOn(Schedulers.boundedElastic()))
                .map(item -> map("item", item, "attachment_id", item.get("attachment_id")));
    }

    @GetMapping("/attachments/{attachmentId}/status")
    public Map<String, Object> attachmentStatus(@PathVariable String attachmentId) {
        return map("item", state.attachment(attachmentId));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public Map<String, Object> deleteAttachment(@PathVariable String attachmentId) {
        state.deleteAttachment(attachmentId);
        return map("ok", true, "attachment_id", attachmentId);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
