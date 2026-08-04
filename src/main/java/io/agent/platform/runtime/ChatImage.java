/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import com.fasterxml.jackson.annotation.JsonAlias;

/** Image input accepted by the agent chat APIs. Exactly one of url or data is required. */
public record ChatImage(String url, String data, @JsonAlias({"media_type"}) String mediaType) {

    public ChatImage {
        url = url == null ? "" : url.strip();
        data = data == null ? "" : data.strip();
        mediaType = mediaType == null || mediaType.isBlank() ? "image/jpeg" : mediaType.strip();
        if (url.isBlank() == data.isBlank()) {
            throw new IllegalArgumentException("An image must provide exactly one of url or data");
        }
        if (!mediaType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException("Image media_type must start with image/");
        }
    }

    public boolean isUrl() {
        return !url.isBlank();
    }
}
