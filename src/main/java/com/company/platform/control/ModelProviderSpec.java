/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

public record ModelProviderSpec(
        String providerId,
        String displayName,
        String providerType,
        String defaultBaseUrl,
        String endpointPath,
        String secretRef,
        Long timeoutMs,
        String description,
        String status) {

    public ModelProviderSpec {
        providerId = providerId == null ? "" : providerId.strip();
        displayName =
                displayName == null || displayName.isBlank() ? providerId : displayName.strip();
        providerType =
                providerType == null || providerType.isBlank()
                        ? "openai-compatible"
                        : providerType.strip().toLowerCase();
        defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.strip();
        endpointPath = endpointPath == null ? "" : endpointPath.strip();
        secretRef = secretRef == null ? "" : secretRef.strip();
        description = description == null ? "" : description.strip();
        status = status == null || status.isBlank() ? "active" : status.strip().toLowerCase();
    }
}
