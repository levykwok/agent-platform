/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.List;
import java.util.Optional;

public interface SkillRegistry {
    List<SkillSpec> all();

    Optional<SkillSpec> find(String skillId);

    void upsert(SkillSpec spec);

    void delete(String skillId);
}
