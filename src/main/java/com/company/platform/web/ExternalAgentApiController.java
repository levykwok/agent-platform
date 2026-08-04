/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.control.AgentDefinition;
import com.company.platform.control.AgentDefinitionRegistry;
import com.company.platform.runtime.AgentEventEnvelope;
import com.company.platform.runtime.AgentRuntime;
import com.company.platform.runtime.ChatResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Public, versioned Agent invocation API. Internal management fields are intentionally omitted. */
@RestController
@RequestMapping("/api/v1")
public class ExternalAgentApiController {

    private final AgentDefinitionRegistry registry;
    private final AgentRuntime runtime;

    public ExternalAgentApiController(AgentDefinitionRegistry registry, AgentRuntime runtime) {
        this.registry = registry;
        this.runtime = runtime;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "agent-api", "version", "v1");
    }

    @GetMapping("/agents")
    public Map<String, Object> agents() {
        List<PublicAgent> items =
                registry.allPublished().stream()
                        .filter(AgentDefinition::enabled)
                        .map(ExternalAgentApiController::publicAgent)
                        .toList();
        return Map.of("items", items, "count", items.size());
    }

    @GetMapping("/agents/{agentId}")
    public PublicAgent agent(@PathVariable String agentId) {
        return registry
                .findPublished(agentId)
                .filter(AgentDefinition::enabled)
                .map(ExternalAgentApiController::publicAgent)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Published agent was not found."));
    }

    @PostMapping("/agents/{agentId}/chat")
    public Mono<ResponseEntity<Object>> chat(
            @PathVariable String agentId, @RequestBody Map<String, Object> payload) {
        ExternalChatRequest request = ExternalChatRequest.from(payload);
        ensureAgent(agentId);
        ensureMessage(request);
        String requestId = "req_" + UUID.randomUUID();
        return runtime.chat(agentId, request.toRuntimeRequest())
                .map(
                        response ->
                                ResponseEntity.<Object>ok(
                                        ExternalChatResponse.from(requestId, response)))
                .onErrorResume(
                        error ->
                                Mono.just(
                                        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                                .body((Object) invocationError(requestId))));
    }

    @PostMapping(value = "/agents/{agentId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEventEnvelope>> stream(
            @PathVariable String agentId, @RequestBody Map<String, Object> payload) {
        ExternalChatRequest request = ExternalChatRequest.from(payload);
        ensureAgent(agentId);
        ensureMessage(request);
        return runtime.stream(agentId, request.toRuntimeRequest())
                .map(
                        event ->
                                ServerSentEvent.<AgentEventEnvelope>builder(event)
                                        .id(event.id())
                                        .event(event.type())
                                        .build())
                .onErrorResume(
                        error ->
                                Flux.just(
                                        ServerSentEvent.<AgentEventEnvelope>builder(errorEvent())
                                                .event("error")
                                                .build()));
    }

    private void ensureAgent(String agentId) {
        if (registry.findPublished(agentId).filter(AgentDefinition::enabled).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Published agent was not found: " + agentId);
        }
    }

    private static void ensureMessage(ExternalChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required.");
        }
    }

    private static Map<String, Object> invocationError(String requestId) {
        return Map.of(
                "error",
                Map.of(
                        "code", "agent_invocation_failed",
                        "message", "The Agent could not complete the request.",
                        "request_id", requestId));
    }

    private static AgentEventEnvelope errorEvent() {
        return new AgentEventEnvelope(
                "error_" + UUID.randomUUID(),
                "error",
                Instant.now().toString(),
                "platform",
                null,
                Map.of(
                        "code", "agent_invocation_failed",
                        "message", "The Agent could not complete the request."));
    }

    private static PublicAgent publicAgent(AgentDefinition definition) {
        return new PublicAgent(
                definition.agentId(),
                definition.version(),
                definition.name(),
                definition.orchestration().mode().name().toLowerCase(),
                List.of("chat", "stream"));
    }

    public record PublicAgent(
            String agentId,
            String version,
            String name,
            String orchestration,
            List<String> capabilities) {}

    public record ExternalChatResponse(
            String requestId,
            String agentId,
            String userId,
            String sessionId,
            String text) {

        private static ExternalChatResponse from(String requestId, ChatResponse response) {
            return new ExternalChatResponse(
                    requestId,
                    response.agentId(),
                    response.userId(),
                    response.sessionId(),
                    response.text());
        }
    }
}
