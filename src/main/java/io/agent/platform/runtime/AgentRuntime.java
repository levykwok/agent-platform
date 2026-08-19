/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.control.WorkflowAsset;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AgentRuntime {
    Mono<ChatResponse> chat(String agentId, ChatRequest request);

    Mono<ChatResponse> workflow(WorkflowAsset workflow, ChatRequest request);

    Flux<AgentEventEnvelope> stream(String agentId, ChatRequest request);

    Flux<AgentEventEnvelope> workflowStream(WorkflowAsset workflow, ChatRequest request);

    void evict(String agentId);

    default List<Map<String, Object>> runtimeToolManifest(
            String agentId, String tenantId, String userId) {
        return List.of();
    }
}
