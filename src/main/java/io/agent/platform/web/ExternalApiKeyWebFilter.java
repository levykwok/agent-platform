/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Protects the stable external API without affecting the platform's internal endpoints. */
@Component
@Order(-100)
public class ExternalApiKeyWebFilter implements WebFilter {

    private final boolean enabled;
    private final Set<String> apiKeys;

    public ExternalApiKeyWebFilter(
            @Value("${agent.platform.api.external.enabled:false}") String enabled,
            @Value("${agent.platform.api.external.api-keys:}") String configuredKeys) {
        this.enabled = Boolean.parseBoolean(enabled);
        this.apiKeys = parseKeys(configuredKeys);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!isExternalApi(path) || "/api/v1/health".equals(path)) {
            return chain.filter(exchange);
        }
        if (!enabled) {
            return writeError(
                    exchange,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "external_api_disabled",
                    "External API is disabled.");
        }
        if (apiKeys.isEmpty()) {
            return writeError(
                    exchange,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "external_api_not_configured",
                    "External API keys are not configured.");
        }
        String supplied = suppliedKey(exchange);
        if (supplied.isBlank() || !apiKeys.contains(supplied)) {
            return writeError(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "invalid_api_key",
                    "A valid X-API-Key or Bearer token is required.");
        }
        return chain.filter(exchange);
    }

    private static boolean isExternalApi(String path) {
        return "/api/v1".equals(path) || path.startsWith("/api/v1/");
    }

    private static Set<String> parseKeys(String configuredKeys) {
        if (configuredKeys == null || configuredKeys.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configuredKeys.split(","))
                .map(String::strip)
                .filter(key -> !key.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String suppliedKey(ServerWebExchange exchange) {
        String direct = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (direct != null && !direct.isBlank()) {
            return direct.strip();
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).strip();
        }
        return "";
    }

    private static Mono<Void> writeError(
            ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body =
                ("{\"error\":{\"code\":\""
                                + code
                                + "\",\"message\":\""
                                + message
                                + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
