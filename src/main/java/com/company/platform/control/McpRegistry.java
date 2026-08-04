/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import java.util.List;
import java.util.Optional;

public interface McpRegistry {
    List<McpSpec> all();

    Optional<McpSpec> find(String mcpId);

    void upsert(McpSpec spec);

    void delete(String mcpId);
}
