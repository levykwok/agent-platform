/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import java.util.List;
import java.util.Optional;

public interface ModelProviderRegistry {
    List<ModelProviderSpec> all();

    Optional<ModelProviderSpec> find(String providerId);

    void upsert(ModelProviderSpec provider);

    void delete(String providerId);
}
