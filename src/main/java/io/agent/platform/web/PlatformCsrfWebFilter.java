/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Rejects cross-origin, cookie-authenticated mutations of platform endpoints. */
@Component
@Order(-90)
public class PlatformCsrfWebFilter implements WebFilter {

    private static final String SESSION_COOKIE = "platform_session";
    private final Set<String> allowedOrigins;

    public PlatformCsrfWebFilter(
            @Value("${agent.platform.auth.allowed-origins:}") String configuredOrigins) {
        this.allowedOrigins = parseOrigins(configuredOrigins);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (!request.getPath().value().startsWith("/platform/")
                || isSafe(request.getMethod())
                || request.getCookies().getFirst(SESSION_COOKIE) == null) {
            return chain.filter(exchange);
        }
        String fetchSite = request.getHeaders().getFirst("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            return reject(exchange);
        }
        String origin = request.getHeaders().getOrigin();
        if (origin == null || origin.isBlank()) {
            // Non-browser clients generally omit Origin; authentication and authorization still apply.
            return chain.filter(exchange);
        }
        String normalized = normalizeOrigin(origin);
        if (normalized.isBlank()
                || (!allowedOrigins.contains(normalized)
                        && !sameOrigin(URI.create(normalized), request.getURI()))) {
            return reject(exchange);
        }
        return chain.filter(exchange);
    }

    private static boolean isSafe(HttpMethod method) {
        return method == null
                || HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.OPTIONS.equals(method);
    }

    private static boolean sameOrigin(URI origin, URI request) {
        return equalsIgnoreCase(origin.getScheme(), request.getScheme())
                && equalsIgnoreCase(origin.getHost(), request.getHost())
                && effectivePort(origin) == effectivePort(request);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static Set<String> parseOrigins(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(PlatformCsrfWebFilter::normalizeOrigin)
                .filter(origin -> !origin.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeOrigin(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            StringBuilder result =
                    new StringBuilder(uri.getScheme().toLowerCase())
                            .append("://")
                            .append(uri.getHost().toLowerCase());
            if (uri.getPort() >= 0) result.append(':').append(uri.getPort());
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body =
                "{\"ok\":false,\"detail\":\"跨站请求已被拒绝\"}"
                        .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
