/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import java.util.List;
import java.util.Optional;

public interface AgentDefinitionRegistry {
    List<AgentDefinition> allPublished();

    Optional<AgentDefinition> findPublished(String agentId);

    AgentDefinition upsert(YamlAgentDefinitionRegistry.AgentConfig agent);

    void delete(String agentId);
}
