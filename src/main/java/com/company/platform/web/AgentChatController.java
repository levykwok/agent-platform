/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.runtime.AgentEventEnvelope;
import com.company.platform.runtime.AgentRuntime;
import com.company.platform.runtime.ChatRequest;
import com.company.platform.runtime.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class AgentChatController {

    private final AgentRuntime runtime;

    public AgentChatController(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping
    public Mono<ChatResponse> chat(@PathVariable String agentId, @RequestBody ChatRequest request) {
        return runtime.chat(agentId, request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEventEnvelope>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest request) {
        return runtime.stream(agentId, request)
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }
}
