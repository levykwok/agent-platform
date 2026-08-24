/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.control.AgentDefinition;
import reactor.core.publisher.Mono;

/** Stateless, tool-free LLM call used only for orchestration decisions. */
public interface OrchestrationDecisionModel {

    Mono<DecisionResponse> decide(AgentDefinition definition, String prompt);

    record DecisionResponse(
            String text,
            String modelId,
            long durationMs,
            long inputTokens,
            long outputTokens) {

        public DecisionResponse(String text, String modelId, long durationMs) {
            this(text, modelId, durationMs, 0L, 0L);
        }

        public DecisionResponse {
            text = text == null ? "" : text;
            modelId = modelId == null ? "" : modelId;
            durationMs = Math.max(0L, durationMs);
            inputTokens = Math.max(0L, inputTokens);
            outputTokens = Math.max(0L, outputTokens);
        }

        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
