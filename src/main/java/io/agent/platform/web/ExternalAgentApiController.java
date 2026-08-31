/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.runtime.AgentEventEnvelope;
import io.agent.platform.runtime.ChatRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Public, versioned Agent invocation API. Internal management fields are intentionally omitted. */
@RestController
@RequestMapping("/api/v1")
public class ExternalAgentApiController {

    private final AgentDefinitionRegistry registry;
    private final DurableAgentRunService durableRuns;
    private final PlatformCompatibilityState state;
    private final PlatformAssetAccessService assetAccess;
    private final ExternalAccessService externalAccess;

    public ExternalAgentApiController(
            AgentDefinitionRegistry registry,
            DurableAgentRunService durableRuns,
            PlatformCompatibilityState state,
            PlatformAssetAccessService assetAccess,
            ExternalAccessService externalAccess) {
        this.registry = registry;
        this.durableRuns = durableRuns;
        this.state = state;
        this.assetAccess = assetAccess;
        this.externalAccess = externalAccess;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "agent-api", "version", "v1");
    }

    @GetMapping("/agents")
    public Map<String, Object> agents(ServerWebExchange exchange) {
        var principal = client(exchange).principal();
        List<PublicAgent> items =
                registry.allPublished().stream()
                        .filter(AgentDefinition::enabled)
                        .filter(definition -> assetAccess.canRead("AGENT", definition.agentId(), principal))
                        .map(ExternalAgentApiController::publicAgent)
                        .toList();
        return Map.of("items", items, "count", items.size());
    }

    @GetMapping("/agents/{agentId}")
    public PublicAgent agent(
            @PathVariable("agentId") String agentId, ServerWebExchange exchange) {
        ensureAgent(agentId, client(exchange));
        return publicAgent(registry.findPublished(agentId).orElseThrow());
    }

    @PostMapping("/agents/{agentId}/chat")
    public Mono<ResponseEntity<Object>> chat(
            @PathVariable("agentId") String agentId,
            @RequestBody Map<String, Object> payload,
            ServerWebExchange exchange) {
        ExternalAccessService.ApiClient client = client(exchange);
        ExternalChatRequest request = parseRequest(payload);
        ensureAgent(agentId, client);
        ensureMessage(request);
        Invocation invocation = beginInvocation(agentId, "sync", request, client);
        StringBuilder answer = new StringBuilder();
        return durableRuns.stream(invocation.runId(), agentId, invocation.runtimeRequest())
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(
                        event -> {
                            state.appendRunEventFromEnvelope(invocation.runId(), event);
                            if (event.delta() != null) answer.append(event.delta());
                        })
                .then(
                        Mono.fromSupplier(
                                () -> {
                                    String text = answer.toString();
                                    state.finishRun(invocation.runId(), text);
                                    durableRuns.succeeded(invocation.runId());
                                    state.appendSessionMessage(
                                            agentId,
                                            invocation.sessionId(),
                                            invocation.ownerUserId(),
                                            "assistant",
                                            text,
                                            Map.of("external", true, "run_id", invocation.runId()));
                                    externalAccess.completeInvocation(
                                            invocation.requestId(),
                                            invocation.runId(),
                                            "SUCCEEDED",
                                            200,
                                            invocation.elapsedMs(),
                                            text,
                                            "",
                                            "");
                                    return ResponseEntity.<Object>ok(
                                            new ExternalChatResponse(
                                                    invocation.requestId(),
                                                    invocation.runId(),
                                                    agentId,
                                                    request.clientUserId(),
                                                    invocation.sessionId(),
                                                    text,
                                                    observeUrl(invocation.runId())));
                                }))
                .onErrorResume(
                        error -> {
                            finishFailure(invocation, error);
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                            .body((Object) invocationError(invocation, error)));
                        });
    }

    @PostMapping(value = "/agents/{agentId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEventEnvelope>> stream(
            @PathVariable("agentId") String agentId,
            @RequestBody Map<String, Object> payload,
            ServerWebExchange exchange) {
        ExternalAccessService.ApiClient client = client(exchange);
        ExternalChatRequest request = parseRequest(payload);
        ensureAgent(agentId, client);
        ensureMessage(request);
        Invocation invocation = beginInvocation(agentId, "stream", request, client);
        StringBuilder answer = new StringBuilder();
        AtomicBoolean terminal = new AtomicBoolean(false);
        AgentEventEnvelope startEvent = startedEvent(invocation);
        state.appendRunEventFromEnvelope(invocation.runId(), startEvent);
        Flux<AgentEventEnvelope> events =
                Flux.concat(
                                Flux.just(startEvent),
                                durableRuns.stream(
                                                invocation.runId(), agentId, invocation.runtimeRequest())
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .doOnNext(
                                                event -> {
                                                    state.appendRunEventFromEnvelope(
                                                            invocation.runId(), event);
                                                    if (event.delta() != null) answer.append(event.delta());
                                                }),
                                Flux.defer(
                                        () -> {
                                            String text = answer.toString();
                                            state.finishRun(invocation.runId(), text);
                                            durableRuns.succeeded(invocation.runId());
                                            state.appendSessionMessage(
                                                    agentId,
                                                    invocation.sessionId(),
                                                    invocation.ownerUserId(),
                                                    "assistant",
                                                    text,
                                                    Map.of(
                                                            "external",
                                                            true,
                                                            "run_id",
                                                            invocation.runId()));
                                            externalAccess.completeInvocation(
                                                    invocation.requestId(),
                                                    invocation.runId(),
                                                    "SUCCEEDED",
                                                    200,
                                                    invocation.elapsedMs(),
                                                    text,
                                                    "",
                                                    "");
                                            terminal.set(true);
                                            return Flux.just(completedEvent(invocation));
                                        }))
                        .onErrorResume(
                                error -> {
                                    if (terminal.compareAndSet(false, true)) {
                                        finishFailure(invocation, error);
                                    }
                                    return Flux.just(errorEvent(invocation, error));
                                })
                        .doOnCancel(
                                () -> {
                                    if (terminal.compareAndSet(false, true)) {
                                        durableRuns.requestCancellation(invocation.runId());
                                        state.cancelRun(
                                                invocation.runId(), "External SSE client disconnected");
                                        durableRuns.cancelled(invocation.runId());
                                        externalAccess.completeInvocation(
                                                invocation.requestId(),
                                                invocation.runId(),
                                                "CANCELLED",
                                                499,
                                                invocation.elapsedMs(),
                                                answer.toString(),
                                                "client_disconnected",
                                                "External SSE client disconnected");
                                    }
                                });
        return events.map(
                event ->
                        ServerSentEvent.<AgentEventEnvelope>builder(event)
                                .id(event.id())
                                .event(event.type())
                                .build());
    }

    private Invocation beginInvocation(
            String agentId,
            String mode,
            ExternalChatRequest request,
            ExternalAccessService.ApiClient client) {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
        String sessionId = request.sessionIdOrGenerated();
        String tenantId =
                client.principal() == null ? "external" : client.principal().orgId();
        String ownerUserId =
                client.principal() == null ? "external-legacy" : client.principal().userId();
        String runtimeUserId =
                "external:"
                        + safeIdentity(client.keyId(), 40)
                        + ":"
                        + safeIdentity(request.clientUserId(), 80);
        Map<String, Object> run = state.createRun(agentId, request.message(), ownerUserId);
        String runId = String.valueOf(run.get("run_id"));
        ChatRequest runtimeRequest =
                request.toRuntimeRequest(tenantId, runtimeUserId, sessionId, runId, agentId);
        state.appendSessionMessage(agentId, sessionId, ownerUserId, "user", request.message());
        Invocation invocation =
                new Invocation(
                        requestId,
                        runId,
                        sessionId,
                        ownerUserId,
                        runtimeRequest,
                        System.nanoTime());
        try {
            externalAccess.beginInvocation(
                    requestId,
                    client,
                    agentId,
                    mode,
                    sessionId,
                    externalAccess.safeRequest(request, runtimeUserId, tenantId, sessionId));
        } catch (RuntimeException error) {
            state.failRun(runId, error);
            throw error;
        }
        try {
            durableRuns.register(runId, agentId, runtimeRequest);
        } catch (RuntimeException error) {
            state.failRun(runId, error);
            externalAccess.completeInvocation(
                    requestId,
                    runId,
                    "FAILED",
                    500,
                    0,
                    "",
                    "run_registration_failed",
                    message(error));
            throw error;
        }
        return invocation;
    }

    private void finishFailure(Invocation invocation, Throwable error) {
        boolean cancelled = DurableAgentRunService.isCancellation(error);
        if (cancelled) {
            state.cancelRun(invocation.runId(), message(error));
            durableRuns.cancelled(invocation.runId());
        } else {
            state.failRun(invocation.runId(), error);
            durableRuns.failed(invocation.runId());
        }
        externalAccess.completeInvocation(
                invocation.requestId(),
                invocation.runId(),
                cancelled ? "CANCELLED" : "FAILED",
                cancelled ? 499 : 502,
                invocation.elapsedMs(),
                "",
                cancelled ? "cancelled" : "agent_invocation_failed",
                message(error));
    }

    private void ensureAgent(String agentId, ExternalAccessService.ApiClient client) {
        boolean available =
                registry.findPublished(agentId).filter(AgentDefinition::enabled).isPresent();
        if (!available || !assetAccess.canRead("AGENT", agentId, client.principal())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Published agent was not found: " + agentId);
        }
    }

    private static ExternalAccessService.ApiClient client(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ExternalApiKeyWebFilter.CLIENT_ATTRIBUTE);
        if (value instanceof ExternalAccessService.ApiClient client) return client;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid API key is required.");
    }

    private static ExternalChatRequest parseRequest(Map<String, Object> payload) {
        try {
            return ExternalChatRequest.from(payload);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    private static void ensureMessage(ExternalChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required.");
        }
    }

    private static Map<String, Object> invocationError(Invocation invocation, Throwable error) {
        return Map.of(
                "error",
                Map.of(
                        "code", "agent_invocation_failed",
                        "message", message(error),
                        "request_id", invocation.requestId(),
                        "run_id", invocation.runId(),
                        "observe_url", observeUrl(invocation.runId())));
    }

    private static AgentEventEnvelope startedEvent(Invocation invocation) {
        return event(
                "external_run_started",
                invocation,
                Map.of(
                        "request_id", invocation.requestId(),
                        "run_id", invocation.runId(),
                        "observe_url", observeUrl(invocation.runId())));
    }

    private static AgentEventEnvelope completedEvent(Invocation invocation) {
        return event(
                "external_run_completed",
                invocation,
                Map.of(
                        "request_id", invocation.requestId(),
                        "run_id", invocation.runId(),
                        "status", "succeeded",
                        "duration_ms", invocation.elapsedMs(),
                        "observe_url", observeUrl(invocation.runId())));
    }

    private static AgentEventEnvelope errorEvent(Invocation invocation, Throwable error) {
        return event(
                "error",
                invocation,
                Map.of(
                        "code", "agent_invocation_failed",
                        "message", message(error),
                        "request_id", invocation.requestId(),
                        "run_id", invocation.runId(),
                        "observe_url", observeUrl(invocation.runId())));
    }

    private static AgentEventEnvelope event(
            String type, Invocation invocation, Map<String, Object> payload) {
        return new AgentEventEnvelope(
                type + "_" + UUID.randomUUID(),
                type,
                Instant.now().toString(),
                "platform",
                null,
                payload);
    }

    private static String safeIdentity(String value, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        normalized = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.isBlank()) normalized = "anonymous";
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    private static String message(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.isBlank()
                ? "The Agent could not complete the request."
                : value;
    }

    private static String observeUrl(String runId) {
        return "/platform/live/runs?run_id=" + runId;
    }

    private static PublicAgent publicAgent(AgentDefinition definition) {
        return new PublicAgent(
                definition.agentId(),
                definition.version(),
                definition.name(),
                definition.orchestration().mode().name().toLowerCase(),
                List.of("chat", "stream"));
    }

    private record Invocation(
            String requestId,
            String runId,
            String sessionId,
            String ownerUserId,
            ChatRequest runtimeRequest,
            long startedNanos) {
        long elapsedMs() {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        }
    }

    public record PublicAgent(
            String agentId,
            String version,
            String name,
            String orchestration,
            List<String> capabilities) {}

    public record ExternalChatResponse(
            String requestId,
            String runId,
            String agentId,
            String userId,
            String sessionId,
            String text,
            String observeUrl) {}
}
