/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.control.PlatformStorageLayer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Persistent folders and document membership for the platform knowledge base. */
@Component
public class KnowledgeCollectionService {

    private final PlatformStorageLayer storage;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> collections = new LinkedHashMap<>();

    public KnowledgeCollectionService(PlatformStorageLayer storage) {
        this.storage = storage;
        load();
    }

    public synchronized List<Map<String, Object>> list(String domain) {
        return collections.values().stream()
                .filter(
                        collection ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(collection.get("domain")))
                .sorted(
                        Comparator.comparing(
                                        (Map<String, Object> collection) ->
                                                String.valueOf(collection.get("created_at")))
                                .reversed())
                .map(KnowledgeCollectionService::copyWithCount)
                .toList();
    }

    public synchronized Map<String, Object> create(Map<String, Object> payload, String orgId) {
        String title = string(payload.get("title"));
        if (title.isBlank()) {
            throw new IllegalArgumentException("分组名称不能为空。");
        }
        String id = "col_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> collection =
                map(
                        "collection_id",
                        id,
                        "id",
                        id,
                        "title",
                        title,
                        "domain",
                        nonBlank(string(payload.get("domain")), "platform"),
                        "org_id",
                        nonBlank(orgId, "platform"),
                        "collection_type",
                        nonBlank(string(payload.get("collection_type")), "folder"),
                        "scope",
                        nonBlank(string(payload.get("scope")), "org_shared"),
                        "items",
                        new ArrayList<>(),
                        "created_at",
                        Instant.now().toString(),
                        "updated_at",
                        Instant.now().toString());
        collections.put(id, collection);
        persist();
        return copyWithCount(collection);
    }

    public synchronized Map<String, Object> addDocument(
            String collectionId, String documentId, String versionId, String orgId) {
        Map<String, Object> collection = required(collectionId, orgId);
        String docId = string(documentId);
        if (docId.isBlank()) {
            throw new IllegalArgumentException("文档标识不能为空。");
        }
        List<Map<String, Object>> items = mutableItems(collection);
        items.removeIf(item -> docId.equals(item.get("item_id")));
        items.add(
                map(
                        "item_type",
                        "document",
                        "item_id",
                        docId,
                        "resource_id",
                        docId,
                        "item_version_id",
                        nonBlank(versionId, "v1"),
                        "added_at",
                        Instant.now().toString()));
        collection.put("items", items);
        collection.put("updated_at", Instant.now().toString());
        persist();
        return copyWithCount(collection);
    }

    public synchronized Map<String, Object> removeDocument(
            String collectionId, String documentId, String versionId, String orgId) {
        Map<String, Object> collection = required(collectionId, orgId);
        List<Map<String, Object>> items = mutableItems(collection);
        items.removeIf(
                item ->
                        documentId.equals(item.get("item_id"))
                                && (versionId.isBlank()
                                        || versionId.equals(item.get("item_version_id"))));
        collection.put("items", items);
        collection.put("updated_at", Instant.now().toString());
        persist();
        return copyWithCount(collection);
    }

    public synchronized void delete(String collectionId, String orgId) {
        required(collectionId, orgId);
        collections.remove(collectionId);
        persist();
    }

    public synchronized void removeDocumentEverywhere(String documentId) {
        boolean changed = false;
        for (Map<String, Object> collection : collections.values()) {
            List<Map<String, Object>> items = mutableItems(collection);
            if (items.removeIf(item -> documentId.equals(item.get("item_id")))) {
                collection.put("items", items);
                collection.put("updated_at", Instant.now().toString());
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    private Map<String, Object> required(String id, String orgId) {
        Map<String, Object> collection = collections.get(id);
        if (collection == null) {
            throw new IllegalArgumentException("分组不存在：" + id);
        }
        if (!nonBlank(orgId, "platform").equals(collection.get("org_id"))) {
            throw new IllegalArgumentException("无权修改其他组织的分组。");
        }
        return collection;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableItems(Map<String, Object> collection) {
        Object value = collection.get("items");
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, item) -> copy.put(String.valueOf(key), item));
                    result.add(copy);
                }
            }
        }
        return result;
    }

    private void load() {
        if (!Files.isRegularFile(file())) {
            return;
        }
        try {
            List<Map<String, Object>> rows =
                    objectMapper.readValue(file().toFile(), new TypeReference<>() {});
            for (Map<String, Object> row : rows) {
                String id = string(row.get("collection_id"));
                if (!id.isBlank()) {
                    collections.put(id, new LinkedHashMap<>(row));
                }
            }
        } catch (IOException ignored) {
            // Keep the platform available if a manually edited cache is malformed.
        }
    }

    private void persist() {
        try {
            storage.ensureDirectory(file().getParent());
            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(file().toFile(), new ArrayList<>(collections.values()));
        } catch (IOException e) {
            throw new IllegalStateException("保存知识库分组失败。", e);
        }
    }

    private Path file() {
        return storage.resolveWorkspace("documents", "collections.json");
    }

    private static Map<String, Object> copyWithCount(Map<String, Object> collection) {
        Map<String, Object> copy = new LinkedHashMap<>(collection);
        copy.put("item_count", mutableItems(collection).size());
        return copy;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            row.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return row;
    }
}
