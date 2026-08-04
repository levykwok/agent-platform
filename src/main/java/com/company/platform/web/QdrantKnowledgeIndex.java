/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.control.EmbeddingModelRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.TextBlock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Qdrant-backed vector index for the platform knowledge documents. */
@Component
public class QdrantKnowledgeIndex {

    // Qdrant is an optional local dependency during development. Fail fast so an unavailable
    // vector service never makes document upload or chat appear stuck before lexical fallback.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final EmbeddingModelRegistry embeddingModels;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final boolean enabled;
    private final String endpoint;
    private final String collection;
    private final String embeddingModelId;
    private final int dimensions;
    private final double scoreThreshold;
    private volatile boolean collectionReady;

    public QdrantKnowledgeIndex(
            EmbeddingModelRegistry embeddingModels,
            @Value("${company.platform.rag.qdrant.enabled:true}") boolean enabled,
            @Value("${company.platform.rag.qdrant.endpoint:http://localhost:6333}") String endpoint,
            @Value("${company.platform.rag.qdrant.collection:platform_knowledge_chunks}")
                    String collection,
            @Value("${company.platform.rag.embedding-model:bge-m3}") String embeddingModelId,
            @Value("${company.platform.rag.qdrant.dimensions:1024}") int dimensions,
            @Value("${company.platform.rag.retrieve.score-threshold:0.25}") double scoreThreshold) {
        this.embeddingModels = embeddingModels;
        this.enabled = enabled;
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.collection = collection;
        this.embeddingModelId = embeddingModelId;
        this.dimensions = dimensions;
        this.scoreThreshold = scoreThreshold;
    }

    public IndexResult upsert(Map<String, Object> document, List<Map<String, Object>> chunks) {
        if (!enabled) {
            return IndexResult.skipped("Qdrant 索引已关闭。");
        }
        if (chunks.isEmpty()) {
            return IndexResult.skipped("文档没有可向量化的文本块。");
        }
        try {
            ensureCollection();
            ObjectNode request = objectMapper.createObjectNode();
            ArrayNode points = request.putArray("points");
            for (Map<String, Object> chunk : chunks) {
                String text = String.valueOf(chunk.get("text")).strip();
                if (text.isBlank()) {
                    continue;
                }
                double[] embedding = embed(text);
                if (embedding.length != dimensions) {
                    throw new IllegalStateException(
                            "Embedding 维度不匹配：Qdrant collection 为 "
                                    + dimensions
                                    + "，模型返回 "
                                    + embedding.length
                                    + "。");
                }
                ObjectNode point = points.addObject();
                point.put("id", pointId(document, chunk, text));
                ArrayNode vector = point.putArray("vector");
                for (double value : embedding) {
                    vector.add(value);
                }
                ObjectNode payload = point.putObject("payload");
                payload.put("doc_id", string(document.get("doc_id")));
                payload.put("chunk_id", string(chunk.get("chunk_id")));
                payload.put("version_id", string(document.get("version_id")));
                payload.put("filename", string(document.get("filename")));
                payload.put("domain", string(document.get("domain")));
                payload.put("org_id", string(document.get("org_id")));
                payload.put("ordinal", intValue(chunk.get("ordinal")));
                payload.put("text", text);
            }
            if (points.isEmpty()) {
                return IndexResult.skipped("文档没有可向量化的文本块。");
            }
            request("PUT", "/collections/" + collection + "/points?wait=true", request);
            return IndexResult.indexed(points.size());
        } catch (Exception e) {
            return IndexResult.failed(message(e));
        }
    }

    public SearchResult search(String query, List<String> allowedDocumentIds, int limit) {
        if (!enabled) {
            return SearchResult.unavailable("Qdrant 索引已关闭。");
        }
        if (query == null || query.isBlank()) {
            return SearchResult.success(List.of());
        }
        try {
            ensureCollection();
            ObjectNode request = objectMapper.createObjectNode();
            ArrayNode vector = request.putArray("query");
            for (double value : embed(query)) {
                vector.add(value);
            }
            request.put("limit", Math.max(1, Math.min(limit, 8)));
            request.put("score_threshold", scoreThreshold);
            request.put("with_payload", true);
            request.put("with_vector", false);
            addDocumentFilter(request, allowedDocumentIds);
            JsonNode response =
                    request("POST", "/collections/" + collection + "/points/query", request);
            JsonNode points = response.path("result").path("points");
            if (!points.isArray()) {
                points = response.path("result");
            }
            List<VectorHit> hits = new ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                String text = payload.path("text").asText("").strip();
                if (text.isBlank()) {
                    continue;
                }
                hits.add(
                        new VectorHit(
                                payload.path("doc_id").asText(),
                                payload.path("version_id").asText("v1"),
                                payload.path("filename").asText(),
                                payload.path("chunk_id").asText(),
                                payload.path("ordinal").asInt(),
                                text,
                                point.path("score").asDouble()));
            }
            return SearchResult.success(hits);
        } catch (Exception e) {
            return SearchResult.unavailable(message(e));
        }
    }

    public void deleteDocument(String documentId) {
        if (!enabled || documentId == null || documentId.isBlank()) {
            return;
        }
        try {
            ensureCollection();
            ObjectNode request = objectMapper.createObjectNode();
            ObjectNode filter = request.putObject("filter");
            ArrayNode must = filter.putArray("must");
            ObjectNode condition = must.addObject();
            condition.put("key", "doc_id").putObject("match").put("value", documentId);
            request("POST", "/collections/" + collection + "/points/delete?wait=true", request);
        } catch (Exception ignored) {
            // Document lifecycle must remain usable while a local vector service is unavailable.
        }
    }

    private synchronized void ensureCollection() throws Exception {
        if (collectionReady) {
            return;
        }
        HttpResponse<String> response = rawRequest("GET", "/collections/" + collection, null);
        if (response.statusCode() == 404) {
            ObjectNode create = objectMapper.createObjectNode();
            create.putObject("vectors").put("size", dimensions).put("distance", "Cosine");
            request("PUT", "/collections/" + collection, create);
        } else if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Qdrant collection 检查失败：HTTP " + response.statusCode());
        }
        createPayloadIndex("doc_id");
        createPayloadIndex("org_id");
        createPayloadIndex("domain");
        collectionReady = true;
    }

    private void createPayloadIndex(String field) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("field_name", field);
        body.put("field_schema", "keyword");
        request("PUT", "/collections/" + collection + "/index?wait=true", body);
    }

    private void addDocumentFilter(ObjectNode request, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        ObjectNode filter = request.putObject("filter");
        ArrayNode should = filter.putArray("should");
        for (String documentId : documentIds) {
            if (documentId == null || documentId.isBlank()) {
                continue;
            }
            should.addObject().put("key", "doc_id").putObject("match").put("value", documentId);
        }
    }

    private double[] embed(String text) {
        return embeddingModels
                .resolveEmbeddingModel(embeddingModelId)
                .embed(TextBlock.builder().text(text).build())
                .block(REQUEST_TIMEOUT);
    }

    private JsonNode request(String method, String path, JsonNode body) throws Exception {
        HttpResponse<String> response = rawRequest(method, path, body);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Qdrant 请求失败：HTTP " + response.statusCode() + " " + response.body());
        }
        return response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
    }

    private HttpResponse<String> rawRequest(String method, String path, JsonNode body)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(REQUEST_TIMEOUT);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(
                                    objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String pointId(
            Map<String, Object> document, Map<String, Object> chunk, String text) {
        String source =
                string(document.get("doc_id")) + "\n" + string(chunk.get("chunk_id")) + "\n" + text;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String message(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    public record IndexResult(String status, int indexed, String message) {
        static IndexResult indexed(int count) {
            return new IndexResult("indexed", count, "已写入 Qdrant 向量索引。");
        }

        static IndexResult skipped(String message) {
            return new IndexResult("skipped", 0, message);
        }

        static IndexResult failed(String message) {
            return new IndexResult("failed", 0, message);
        }
    }

    public record SearchResult(boolean available, List<VectorHit> hits, String message) {
        static SearchResult success(List<VectorHit> hits) {
            return new SearchResult(true, List.copyOf(hits), "");
        }

        static SearchResult unavailable(String message) {
            return new SearchResult(false, List.of(), message);
        }
    }

    public record VectorHit(
            String documentId,
            String versionId,
            String filename,
            String chunkId,
            int ordinal,
            String text,
            double score) {}
}
