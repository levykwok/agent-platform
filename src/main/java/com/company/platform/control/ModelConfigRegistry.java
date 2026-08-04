/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import java.util.List;
import java.util.Optional;

public interface ModelConfigRegistry {
    List<ModelSpec> all();

    Optional<ModelSpec> find(String modelId);

    void upsert(ModelSpec model);

    void delete(String modelId);
}
