/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class YamlSkillRegistry implements SkillRegistry {

    private final Map<String, SkillSpec> skills = new ConcurrentHashMap<>();
    private final PlatformConfigStore configStore;

    public YamlSkillRegistry(PlatformConfigStore configStore) {
        this.configStore = configStore;
    }

    @PostConstruct
    public void load() throws IOException {
        SkillConfigRoot config =
                configStore.read(PlatformConfigStore.ConfigFile.SKILLS, SkillConfigRoot.class);
        Map<String, SkillSpec> loaded = new LinkedHashMap<>();
        for (SkillConfig skill : config.skills()) {
            SkillSpec spec = toSpec(skill);
            if (spec.skillId() == null || spec.skillId().isBlank()) {
                throw new IllegalStateException("Skill id cannot be blank");
            }
            validate(spec);
            if (loaded.containsKey(spec.skillId())) {
                throw new IllegalStateException("Duplicate skillId in config: " + spec.skillId());
            }
            loaded.put(spec.skillId(), spec);
        }
        skills.clear();
        skills.putAll(loaded);
    }

    @Override
    public List<SkillSpec> all() {
        return skills.values().stream().toList();
    }

    @Override
    public Optional<SkillSpec> find(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    @Override
    public void upsert(SkillSpec spec) {
        SkillSpec normalized = normalize(spec);
        validate(normalized);
        skills.put(normalized.skillId(), normalized);
        persist();
    }

    @Override
    public void delete(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return;
        }
        skills.remove(skillId);
        persist();
    }

    private SkillSpec toSpec(SkillConfig skill) {
        return new SkillSpec(
                skill.skillId(),
                skill.type(),
                skill.location(),
                skill.source(),
                skill.scope(),
                skill.writable(),
                skill.description(),
                skill.enabled());
    }

    private SkillSpec normalize(SkillSpec spec) {
        return new SkillSpec(
                spec.skillId(),
                spec.type(),
                spec.location(),
                spec.source(),
                spec.scope(),
                spec.writable(),
                spec.description(),
                spec.enabled());
    }

    private SkillConfig toConfig(SkillSpec spec) {
        return new SkillConfig(
                spec.skillId(),
                spec.type(),
                spec.location(),
                spec.source(),
                spec.scope(),
                spec.writable(),
                spec.description(),
                spec.enabled());
    }

    private void validate(SkillSpec spec) {
        if (spec.skillId() == null || spec.skillId().isBlank()) {
            throw new IllegalStateException("Skill id cannot be blank");
        }
        if (spec.location() == null || spec.location().isBlank()) {
            throw new IllegalStateException("Skill location cannot be blank: " + spec.skillId());
        }
        if (!"classpath".equals(spec.type())
                && !"filesystem".equals(spec.type())
                && !"local".equals(spec.type())) {
            throw new IllegalStateException(
                    "Unsupported skill type for " + spec.skillId() + ": " + spec.type());
        }
        if (!"agent".equals(spec.scope())
                && !"platform".equals(spec.scope())
                && !"filesystem".equals(spec.scope())
                && !"global".equals(spec.scope())) {
            throw new IllegalStateException(
                    "Unsupported skill scope for " + spec.skillId() + ": " + spec.scope());
        }
    }

    private void persist() {
        List<SkillConfig> list =
                skills.values().stream()
                        .sorted((a, b) -> a.skillId().compareToIgnoreCase(b.skillId()))
                        .map(this::toConfig)
                        .collect(Collectors.toList());
        configStore.write(PlatformConfigStore.ConfigFile.SKILLS, new SkillConfigRoot(list));
    }

    public record SkillConfigRoot(List<SkillConfig> skills) {
        public SkillConfigRoot {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    public record SkillConfig(
            String skillId,
            String type,
            String location,
            String source,
            String scope,
            boolean writable,
            String description,
            boolean enabled) {}
}
