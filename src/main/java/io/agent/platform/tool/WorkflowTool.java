/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.control.WorkflowToolRegistration;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.runtime.ChatRequest;
import io.agent.platform.runtime.WorkflowInvocationContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import reactor.core.publisher.Mono;

/** AgentScope tool adapter for a published Workflow asset. */
public final class WorkflowTool extends ToolBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkflowAsset workflow;
    private final WorkflowToolRegistration registration;
    private final AgentRuntime runtime;

    public WorkflowTool(WorkflowAsset workflow, WorkflowToolRegistration registration, AgentRuntime runtime) {
        super(
                ToolBase.builder()
                        .name(registration.name())
                        .description(
                                registration.description().isBlank()
                                        ? workflow.name()
                                        : registration.description())
                        .inputSchema(normalizeSchema(registration.inputSchema()))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.workflow = workflow;
        this.registration = registration;
        this.runtime = runtime;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> args = param == null || param.getInput() == null ? Map.of() : param.getInput();
        String message = message(args);
        return Mono.deferContextual(
                        context -> {
                            ChatRequest caller =
                                    callerRequest(param, context)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "Workflow Tool caller context is unavailable"));
                            var childContext =
                                    caller.taskContext()
                                            .child(
                                                    caller.taskContext().targetAgentId(),
                                                    "workflow:" + workflow.workflowId(),
                                                    "tool:" + registration.toolId());
                            ChatRequest child =
                                    new ChatRequest(
                                            caller.tenantId(),
                                            caller.userId(),
                                            caller.sessionId() + "_" + registration.toolId(),
                                            message,
                                            childContext,
                                            java.util.List.of());
                            return runtime.workflow(workflow, child);
                        })
                .map(response -> ToolResultBlock.text(response.text() == null ? "" : response.text()))
                .onErrorResume(error -> Mono.just(ToolResultBlock.error(error.getMessage())));
    }

    private static java.util.Optional<ChatRequest> callerRequest(
            ToolCallParam param, reactor.util.context.ContextView context) {
        if (param != null && param.getRuntimeContext() != null) {
            ChatRequest request = param.getRuntimeContext().get(ChatRequest.class);
            if (request != null) return java.util.Optional.of(request);
        }
        return WorkflowInvocationContext.request(context);
    }

    private String message(Map<String, Object> args) {
        Object query = args.get("query");
        if (query != null && !String.valueOf(query).isBlank()) {
            return String.valueOf(query);
        }
        try {
            return JSON.writeValueAsString(args);
        } catch (Exception ignored) {
            return String.valueOf(args);
        }
    }

    private static Map<String, Object> normalizeSchema(Map<String, Object> schema) {
        return schema == null || schema.isEmpty()
                ? Map.of("type", "object", "properties", Map.of())
                : schema;
    }
}
