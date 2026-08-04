/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

public record SkillSpec(
        String skillId,
        String type,
        String location,
        String source,
        String scope,
        boolean writable,
        String description,
        boolean enabled) {

    public SkillSpec {
        skillId = skillId == null || skillId.isBlank() ? location : skillId.strip();
        type = type == null || type.isBlank() ? "filesystem" : type.strip().toLowerCase();
        location = location == null ? "" : location.strip();
        source = source == null || source.isBlank() ? "platform" : source.strip();
        scope = scope == null || scope.isBlank() ? "agent" : scope.strip().toLowerCase();
    }
}
