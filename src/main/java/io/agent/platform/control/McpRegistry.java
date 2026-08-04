/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;
import java.util.Optional;

public interface McpRegistry {
    List<McpSpec> all();

    Optional<McpSpec> find(String mcpId);

    void upsert(McpSpec spec);

    void delete(String mcpId);
}
