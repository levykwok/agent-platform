/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AgentRuntime {
    Mono<ChatResponse> chat(String agentId, ChatRequest request);

    Flux<AgentEventEnvelope> stream(String agentId, ChatRequest request);

    void evict(String agentId);
}
