/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.adapter.agentscope;

import io.agent.platform.web.PlatformCompatibilityState;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class LlmCallAuditMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(LlmCallAuditMiddleware.class);

    private final PlatformCompatibilityState platformState;
    private final String agentId;
    private final String configuredModelId;

    public LlmCallAuditMiddleware(
            PlatformCompatibilityState platformState, String agentId, String configuredModelId) {
        this.platformState = platformState;
        this.agentId = safe(agentId);
        this.configuredModelId = safe(configuredModelId);
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof ModelCallEndEvent modelCallEndEvent) {
                                record(input, ctx, modelCallEndEvent);
                            }
                        });
    }

    private void record(ModelCallInput input, RuntimeContext ctx, ModelCallEndEvent event) {
        if (platformState == null) {
            return;
        }
        try {
            ChatUsage usage = event.getUsage();
            String resolvedModel = resolveModelName(input);
            String configuredModel = configuredModelId;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agent_id", agentId);
            payload.put("user_id", safe(ctx == null ? null : ctx.getUserId()));
            payload.put("session_id", safe(ctx == null ? null : ctx.getSessionId()));
            payload.put("tenant_id", safe(ctx == null ? null : ctx.get("tenant_id", String.class)));
            payload.put("reply_id", safe(event.getReplyId()));
            payload.put("configured_model", configuredModel);
            payload.put("model_name", resolvedModel);
            payload.put("model_matches_configured", matchesModel(resolvedModel, configuredModel));
            payload.put("model_instance", resolveModelClass(input));
            payload.put("provider_type", resolveProviderType(input, ctx));
            payload.put("input_tokens", usage == null ? 0 : usage.getInputTokens());
            payload.put("output_tokens", usage == null ? 0 : usage.getOutputTokens());
            payload.put("total_tokens", usage == null ? 0 : usage.getTotalTokens());
            payload.put("usage_time_seconds", usage == null ? null : usage.getTime());
            payload.put("usage_available", usage != null);
            payload.put("stream", resolveStream(input));
            payload.put("recorded_at", Instant.now().toString());
            platformState.appendAuditEvent("llm.call", agentId, payload);
            log.info(
                    "llm call {} | model={} | input_tokens={} | output_tokens={} | total_tokens={}",
                    agentId,
                    resolvedModel,
                    usage == null ? "n/a" : String.valueOf(usage.getInputTokens()),
                    usage == null ? "n/a" : String.valueOf(usage.getOutputTokens()),
                    usage == null ? "n/a" : String.valueOf(usage.getTotalTokens()));
        } catch (Exception e) {
            log.warn("Failed to audit llm call for agent {}: {}", agentId, e.getMessage());
        }
    }

    private String resolveModelName(ModelCallInput input) {
        if (input == null) {
            return "";
        }
        GenerateOptions options = input.options();
        if (options != null
                && options.getModelName() != null
                && !options.getModelName().isBlank()) {
            return options.getModelName();
        }
        Model model = input.model();
        if (model != null && model.getModelName() != null && !model.getModelName().isBlank()) {
            return model.getModelName();
        }
        return "";
    }

    private String resolveModelClass(ModelCallInput input) {
        return input != null && input.model() != null ? input.model().getClass().getName() : "";
    }

    private static Boolean matchesModel(String calledModel, String configuredModel) {
        String called = calledModel == null ? "" : calledModel.strip();
        String configured = configuredModel == null ? "" : configuredModel.strip();
        return called.isBlank() || configured.isBlank() ? null : called.equals(configured);
    }

    private String resolveProviderType(ModelCallInput input, RuntimeContext ctx) {
        String providerFromContext =
                ctx == null ? "" : safe(ctx.get("provider_type", String.class));
        if (!providerFromContext.isBlank()) {
            return providerFromContext;
        }
        Model model = input == null ? null : input.model();
        return model == null ? "" : model.getClass().getSimpleName();
    }

    private Boolean resolveStream(ModelCallInput input) {
        if (input == null || input.options() == null) {
            return null;
        }
        return input.options().getStream();
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
