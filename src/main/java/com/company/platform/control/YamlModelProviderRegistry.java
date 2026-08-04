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
public class YamlModelProviderRegistry implements ModelProviderRegistry {

    private final Map<String, ModelProviderSpec> providers = new ConcurrentHashMap<>();
    private final PlatformConfigStore configStore;

    public YamlModelProviderRegistry(PlatformConfigStore configStore) {
        this.configStore = configStore;
    }

    @PostConstruct
    public void load() throws IOException {
        ProviderConfigRoot root =
                configStore.read(
                        PlatformConfigStore.ConfigFile.PROVIDERS, ProviderConfigRoot.class);
        Map<String, ModelProviderSpec> loaded = new LinkedHashMap<>();
        for (ProviderConfig provider : root.providers()) {
            ModelProviderSpec spec = toSpec(provider);
            validate(spec);
            loaded.put(spec.providerId(), spec);
        }
        providers.clear();
        providers.putAll(loaded);
    }

    @Override
    public List<ModelProviderSpec> all() {
        return providers.values().stream().toList();
    }

    @Override
    public Optional<ModelProviderSpec> find(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    @Override
    public void upsert(ModelProviderSpec provider) {
        validate(provider);
        providers.put(provider.providerId(), provider);
        persist();
    }

    @Override
    public void delete(String providerId) {
        providers.remove(providerId);
        persist();
    }

    private ModelProviderSpec toSpec(ProviderConfig provider) {
        return new ModelProviderSpec(
                provider.providerId(),
                provider.displayName(),
                provider.providerType(),
                provider.defaultBaseUrl(),
                provider.endpointPath(),
                provider.secretRef(),
                provider.timeoutMs(),
                provider.description(),
                provider.status());
    }

    private ProviderConfig toConfig(ModelProviderSpec spec) {
        return new ProviderConfig(
                spec.providerId(),
                spec.displayName(),
                spec.providerType(),
                spec.defaultBaseUrl(),
                spec.endpointPath(),
                spec.secretRef(),
                spec.timeoutMs(),
                spec.description(),
                spec.status());
    }

    private void validate(ModelProviderSpec spec) {
        if (spec.providerId() == null || spec.providerId().isBlank()) {
            throw new IllegalStateException("Provider id cannot be blank");
        }
    }

    private void persist() {
        List<ProviderConfig> list =
                providers.values().stream()
                        .sorted((a, b) -> a.providerId().compareToIgnoreCase(b.providerId()))
                        .map(this::toConfig)
                        .collect(Collectors.toList());
        configStore.write(PlatformConfigStore.ConfigFile.PROVIDERS, new ProviderConfigRoot(list));
    }

    public record ProviderConfigRoot(List<ProviderConfig> providers) {
        public ProviderConfigRoot {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }
    }

    public record ProviderConfig(
            String providerId,
            String displayName,
            String providerType,
            String defaultBaseUrl,
            String endpointPath,
            String secretRef,
            Long timeoutMs,
            String description,
            String status) {}
}
