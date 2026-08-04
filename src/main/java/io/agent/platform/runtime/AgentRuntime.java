/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AgentRuntime {
    Mono<ChatResponse> chat(String agentId, ChatRequest request);

    Flux<AgentEventEnvelope> stream(String agentId, ChatRequest request);

    void evict(String agentId);
}
