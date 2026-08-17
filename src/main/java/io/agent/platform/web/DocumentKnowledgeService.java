/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.PlatformArtifactStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Stores uploaded office documents and provides the small, deterministic retrieval layer used by
 * the first knowledge-base release. Vector indexing can later replace the lexical scorer without
 * changing upload or chat APIs.
 */
@Component
public class DocumentKnowledgeService {

    private static final int MAX_CONTEXT_CHARS = 6_000;
    private static final int MAX_CHUNK_CHARS = 1_400;

    private final PlatformStorageLayer storage;
    private final DocumentExtractionService extractionService;
    private final QdrantKnowledgeIndex qdrantIndex;
    private final PlatformArtifactStore artifactStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> documents = new LinkedHashMap<>();

    public DocumentKnowledgeService(
            PlatformStorageLayer storage,
            DocumentExtractionService extractionService,
            QdrantKnowledgeIndex qdrantIndex,
            PlatformArtifactStore artifactStore) {
        this.storage = storage;
        this.extractionService = extractionService;
        this.qdrantIndex = qdrantIndex;
        this.artifactStore = artifactStore;
        loadCatalog();
    }

    public boolean supports(String filename) {
        return extractionService.supports(filename);
    }

    public String newDocumentId() {
        return "doc_" + UUID.randomUUID().toString().replace("-", "");
    }

    public Path uploadTarget(String docId, String filename) {
        String safeName = safeFilename(filename);
        Path directory =
                storage.ensureDirectory(storage.resolveWorkspace("documents", docId, "v1"));
        return directory.resolve(safeName).normalize();
    }

    /** Return an owned private document with the same bytes, if one already exists. */
    public synchronized Map<String, Object> findReusable(
            Path rawFile, PlatformAuthService.Principal principal, String sourceType) {
        if (rawFile == null || principal == null || !Files.isRegularFile(rawFile)) {
            return Map.of();
        }
        String hash = contentSha256(rawFile);
        if (hash.isBlank()) {
            return Map.of();
        }
        return documents.values().stream()
                .filter(document -> isOwnedPrivate(document, principal))
                .filter(
                        document ->
                                sourceType == null
                                        || sourceType.isBlank()
                                        || sourceType.equals(String.valueOf(document.get("source_type"))))
                .filter(document -> hash.equals(documentContentSha256(document)))
                .findFirst()
                .map(DocumentKnowledgeService::publicDocument)
                .orElseGet(Map::of);
    }

    public void discardUpload(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        deleteRecursively(storage.resolveWorkspace("documents", docId));
    }

    public synchronized Map<String, Object> ingest(
            String docId, Path rawFile, String filename, String domain, String orgId) {
        return ingestInternal(
                docId, rawFile, filename, domain, orgId, null, "PUBLIC", Map.of());
    }

    public synchronized Map<String, Object> ingest(
            String docId,
            Path rawFile,
            String filename,
            String domain,
            PlatformAuthService.Principal principal) {
        return ingest(docId, rawFile, filename, domain, principal, "", "");
    }

    /** Ingest a private document and retain where it came from for knowledge-base navigation. */
    public synchronized Map<String, Object> ingest(
            String docId,
            Path rawFile,
            String filename,
            String domain,
            PlatformAuthService.Principal principal,
            String sourceType,
            String sourceSessionId) {
        return ingest(docId, rawFile, filename, domain, principal, sourceType, sourceSessionId, "");
    }

    public synchronized Map<String, Object> ingest(
            String docId,
            Path rawFile,
            String filename,
            String domain,
            PlatformAuthService.Principal principal,
            String sourceType,
            String sourceSessionId,
            String sourceMessageId) {
        if (principal == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录后上传文档");
        }
        Map<String, Object> provenance = new LinkedHashMap<>();
        if (sourceType != null && !sourceType.isBlank()) {
            provenance.put("source_type", sourceType.strip());
        }
        if (sourceSessionId != null && !sourceSessionId.isBlank()) {
            provenance.put("source_session_id", sourceSessionId.strip());
        }
        if (sourceMessageId != null && !sourceMessageId.isBlank()) {
            provenance.put("source_message_id", sourceMessageId.strip());
        }
        return ingestInternal(
                docId,
                rawFile,
                filename,
                domain,
                principal.orgId(),
                principal.userId(),
                "PRIVATE",
                provenance);
    }

    private Map<String, Object> ingestInternal(
            String docId,
            Path rawFile,
            String filename,
            String domain,
            String orgId,
            String createdBy,
            String visibility,
            Map<String, Object> provenance) {
        if (!supports(filename)) {
            throw new IllegalArgumentException(
                    "仅支持 PDF、Office、Markdown、TXT、CSV（pdf/doc/docx/xls/xlsx/ppt/pptx/md/txt/csv）。");
        }
        DocumentExtractionService.Extraction extraction =
                extractionService.extract(rawFile, filename);
        String contentSha256 = contentSha256(rawFile);
        List<Map<String, Object>> chunks = new ArrayList<>();
        int ordinal = 1;
        for (String text : extraction.chunks()) {
            chunks.add(map("chunk_id", docId + "_" + ordinal, "ordinal", ordinal++, "text", text));
        }
        Map<String, Object> document =
                map(
                        "doc_id",
                        docId,
                        "id",
                        docId,
                        "version_id",
                        "v1",
                        "filename",
                        safeFilename(filename),
                        "title",
                        safeFilename(filename),
                        "domain",
                        blankOr(domain, "platform"),
                        "org_id",
                        blankOr(orgId, "platform"),
                        "created_by",
                        blankOr(createdBy, "platform_admin"),
                        "visibility",
                        blankOr(visibility, "PUBLIC"),
                        "doc_type",
                        extraction.documentType(),
                        "status",
                        extraction.parseStatus().equals("parsed") ? "parsed" : "requires_ocr",
                        "parse_status",
                        extraction.parseStatus(),
                        "parse_message",
                        extraction.message(),
                        "block_count",
                        chunks.size(),
                        "raw_available",
                        true,
                        "preview_available",
                        false,
                        "object_mime_type",
                        mimeType(filename),
                        "content_sha256",
                        contentSha256,
                        "raw_path",
                        storage.toWorkspaceRelative(rawFile),
                        "chunks",
                        chunks,
                        "created_at",
                        Instant.now().toString(),
                        "updated_at",
                        Instant.now().toString());
        if (provenance != null && !provenance.isEmpty()) {
            document.putAll(provenance);
        }
        QdrantKnowledgeIndex.IndexResult indexResult =
                "parsed".equals(extraction.parseStatus())
                        ? qdrantIndex.upsert(document, chunks)
                        : QdrantKnowledgeIndex.IndexResult.skipped("文档未提取到原生文本，等待 OCR。");
        document.put("vector_index_status", indexResult.status());
        document.put("vector_indexed_chunks", indexResult.indexed());
        document.put("vector_index_message", indexResult.message());
        documents.put(docId, document);
        persist(document);
        persistArtifact(document, rawFile);
        persistCatalog();
        return publicDocument(document);
    }

    public synchronized Map<String, Object> reindex(String docId) {
        return reindex(docId, null);
    }

    public synchronized Map<String, Object> reindex(
            String docId, PlatformAuthService.Principal principal) {
        Map<String, Object> existing = document(docId);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Document not found: " + docId);
        }
        requireReadable(existing, principal);
        Path rawFile = resolveStoredPath(String.valueOf(existing.get("raw_path")));
        qdrantIndex.deleteDocument(docId);
        Map<String, Object> refreshed =
                ingestInternal(
                        docId,
                        rawFile,
                        String.valueOf(existing.get("filename")),
                        String.valueOf(existing.get("domain")),
                        String.valueOf(existing.get("org_id")),
                        String.valueOf(existing.getOrDefault("created_by", "platform_admin")),
                        String.valueOf(existing.getOrDefault("visibility", "PUBLIC")),
                        provenance(existing));
        return map("document", refreshed, "indexed", refreshed.get("block_count"));
    }

    public synchronized Preview preparePreview(String docId) {
        return preparePreview(docId, null);
    }

    public synchronized Preview preparePreview(
            String docId, PlatformAuthService.Principal principal) {
        Map<String, Object> document = document(docId);
        if (document.isEmpty()) {
            throw new IllegalArgumentException("Document not found: " + docId);
        }
        requireReadable(document, principal);
        Path rawFile = resolveStoredPath(String.valueOf(document.get("raw_path")));
        if ("pdf".equals(document.get("doc_type"))) {
            return savePreview(document, rawFile, "application/pdf", "PDF 原文件已可预览。");
        }
        if (List.of("text", "markdown", "csv").contains(document.get("doc_type"))) {
            return savePreview(
                    document,
                    rawFile,
                    String.valueOf(document.get("object_mime_type")),
                    "文本文件已可预览。");
        }
        Path outputDirectory =
                storage.ensureDirectory(storage.resolveWorkspace("documents", docId, "preview"));
        Path previewFile =
                outputDirectory.resolve(
                        baseName(String.valueOf(document.get("filename"))) + ".pdf");
        if (!Files.isRegularFile(previewFile)) {
            convertOfficeToPdf(rawFile, outputDirectory);
        }
        if (!Files.isRegularFile(previewFile)) {
            throw new IllegalStateException("文档转换未生成 PDF 预览文件。");
        }
        return savePreview(document, previewFile, "application/pdf", "Office 文档已转换为 PDF 预览。");
    }

    public synchronized PreviewFile previewFile(String docId) {
        return previewFile(docId, null);
    }

    public synchronized PreviewFile previewFile(
            String docId, PlatformAuthService.Principal principal) {
        Map<String, Object> document = document(docId);
        if (document.isEmpty()) {
            throw new IllegalArgumentException("Document not found: " + docId);
        }
        requireReadable(document, principal);
        String path = String.valueOf(document.get("preview_path"));
        if (path.isBlank() || "null".equals(path)) {
            throw new IllegalStateException("请先生成文档预览。");
        }
        Path preview = resolveStoredPath(path);
        return new PreviewFile(preview, String.valueOf(document.get("preview_mime_type")));
    }

    public synchronized void delete(String docId) {
        delete(docId, null);
    }

    public synchronized void delete(String docId, PlatformAuthService.Principal principal) {
        Map<String, Object> existing = document(docId);
        if (!existing.isEmpty()) requireWritable(existing, principal);
        Map<String, Object> removed = documents.remove(docId);
        if (removed != null) {
            qdrantIndex.deleteDocument(docId);
            deleteRecursively(storage.resolveWorkspace("documents", docId));
            artifactStore.deleteDocument(docId);
            persistCatalog();
        }
    }

    public synchronized List<Map<String, Object>> documents(String domain) {
        return documents(domain, null);
    }

    public synchronized List<Map<String, Object>> documents(
            String domain, PlatformAuthService.Principal principal) {
        return documents.values().stream()
                .filter(document -> isReadable(document, principal))
                .filter(
                        document ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(document.get("domain")))
                .sorted(
                        Comparator.comparing(
                                        (Map<String, Object> document) ->
                                                String.valueOf(document.get("updated_at")))
                                .reversed())
                .map(DocumentKnowledgeService::publicDocument)
                .toList();
    }

    public synchronized Map<String, Object> document(String docId) {
        Map<String, Object> document = documents.get(docId);
        return document == null ? Map.of() : new LinkedHashMap<>(document);
    }

    public synchronized Map<String, Object> document(
            String docId, PlatformAuthService.Principal principal) {
        Map<String, Object> document = document(docId);
        if (document.isEmpty()) return document;
        requireReadable(document, principal);
        return document;
    }

    public synchronized Retrieval retrieve(String query, List<String> requestedDocIds, int topK) {
        return retrieve(query, requestedDocIds, topK, null);
    }

    public synchronized Retrieval retrieve(
            String query,
            List<String> requestedDocIds,
            int topK,
            PlatformAuthService.Principal principal) {
        List<RequestedDocument> requested =
                requestedDocIds == null
                        ? List.of()
                        : requestedDocIds.stream()
                                .map(id -> new RequestedDocument(id, ""))
                                .toList();
        return retrieveScoped(query, requested, topK, principal);
    }

    public synchronized Retrieval retrieveScoped(
            String query,
            List<RequestedDocument> requestedDocuments,
            int topK,
            PlatformAuthService.Principal principal) {
        Set<String> readableIds =
                documents.values().stream()
                        .filter(document -> isReadable(document, principal))
                        .map(document -> String.valueOf(document.get("doc_id")))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (principal != null && readableIds.isEmpty()) {
            return new Retrieval("", List.of());
        }
        boolean explicitScope = requestedDocuments != null && !requestedDocuments.isEmpty();
        Set<String> allowedIds;
        Map<String, Set<String>> allowedVersions = new LinkedHashMap<>();
        if (!explicitScope) {
            allowedIds = readableIds;
        } else {
            allowedIds = new LinkedHashSet<>();
            for (RequestedDocument requested : requestedDocuments) {
                if (requested == null || requested.docId() == null || requested.docId().isBlank()) {
                    continue;
                }
                if (!readableIds.contains(requested.docId())) {
                    continue;
                }
                String version = requested.versionId() == null ? "" : requested.versionId().strip();
                allowedIds.add(requested.docId());
                allowedVersions.computeIfAbsent(requested.docId(), ignored -> new LinkedHashSet<>());
                if (!version.isBlank()) {
                    allowedVersions.get(requested.docId()).add(version);
                }
            }
            // An explicit but unauthorized/nonexistent/version-mismatched scope must never fall
            // through to the default all-readable search.
            if (allowedIds.isEmpty()) {
                return new Retrieval("", List.of());
            }
        }
        List<String> terms = searchTerms(query);
        int limit = Math.max(1, Math.min(topK <= 0 ? 4 : topK, 8));
        QdrantKnowledgeIndex.SearchResult vectorResult =
                qdrantIndex.search(query, new ArrayList<>(allowedIds), limit);
        if (vectorResult.available() && !vectorResult.hits().isEmpty()) {
            List<QdrantKnowledgeIndex.VectorHit> filtered =
                    vectorResult.hits().stream()
                            .filter(
                                    hit ->
                                            isAllowedVersion(
                                                    hit.documentId(), hit.versionId(), allowedIds, allowedVersions))
                            .toList();
            if (!filtered.isEmpty()) {
                return vectorRetrieval(filtered);
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> document : documents.values()) {
            String docId = String.valueOf(document.get("doc_id"));
            if (!isAllowedVersion(
                    docId,
                    String.valueOf(document.getOrDefault("version_id", "v1")),
                    allowedIds,
                    allowedVersions)) {
                continue;
            }
            if (!"parsed".equals(document.get("parse_status"))) {
                continue;
            }
            for (Map<String, Object> chunk : chunks(document)) {
                String text = String.valueOf(chunk.get("text"));
                int score = score(text, terms);
                if (score > 0) {
                    hits.add(new Hit(document, chunk, score));
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        List<Hit> selected = hits.stream().limit(limit).toList();
        StringBuilder context = new StringBuilder();
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Hit hit : selected) {
            String text = hit.text();
            if (context.length() + text.length() > MAX_CONTEXT_CHARS) {
                text = text.substring(0, Math.max(0, MAX_CONTEXT_CHARS - context.length()));
            }
            if (text.isBlank()) {
                break;
            }
            context.append("\n\n[来源: ")
                    .append(hit.filename())
                    .append("，片段 ")
                    .append(hit.ordinal())
                    .append("]\n")
                    .append(text);
            citations.add(
                    map(
                            "doc_id",
                            hit.document().get("doc_id"),
                            "version_id",
                            hit.document().get("version_id"),
                            "filename",
                            hit.filename(),
                            "chunk_id",
                            hit.chunk().get("chunk_id"),
                            "ordinal",
                            hit.ordinal(),
                            "score",
                            hit.score()));
            if (context.length() >= MAX_CONTEXT_CHARS) {
                break;
            }
        }
        return new Retrieval(context.toString().strip(), List.copyOf(citations));
    }

    private static boolean isAllowedVersion(
            String docId,
            String versionId,
            Set<String> allowedIds,
            Map<String, Set<String>> allowedVersions) {
        if (!allowedIds.contains(docId)) {
            return false;
        }
        Set<String> versions = allowedVersions.get(docId);
        return versions == null || versions.isEmpty() || versions.contains(versionId);
    }

    private static Retrieval vectorRetrieval(List<QdrantKnowledgeIndex.VectorHit> hits) {
        StringBuilder context = new StringBuilder();
        List<Map<String, Object>> citations = new ArrayList<>();
        for (QdrantKnowledgeIndex.VectorHit hit : hits) {
            String text = hit.text();
            if (context.length() + text.length() > MAX_CONTEXT_CHARS) {
                text = text.substring(0, Math.max(0, MAX_CONTEXT_CHARS - context.length()));
            }
            if (text.isBlank()) {
                break;
            }
            context.append("\n\n[来源: ")
                    .append(hit.filename())
                    .append("，片段 ")
                    .append(hit.ordinal())
                    .append("]\n")
                    .append(text);
            citations.add(
                    map(
                            "doc_id",
                            hit.documentId(),
                            "version_id",
                            hit.versionId(),
                            "filename",
                            hit.filename(),
                            "chunk_id",
                            hit.chunkId(),
                            "ordinal",
                            hit.ordinal(),
                            "score",
                            hit.score(),
                            "retrieval",
                            "qdrant"));
            if (context.length() >= MAX_CONTEXT_CHARS) {
                break;
            }
        }
        return new Retrieval(context.toString().strip(), List.copyOf(citations));
    }

    public static String withContext(String query, Retrieval retrieval) {
        if (retrieval == null || retrieval.context().isBlank()) {
            return query;
        }
        return query
                + "\n\n<platform_document_context>\n"
                + "以下是从用户指定文档中检索出的可信片段。请优先据此回答；片段未覆盖时明确说明。\n"
                + "回复必须跟随用户提问的语言；用户用中文提问时，标题、正文和建议都必须用中文。\n"
                + "若用户要求总结、提炼要点或关键结论，直接给出基于文档的总结，不要自行改写为发布简报、项目计划或评审报告。\n"
                + "不要向用户透露加载 Skill、模板或工具的过程。\n"
                + "</platform_document_context>\n"
                + retrieval.context();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> chunks(Map<String, Object> document) {
        if (!(document.get("chunks") instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                Map<String, Object> item = new LinkedHashMap<>();
                map.forEach((key, value) -> item.put(String.valueOf(key), value));
                result.add(item);
            }
        }
        return result;
    }

    private static int score(String text, List<String> terms) {
        String value = text.toLowerCase(Locale.ROOT);
        int total = 0;
        for (String term : terms) {
            int at = value.indexOf(term);
            while (at >= 0) {
                total++;
                at = value.indexOf(term, at + term.length());
            }
        }
        return total;
    }

    private static List<String> searchTerms(String query) {
        String value = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String word : value.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 2) {
                terms.add(word);
            }
            if (containsCjk(word)) {
                for (int index = 0; index + 1 < word.length(); index++) {
                    terms.add(word.substring(index, index + 2));
                }
            }
        }
        return List.copyOf(terms);
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private void loadCatalog() {
        if (storage.isSqliteEnabled() && loadPersistedDocuments()) {
            return;
        }
        loadCatalogFile();
        if (storage.isSqliteEnabled()) {
            for (Map<String, Object> document : documents.values()) {
                persistArtifact(document, existingRawFile(document));
            }
        }
    }

    private static boolean isReadable(
            Map<String, Object> document, PlatformAuthService.Principal principal) {
        if (principal == null) return true;
        if ("PLATFORM_ADMIN".equals(principal.role())) return true;
        String visibility = String.valueOf(document.getOrDefault("visibility", "PUBLIC"));
        if ("PUBLIC".equals(visibility)) return true;
        if (!principal.orgId().equals(String.valueOf(document.getOrDefault("org_id", "")))) {
            return false;
        }
        return "ORGANIZATION".equals(visibility)
                || ("PRIVATE".equals(visibility)
                        && principal.userId().equals(String.valueOf(document.get("created_by"))));
    }

    private static boolean isOwnedPrivate(
            Map<String, Object> document, PlatformAuthService.Principal principal) {
        return principal != null
                && "PRIVATE".equals(String.valueOf(document.getOrDefault("visibility", "PUBLIC")))
                && principal.orgId().equals(String.valueOf(document.getOrDefault("org_id", "")))
                && principal.userId().equals(String.valueOf(document.getOrDefault("created_by", "")));
    }

    private static void requireReadable(
            Map<String, Object> document, PlatformAuthService.Principal principal) {
        if (!isReadable(document, principal)) {
            throw new PlatformAuthService.AuthException(
                    principal == null ? 401 : 404, "文档不存在或当前账号无权访问");
        }
    }

    private static void requireWritable(
            Map<String, Object> document, PlatformAuthService.Principal principal) {
        if (principal == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录后管理文档");
        }
        if ("PLATFORM_ADMIN".equals(principal.role())) return;
        String visibility = String.valueOf(document.getOrDefault("visibility", "PUBLIC"));
        boolean owner = principal.userId().equals(String.valueOf(document.get("created_by")));
        boolean sameOrg = principal.orgId().equals(String.valueOf(document.get("org_id")));
        if (!owner || !sameOrg || "PUBLIC".equals(visibility)) {
            throw new PlatformAuthService.AuthException(403, "没有权限修改该文档");
        }
    }

    private boolean loadPersistedDocuments() {
        List<PlatformArtifactStore.StoredDocument> rows = artifactStore.loadDocuments();
        for (PlatformArtifactStore.StoredDocument row : rows) {
            try {
                Map<String, Object> document =
                        objectMapper.readValue(row.payload(), new TypeReference<>() {});
                if (document.isEmpty()) {
                    continue;
                }
                byte[] raw = row.rawContent();
                Path target = documentTarget(document);
                if (raw == null || raw.length == 0) {
                    Path legacy = existingRawFile(document);
                    if (legacy != null) {
                        raw = Files.readAllBytes(legacy);
                    }
                }
                if (raw != null && raw.length > 0) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, raw);
                    document.put("raw_path", storage.toWorkspaceRelative(target));
                    document.put("raw_available", true);
                }
                documents.put(row.docId(), new LinkedHashMap<>(document));
                if (raw != null && raw.length > 0) {
                    persistArtifact(document, target, raw);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to restore knowledge document: " + row.docId(), e);
            }
        }
        return !rows.isEmpty();
    }

    private void loadCatalogFile() {
        Path catalog = catalogFile();
        if (!Files.isRegularFile(catalog)) {
            return;
        }
        try {
            List<Map<String, Object>> rows =
                    objectMapper.readValue(catalog.toFile(), new TypeReference<>() {});
            for (Map<String, Object> row : rows) {
                String id = String.valueOf(row.get("doc_id"));
                if (!id.isBlank()) {
                    documents.put(id, new LinkedHashMap<>(row));
                }
            }
        } catch (IOException ignored) {
            // A malformed cache must not stop the platform; the original uploads remain on disk.
        }
    }

    private Path documentTarget(Map<String, Object> document) {
        String docId = String.valueOf(document.getOrDefault("doc_id", "document"));
        String versionId = String.valueOf(document.getOrDefault("version_id", "v1"));
        String filename = safeFilename(String.valueOf(document.getOrDefault("filename", "document")));
        return storage.resolveWorkspace("documents", docId, versionId, filename).normalize();
    }

    private Path existingRawFile(Map<String, Object> document) {
        Object value = document.get("raw_path");
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        Path path = Path.of(String.valueOf(value));
        if (!path.isAbsolute()) {
            path = storage.resolveWorkspace(String.valueOf(value));
        }
        return Files.isRegularFile(path) ? path.normalize() : null;
    }

    private void persist(Map<String, Object> document) {
        Path metadata =
                storage.resolveWorkspace(
                        "documents", String.valueOf(document.get("doc_id")), "metadata.json");
        writeJson(metadata, document);
    }

    private void persistArtifact(Map<String, Object> document, Path rawFile) {
        byte[] raw = null;
        try {
            if (rawFile != null && Files.isRegularFile(rawFile)) {
                raw = Files.readAllBytes(rawFile);
            }
            persistArtifact(document, rawFile, raw);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read knowledge document artifact: " + document.get("doc_id"), e);
        }
    }

    private void persistArtifact(Map<String, Object> document, Path rawFile, byte[] raw) {
        if (!storage.isSqliteEnabled()) {
            return;
        }
        try {
            if (rawFile != null && raw != null && raw.length > 0) {
                Path target = documentTarget(document);
                if (!target.equals(rawFile.normalize())) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, raw);
                    document.put("raw_path", storage.toWorkspaceRelative(target));
                }
            }
            artifactStore.saveDocument(
                    String.valueOf(document.get("doc_id")),
                    objectMapper.writeValueAsString(document),
                    raw);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to persist knowledge document artifact: " + document.get("doc_id"), e);
        }
    }

    private void persistCatalog() {
        writeJson(catalogFile(), new ArrayList<>(documents.values()));
    }

    private Path catalogFile() {
        return storage.resolveWorkspace("documents", "catalog.json");
    }

    private void writeJson(Path target, Object value) {
        try {
            storage.ensureDirectory(target.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist document metadata", e);
        }
    }

    private Path resolveStoredPath(String relativePath) {
        Path resolved = storage.resolveWorkspace(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(storage.workspace()) || !Files.isRegularFile(resolved)) {
            throw new IllegalStateException("The stored document file is unavailable.");
        }
        return resolved;
    }

    private Preview savePreview(
            Map<String, Object> document, Path previewFile, String mimeType, String message) {
        document.put("preview_available", true);
        document.put("preview_mime_type", mimeType);
        document.put("preview_path", storage.toWorkspaceRelative(previewFile));
        document.put("preview_message", message);
        document.put("updated_at", Instant.now().toString());
        documents.put(String.valueOf(document.get("doc_id")), document);
        persist(document);
        persistArtifact(document, existingRawFile(document));
        persistCatalog();
        return new Preview(true, message, mimeType);
    }

    private void convertOfficeToPdf(Path input, Path outputDirectory) {
        String executable = libreOfficeExecutable();
        Process process;
        try {
            process =
                    new ProcessBuilder(
                                    executable,
                                    "--headless",
                                    "--convert-to",
                                    "pdf",
                                    "--outdir",
                                    outputDirectory.toString(),
                                    input.toString())
                            .redirectErrorStream(true)
                            .start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Office 文档预览转换超时。");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Office 文档预览转换失败，退出码：" + process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Office 文档预览转换被中断。", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 LibreOffice 文档预览转换器。", e);
        }
    }

    private static String libreOfficeExecutable() {
        Path windowsDefault =
                Path.of("C:", "Program Files", "LibreOffice", "program", "soffice.com");
        return Files.isRegularFile(windowsDefault) ? windowsDefault.toString() : "soffice";
    }

    private static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static Map<String, Object> publicDocument(Map<String, Object> document) {
        Map<String, Object> copy = new LinkedHashMap<>(document);
        copy.remove("chunks");
        copy.remove("raw_path");
        copy.remove("preview_path");
        return copy;
    }

    private static String safeFilename(String filename) {
        String name = filename == null ? "document" : Path.of(filename).getFileName().toString();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String mimeType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (name.endsWith(".xls")) return "application/vnd.ms-excel";
        if (name.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (name.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "text/markdown";
        if (name.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (name.endsWith(".csv")) return "text/csv; charset=utf-8";
        return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String documentContentSha256(Map<String, Object> document) {
        String recorded = String.valueOf(document.getOrDefault("content_sha256", "")).strip();
        if (!recorded.isBlank()) {
            return recorded;
        }
        Object rawPath = document.get("raw_path");
        if (rawPath == null || String.valueOf(rawPath).isBlank()) {
            return "";
        }
        try {
            Path path = storage.resolveRelativeToWorkspace(String.valueOf(rawPath));
            return Files.isRegularFile(path) ? contentSha256(path) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String contentSha256(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算文档内容指纹", e);
        }
    }

    private static Map<String, Object> provenance(Map<String, Object> document) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("source_type", "source_session_id", "source_message_id")) {
            Object value = document.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    throw new IllegalStateException(
                                            "Failed to delete document storage", e);
                                }
                            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete document storage", e);
        }
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            row.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return row;
    }

    public record RequestedDocument(String docId, String versionId) {}

    public record Retrieval(String context, List<Map<String, Object>> citations) {}

    public record Preview(boolean ready, String message, String mimeType) {}

    public record PreviewFile(Path path, String mimeType) {}

    private record Hit(Map<String, Object> document, Map<String, Object> chunk, int score) {
        String text() {
            String text = String.valueOf(chunk.get("text"));
            return text.length() > MAX_CHUNK_CHARS ? text.substring(0, MAX_CHUNK_CHARS) : text;
        }

        String filename() {
            return String.valueOf(document.get("filename"));
        }

        int ordinal() {
            Object value = chunk.get("ordinal");
            return value instanceof Number number ? number.intValue() : 0;
        }
    }
}
