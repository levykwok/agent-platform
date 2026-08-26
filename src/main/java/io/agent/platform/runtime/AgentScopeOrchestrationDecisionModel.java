/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.adapter.agentscope.AgentExecutionPolicy;
import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.OrchestrationMode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Production implementation backed by the configured AgentScope chat model. */
@Component
public final class AgentScopeOrchestrationDecisionModel implements OrchestrationDecisionModel {

    private static final long MAX_DECISION_TIMEOUT_MS = 30_000L;
    private static final GenerateOptions DECISION_OPTIONS =
            GenerateOptions.builder().temperature(0.0).maxTokens(512).build();
    private static final GenerateOptions ROUTER_NO_THINKING_OPTIONS =
            GenerateOptions.builder()
                    .temperature(0.0)
                    .maxTokens(512)
                    .additionalBodyParam("enable_thinking", false)
                    .build();

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
        AtomicLong inputTokens = new AtomicLong();
        AtomicLong outputTokens = new AtomicLong();
        UserMessage message =
                new UserMessage(List.of(TextBlock.builder().text(prompt == null ? "" : prompt).build()));
        return ModelRegistry.resolve(modelId)
                .stream(List.of(message), List.of(), decisionOptions(definition))
                .doOnNext(
                        response -> {
                            if (response.getUsage() != null) {
                                inputTokens.accumulateAndGet(
                                        response.getUsage().getInputTokens(), Math::max);
                                outputTokens.accumulateAndGet(
                                        response.getUsage().getOutputTokens(), Math::max);
                            }
                        })
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
                .map(
                        text ->
                                new DecisionResponse(
                                        text,
                                        modelId,
                                        elapsedMs(started),
                                        inputTokens.get(),
                                        outputTokens.get()));
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    static GenerateOptions decisionOptions(AgentDefinition definition) {
        if (definition != null
                && definition.orchestration().mode() == OrchestrationMode.ROUTER
                && definition.orchestration().routerDisableThinking()) {
            return ROUTER_NO_THINKING_OPTIONS;
        }
        return DECISION_OPTIONS;
    }
}
