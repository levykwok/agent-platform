/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {
    List<ToolSpec> all();

    Optional<ToolSpec> find(String toolId);

    void upsert(ToolSpec spec);
}
