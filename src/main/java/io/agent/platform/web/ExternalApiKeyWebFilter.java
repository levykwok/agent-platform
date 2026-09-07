/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Protects the stable external API without affecting the platform's internal endpoints. */
@Component
@Order(-100)
public class ExternalApiKeyWebFilter implements WebFilter {

    public static final String CLIENT_ATTRIBUTE =
            ExternalApiKeyWebFilter.class.getName() + ".client";

    private final boolean enabled;
    private final Set<String> apiKeys;
    private final ExternalAccessService externalAccess;

    public ExternalApiKeyWebFilter(
            @Value("${agent.platform.api.external.enabled:false}") String enabled,
            @Value("${agent.platform.api.external.api-keys:}") String configuredKeys,
            ExternalAccessService externalAccess) {
        this.enabled = Boolean.parseBoolean(enabled);
        this.apiKeys = parseKeys(configuredKeys);
        this.externalAccess = externalAccess;
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
        String supplied = suppliedKey(exchange);
        RequestTarget target = requestTarget(exchange);
        return Mono.fromCallable(() -> Optional.ofNullable(authenticate(supplied)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        resolved -> {
                            if (resolved.isEmpty()) {
                                ExternalAccessService.ApiClient identified =
                                        externalAccess.identifyManagedCredential(supplied);
                                auditRejection(
                                        exchange,
                                        identified,
                                        supplied,
                                        target,
                                        HttpStatus.UNAUTHORIZED.value(),
                                        "invalid_api_key",
                                        "A valid X-API-Key or Bearer token is required.");
                                return writeError(
                                        exchange,
                                        HttpStatus.UNAUTHORIZED,
                                        "invalid_api_key",
                                        "A valid X-API-Key or Bearer token is required.");
                            }
                            ExternalAccessService.ApiClient client = resolved.orElseThrow();
                            ExternalAccessService.AccessDecision decision =
                                    invocationDecision(exchange, client, target);
                            applyRateLimitHeaders(exchange, decision);
                            if (!decision.allowed()) {
                                auditRejection(
                                        exchange,
                                        client,
                                        supplied,
                                        target,
                                        decision.httpStatus(),
                                        decision.code(),
                                        decision.message());
                                return writeError(
                                        exchange,
                                        HttpStatus.valueOf(decision.httpStatus()),
                                        decision.code(),
                                        decision.message());
                            }
                            exchange.getAttributes().put(CLIENT_ATTRIBUTE, client);
                            return chain.filter(exchange);
                        });
    }

    private ExternalAccessService.ApiClient authenticate(String supplied) {
        ExternalAccessService.ApiClient client = externalAccess.authenticate(supplied);
        return client == null && apiKeys.contains(supplied)
                ? externalAccess.legacyClient(supplied)
                : client;
    }

    private ExternalAccessService.AccessDecision invocationDecision(
            ServerWebExchange exchange,
            ExternalAccessService.ApiClient client,
            RequestTarget target) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
            return ExternalAccessService.AccessDecision.permit(0, 0, 0);
        }
        if (!Set.of("chat", "stream").contains(target.callMode())
                || target.agentId().isBlank()) {
            return ExternalAccessService.AccessDecision.permit(0, 0, 0);
        }
        return externalAccess.authorizeInvocation(
                client, target.agentId(), target.callMode());
    }

    private static RequestTarget requestTarget(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String prefix = "/api/v1/agents/";
        if (path.startsWith("/api/v1/workflows/") && path.endsWith("/run")) {
            String workflowId =
                    path.substring(
                            "/api/v1/workflows/".length(),
                            path.length() - "/run".length());
            return new RequestTarget("workflow:" + workflowId, "chat");
        }
        if (!path.startsWith(prefix)) return new RequestTarget("", "read");
        String suffix = path.substring(prefix.length());
        if (suffix.endsWith("/chat/stream")) {
            return new RequestTarget(
                    suffix.substring(0, suffix.length() - "/chat/stream".length()),
                    "stream");
        } else if (suffix.endsWith("/chat")) {
            return new RequestTarget(
                    suffix.substring(0, suffix.length() - "/chat".length()), "chat");
        }
        int slash = suffix.indexOf('/');
        return new RequestTarget(slash < 0 ? suffix : suffix.substring(0, slash), "read");
    }

    private void auditRejection(
            ServerWebExchange exchange,
            ExternalAccessService.ApiClient client,
            String supplied,
            RequestTarget target,
            int status,
            String code,
            String message) {
        externalAccess.recordRejectedRequest(
                client,
                supplied,
                target.agentId(),
                "chat".equals(target.callMode()) ? "sync" : target.callMode(),
                exchange.getRequest().getPath().value(),
                sourceIp(exchange),
                status,
                code,
                message);
    }

    private static String sourceIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? ""
                : remote.getAddress().getHostAddress();
    }

    private record RequestTarget(String agentId, String callMode) {}

    private static void applyRateLimitHeaders(
            ServerWebExchange exchange, ExternalAccessService.AccessDecision decision) {
        if (decision.limit() <= 0) return;
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(decision.limit()));
        headers.set("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        headers.set("X-RateLimit-Reset", String.valueOf(decision.retryAfterSeconds()));
        if (!decision.allowed() && decision.retryAfterSeconds() > 0) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        }
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
