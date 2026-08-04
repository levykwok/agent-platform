/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import io.agentscope.core.embedding.EmbeddingModel;
import java.util.List;
import java.util.Optional;

/**
 * Registry for embedding models that are used by Knowledge / RAG runtime.
 */
public interface EmbeddingModelRegistry {

    Optional<EmbeddingModel> findEmbeddingModel(String modelId);

    default EmbeddingModel resolveEmbeddingModel(String modelId) {
        return findEmbeddingModel(modelId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown embedding model: " + modelId));
    }

    List<ModelSpec> allEmbeddingSpecs();
}
