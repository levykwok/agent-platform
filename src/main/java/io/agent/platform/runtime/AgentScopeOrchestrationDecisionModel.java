/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.adapter.agentscope.AgentExecutionPolicy;
import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinition;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ModelRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Production implementation backed by the configured AgentScope chat model. */
@Component
public final class AgentScopeOrchestrationDecisionModel implements OrchestrationDecisionModel {

    private static final long MAX_DECISION_TIMEOUT_MS = 45_000L;

    private final AgentScopeHarnessFactory harnessFactory;

    public AgentScopeOrchestrationDecisionModel(AgentScopeHarnessFactory harnessFactory) {
        this.harnessFactory = harnessFactory;
    }

    @Override
    public Mono<DecisionResponse> decide(AgentDefinition definition, String prompt) {
        String modelId = harnessFactory.resolveOrchestrationModel(definition);
        long timeoutMs =
                Math.min(
                        AgentExecutionPolicy.from(definition).timeoutMs(),
                        MAX_DECISION_TIMEOUT_MS);
        long started = System.nanoTime();
        UserMessage message =
                new UserMessage(List.of(TextBlock.builder().text(prompt == null ? "" : prompt).build()));
        return ModelRegistry.resolve(modelId)
                .stream(List.of(message), List.of(), null)
                .flatMapIterable(
                        response ->
                                response.getContent() == null
                                        ? List.<ContentBlock>of()
                                        : response.getContent())
                .filter(TextBlock.class::isInstance)
                .cast(TextBlock.class)
                .map(TextBlock::getText)
                .collect(java.util.stream.Collectors.joining())
                .timeout(Duration.ofMillis(timeoutMs))
                .map(text -> new DecisionResponse(text, modelId, elapsedMs(started)));
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
