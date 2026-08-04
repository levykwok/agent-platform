/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;
import java.util.Optional;

public interface ModelConfigRegistry {
    List<ModelSpec> all();

    Optional<ModelSpec> find(String modelId);

    void upsert(ModelSpec model);

    void delete(String modelId);
}
