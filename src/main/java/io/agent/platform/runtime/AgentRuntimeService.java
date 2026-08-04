/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.RouteRule;
import io.agent.platform.control.SubagentBinding;
import io.agent.platform.control.WorkflowStep;
import io.agent.platform.control.WorkflowTransition;
import io.agent.platform.runtime.protocol.TaskContext;
import io.agent.platform.runtime.protocol.TaskRequest;
import io.agent.platform.runtime.protocol.TaskResult;
import io.agent.platform.runtime.protocol.TaskStatus;
import io.agent.platform.web.PlatformCompatibilityState;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Service
public class AgentRuntimeService implements AgentRuntime {

    private static final int PLATFORM_COMPACTION_TRIGGER_MESSAGES = 10;

    private final AgentDefinitionRegistry registry;
    private final AgentScopeHarnessFactory harnessFactory;
    private final PlatformCompatibilityState platformState;
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public AgentRuntimeService(
            AgentDefinitionRegistry registry,
            AgentScopeHarnessFactory harnessFactory,
            PlatformCompatibilityState platformState) {
        this.registry = registry;
        this.harnessFactory = harnessFactory;
        this.platformState = platformState;
    }

    @Override
    public Mono<ChatResponse> chat(String agentId, ChatRequest request) {
        AgentDefinition definition = definition(agentId);
        return enrichWithVision(definition, request)
                .flatMap(enriched -> executeDefinition(definition, enriched));
    }

    @Override
    public Flux<AgentEventEnvelope> stream(String agentId, ChatRequest request) {
        AgentDefinition definition = definition(agentId);
        if (!request.hasImages()) {
            return streamDefinition(definition, request);
        }
        return Flux.concat(
                Flux.just(
                        runtimeEvent(
                                definition.agentId(),
                                "vlm_start",
                                "Analyzing images with the vlm slot",
                                Map.of("slot_key", "vlm", "image_count", request.images().size()))),
                enrichWithVision(definition, request)
                        .flatMapMany(
                                enriched ->
                                        Flux.concat(
                                                Flux.just(
                                                        runtimeEvent(
                                                                definition.agentId(),
                                                                "vlm_complete",
                                                                "Visual context is ready for the qa"
                                                                        + " agent",
                                                                Map.of("slot_key", "vlm"))),
                                                streamDefinition(definition, enriched))));
    }

    private Mono<ChatResponse> executeDefinition(AgentDefinition definition, ChatRequest request) {
        return switch (definition.orchestration().mode()) {
            case ROUTER -> executeDefinition(route(definition, request), request);
            case WORKFLOW -> runWorkflow(definition, request);
            case SUPERVISOR -> runSupervisor(definition, request);
            case SINGLE -> runSingle(definition, request);
        };
    }

    private Flux<AgentEventEnvelope> streamDefinition(
            AgentDefinition definition, ChatRequest request) {
        RuntimeContext context = runtimeContext(request);
        if (definition.orchestration().mode() == OrchestrationMode.ROUTER) {
            RouteDecision decision = routeDecision(definition, request);
            return Flux.concat(
                    Flux.just(routerEvent(definition, decision)),
                    streamDefinition(decision.target(), request));
        }
        if (definition.orchestration().mode() == OrchestrationMode.WORKFLOW) {
            return streamWorkflow(definition, request);
        }
        if (definition.orchestration().mode() == OrchestrationMode.SUPERVISOR) {
            return streamSupervisor(definition, request);
        }
        return Flux.concat(
                Flux.just(singleEvent(definition)),
                capabilityEvents(definition, request.tenantId(), request.userId()),
                streamAgent(
                        definition,
                        request.message(),
                        context,
                        request.taskContext(),
                        request.images()));
    }

    @Override
    public void evict(String agentId) {
        String prefix = agentId + ":";
        agentCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private Mono<ChatResponse> runSingle(AgentDefinition definition, ChatRequest request) {
        return runSingleTask(definition, request)
                .map(result -> response(definition.agentId(), request, result.content()));
    }

    private Mono<TaskResult> runSingleTask(AgentDefinition definition, ChatRequest request) {
        RuntimeContext context = runtimeContext(request);
        TaskRequest taskRequest =
                new TaskRequest(
                        request.taskContext().withTarget(definition.agentId()),
                        Map.of("text", safe(request.message(), "")));
        return callAgent(definition, request, request.message(), context)
                .map(
                        msg ->
                                new TaskResult(
                                        taskRequest.context().taskId(),
                                        TaskStatus.COMPLETED,
                                        msg.getTextContent(),
                                        Map.of(),
                                        null,
                                        Map.of()));
    }

    private Mono<ChatResponse> runSupervisor(AgentDefinition definition, ChatRequest request) {
        SubagentBinding binding = selectSubagent(definition, request.message());
        if (binding == null) {
            return runSingle(definition, request);
        }
        AgentDefinition target = definition(binding.targetAgentId());
        return callAgent(
                        target,
                        request,
                        subagentMessage(binding, request.message()),
                        subagentContext(request, binding))
                .flatMap(
                        subagentReply ->
                                callAgent(
                                        definition,
                                        supervisorSummaryMessage(
                                                definition,
                                                binding,
                                                target,
                                                request.message(),
                                                subagentReply),
                                        runtimeContext(request)))
                .map(msg -> response(definition.agentId(), request, msg));
    }

    private Mono<ChatResponse> runWorkflow(AgentDefinition definition, ChatRequest request) {
        if (definition.orchestration().workflow().isEmpty()) {
            return Mono.error(
                    new AgentRuntimeException(
                            "Workflow agent has no steps: " + definition.agentId()));
        }
        return runWorkflowSteps(
                        definition, request, 0, request.message(), new java.util.HashSet<>())
                .map(
                        text ->
                                new ChatResponse(
                                        definition.agentId(),
                                        userKey(request),
                                        sessionKey(request),
                                        text));
    }

    private Mono<String> runWorkflowSteps(
            AgentDefinition definition,
            ChatRequest request,
            int index,
            String input,
            java.util.Set<String> visited) {
        List<WorkflowStep> steps = definition.orchestration().workflow();
        if (index >= steps.size()) {
            return Mono.just(input);
        }
        WorkflowStep step = steps.get(index);
        if (!visited.add(step.stepId())) {
            return Mono.error(
                    new AgentRuntimeException("Workflow cycle detected at step: " + step.stepId()));
        }
        return runWorkflowStep(step, request, input)
                .flatMap(
                        rawOutput -> {
                            WorkflowStepOutput output = WorkflowStepOutput.parse(rawOutput);
                            return runWorkflowSteps(
                                    definition,
                                    request,
                                    nextWorkflowIndex(steps, index, output.status()),
                                    output.content(),
                                    visited);
                        });
    }

    private Mono<String> runWorkflowStep(WorkflowStep step, ChatRequest request, String input) {
        AgentDefinition stepAgent = definition(step.agentId());
        String message = workflowStepMessage(step, input);
        ChatRequest stepRequest =
                new ChatRequest(
                        request.tenantId(),
                        request.userId(),
                        sessionKey(request) + "_" + pathSafe(step.stepId(), "step"),
                        message,
                        request.taskContext()
                                .child(
                                        request.taskContext().targetAgentId(),
                                        stepAgent.agentId(),
                                        step.stepId()),
                        request.images());
        return withStepPolicy(
                step, input, executeDefinition(stepAgent, stepRequest).map(ChatResponse::text));
    }

    static Mono<String> withStepPolicy(WorkflowStep step, String input, Mono<String> action) {
        Mono<String> guarded = action;
        if (step.timeoutMs() != null) {
            guarded = guarded.timeout(Duration.ofMillis(step.timeoutMs()));
        }
        if (step.maxRetries() > 0) {
            guarded =
                    guarded.retryWhen(Retry.fixedDelay(step.maxRetries(), Duration.ofMillis(100)));
        }
        return guarded.onErrorResume(
                error ->
                        switch (step.failurePolicy()) {
                            case SKIP, USE_INPUT -> Mono.just(input);
                            case FAIL_FAST -> Mono.error(error);
                        });
    }

    static Flux<AgentEventEnvelope> withFluxStepPolicy(
            WorkflowStep step, String input, Flux<AgentEventEnvelope> action) {
        Flux<AgentEventEnvelope> guarded = action;
        if (step.timeoutMs() != null) {
            guarded = guarded.timeout(Duration.ofMillis(step.timeoutMs()));
        }
        if (step.maxRetries() > 0) {
            guarded =
                    guarded.retryWhen(Retry.fixedDelay(step.maxRetries(), Duration.ofMillis(100)));
        }
        return guarded.onErrorResume(
                error ->
                        switch (step.failurePolicy()) {
                            case SKIP, USE_INPUT ->
                                    Flux.just(
                                            new AgentEventEnvelope(
                                                    "workflow_fallback_"
                                                            + Instant.now().toEpochMilli(),
                                                    "workflow_step_fallback",
                                                    Instant.now().toString(),
                                                    step.agentId(),
                                                    input,
                                                    Map.of(
                                                            "summary",
                                                            "Workflow step "
                                                                    + safe(step.stepId(), "step")
                                                                    + " failed; using previous"
                                                                    + " input",
                                                            "workflow",
                                                            true,
                                                            "fallback",
                                                            true)));
                            case FAIL_FAST -> Flux.error(error);
                        });
    }

    private Flux<AgentEventEnvelope> streamWorkflow(
            AgentDefinition definition, ChatRequest request) {
        List<WorkflowStep> steps = definition.orchestration().workflow();
        if (steps.isEmpty()) {
            return Flux.error(
                    new AgentRuntimeException(
                            "Workflow agent has no steps: " + definition.agentId()));
        }
        return Flux.concat(
                Flux.just(
                        workflowEvent(
                                definition.agentId(),
                                "workflow_start",
                                "Running workflow " + definition.agentId())),
                streamWorkflowStep(steps, 0, request, request.message()));
    }

    private Flux<AgentEventEnvelope> streamWorkflowStep(
            List<WorkflowStep> steps, int index, ChatRequest request, String input) {
        WorkflowStep step = steps.get(index);
        if (index == steps.size() - 1 && steps.get(index).transitions().isEmpty()) {
            return streamWorkflowFinalStep(step, request, input);
        }
        return Flux.concat(
                Flux.just(
                        workflowEvent(
                                step.agentId(),
                                "workflow_step_start",
                                "Start workflow step "
                                        + safe(step.stepId(), "step")
                                        + " -> "
                                        + step.agentId())),
                workflowAgentSummaryEvents(step.agentId(), "start"),
                runWorkflowStep(step, request, input)
                        .flatMapMany(
                                output -> {
                                    WorkflowStepOutput parsed = WorkflowStepOutput.parse(output);
                                    return Flux.concat(
                                            workflowAgentSummaryEvents(step.agentId(), "end"),
                                            Flux.just(
                                                    workflowEvent(
                                                            step.agentId(),
                                                            "workflow_step_end",
                                                            "Finished workflow step "
                                                                    + safe(step.stepId(), "step")
                                                                    + " -> "
                                                                    + step.agentId())),
                                            streamWorkflowStep(
                                                    steps,
                                                    nextWorkflowIndex(
                                                            steps, index, parsed.status()),
                                                    request,
                                                    parsed.content()));
                                }));
    }

    static int nextWorkflowIndex(List<WorkflowStep> steps, int index, String status) {
        WorkflowStep step = steps.get(index);
        String normalized = safe(status, "").toLowerCase();
        for (WorkflowTransition transition : step.transitions()) {
            if (!transition.defaultTransition()
                    && !transition.when().isBlank()
                    && normalized.equals(transition.when().trim().toLowerCase())) {
                return findWorkflowStep(steps, transition.nextStepId(), index + 1);
            }
        }
        for (WorkflowTransition transition : step.transitions()) {
            if (transition.defaultTransition()) {
                return findWorkflowStep(steps, transition.nextStepId(), index + 1);
            }
        }
        return index + 1;
    }

    private static int findWorkflowStep(List<WorkflowStep> steps, String stepId, int fallback) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepId().equals(stepId)) {
                return i;
            }
        }
        return fallback;
    }

    private Flux<AgentEventEnvelope> streamWorkflowFinalStep(
            WorkflowStep step, ChatRequest request, String input) {
        AgentDefinition stepAgent = definition(step.agentId());
        String message = workflowStepMessage(step, input);
        ChatRequest stepRequest =
                new ChatRequest(
                        request.tenantId(),
                        request.userId(),
                        sessionKey(request) + "_" + pathSafe(step.stepId(), "step"),
                        message,
                        request.taskContext()
                                .child(
                                        request.taskContext().targetAgentId(),
                                        stepAgent.agentId(),
                                        step.stepId()),
                        request.images());
        RuntimeContext context = runtimeContext(stepRequest);
        return Flux.concat(
                Flux.just(
                        workflowEvent(
                                stepAgent.agentId(),
                                "workflow_final_step",
                                "Streaming final workflow step "
                                        + safe(step.stepId(), "step")
                                        + " -> "
                                        + stepAgent.agentId())),
                withFluxStepPolicy(step, input, streamDefinition(stepAgent, stepRequest)));
    }

    private Flux<AgentEventEnvelope> streamSupervisor(
            AgentDefinition definition, ChatRequest request) {
        SubagentBinding binding = selectSubagent(definition, request.message());
        if (binding == null) {
            return Flux.concat(
                    Flux.just(supervisorEvent(definition)),
                    capabilityEvents(definition, request.tenantId(), request.userId()),
                    streamAgent(
                            definition,
                            request.message(),
                            runtimeContext(request),
                            request.taskContext(),
                            request.images()));
        }
        AgentDefinition target = definition(binding.targetAgentId());
        return Flux.concat(
                Flux.just(
                        supervisorEvent(definition),
                        supervisorSelectionEvent(definition, binding, target)),
                capabilityEvents(target, request.tenantId(), request.userId()),
                workflowAgentSummaryEvents(target.agentId(), "start"),
                callAgent(
                                target,
                                request,
                                subagentMessage(binding, request.message()),
                                subagentContext(request, binding))
                        .flatMapMany(
                                subagentReply ->
                                        Flux.concat(
                                                workflowAgentSummaryEvents(target.agentId(), "end"),
                                                Flux.just(
                                                        subagentResultEvent(
                                                                definition,
                                                                binding,
                                                                target,
                                                                subagentReply)),
                                                capabilityEvents(
                                                        definition,
                                                        request.tenantId(),
                                                        request.userId()),
                                                streamAgent(
                                                        definition,
                                                        supervisorSummaryMessage(
                                                                definition,
                                                                binding,
                                                                target,
                                                                request.message(),
                                                                subagentReply),
                                                        runtimeContext(request),
                                                        request.taskContext(),
                                                        request.images()))));
    }

    private Flux<AgentEventEnvelope> workflowAgentSummaryEvents(String agentId, String phase) {
        if ("start".equals(phase)) {
            return Flux.just(
                    runtimeEvent(
                            agentId,
                            "agent_start",
                            "Start agent " + agentId,
                            Map.of("agent_id", agentId, "workflow", true)),
                    runtimeEvent(
                            agentId,
                            "model_call_start",
                            "Model call started for " + agentId,
                            Map.of("agent_id", agentId, "workflow", true)),
                    runtimeEvent(
                            agentId,
                            "text_block_start",
                            "Text generation started for " + agentId,
                            Map.of("agent_id", agentId, "workflow", true)));
        }
        return Flux.just(
                runtimeEvent(
                        agentId,
                        "text_block_end",
                        "Text generation finished for " + agentId,
                        Map.of("agent_id", agentId, "workflow", true)),
                runtimeEvent(
                        agentId,
                        "model_call_end",
                        "Model call finished for " + agentId,
                        Map.of("agent_id", agentId, "workflow", true)),
                runtimeEvent(
                        agentId,
                        "agent_result",
                        "Agent produced result " + agentId,
                        Map.of("agent_id", agentId, "workflow", true)),
                runtimeEvent(
                        agentId,
                        "agent_end",
                        "Agent finished " + agentId,
                        Map.of("agent_id", agentId, "workflow", true)));
    }

    private SubagentBinding selectSubagent(AgentDefinition definition, String message) {
        List<SubagentBinding> bindings = definition.orchestration().subagents();
        if (bindings.isEmpty()) {
            return null;
        }
        if (bindings.size() == 1) {
            return bindings.get(0);
        }
        String normalized = safe(message, "").toLowerCase();
        SubagentBinding best = null;
        int bestScore = 0;
        for (SubagentBinding binding : bindings) {
            AgentDefinition target = definition(binding.targetAgentId());
            int score =
                    matchScore(normalized, binding.bindingId())
                            + matchScore(normalized, binding.role())
                            + matchScore(normalized, binding.description())
                            + matchScore(normalized, target.agentId())
                            + matchScore(normalized, target.name());
            if (score > bestScore) {
                bestScore = score;
                best = binding;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private int matchScore(String normalizedMessage, String candidate) {
        String text = safe(candidate, "").toLowerCase();
        if (normalizedMessage.isBlank() || text.isBlank()) {
            return 0;
        }
        if (normalizedMessage.contains(text)) {
            return 4;
        }
        int score = 0;
        for (String token : text.split("[\\s,，;；、/|]+")) {
            if (token.length() >= 2 && normalizedMessage.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private String subagentMessage(SubagentBinding binding, String userMessage) {
        StringBuilder message = new StringBuilder();
        if (!safe(binding.role(), "").isBlank()) {
            message.append("Role: ").append(binding.role()).append("\n");
        }
        if (!safe(binding.description(), "").isBlank()) {
            message.append("Task scope: ").append(binding.description()).append("\n");
        }
        if (!message.isEmpty()) {
            message.append("\n");
        }
        message.append(userMessage);
        return message.toString();
    }

    private String supervisorSummaryMessage(
            AgentDefinition supervisor,
            SubagentBinding binding,
            AgentDefinition target,
            String userMessage,
            Msg subagentReply) {
        String text = subagentReply == null ? "" : safe(subagentReply.getTextContent(), "");
        return """
        You are the supervisor agent. A subagent has completed its part of the task.

        User request:
        %s

        Subagent:
        - binding_id: %s
        - target_agent_id: %s
        - role: %s
        - description: %s

        Subagent result:
        %s

        Produce the final answer for the user. Preserve useful details from the subagent, \
        resolve contradictions if any, and do not mention internal orchestration unless it \
        helps the user understand the result.
        """
                .formatted(
                        safe(userMessage, ""),
                        safe(binding.bindingId(), ""),
                        safe(target.agentId(), supervisor.agentId()),
                        safe(binding.role(), ""),
                        safe(binding.description(), ""),
                        text);
    }

    private Mono<Msg> callAgent(
            AgentDefinition definition, String message, RuntimeContext context) {
        return callAgent(definition, List.of(), message, context);
    }

    private Mono<Msg> callAgent(
            AgentDefinition definition,
            ChatRequest request,
            String message,
            RuntimeContext context) {
        return callAgent(definition, request.images(), message, context);
    }

    private Mono<Msg> callAgent(
            AgentDefinition definition,
            List<ChatImage> images,
            String message,
            RuntimeContext context) {
        String runId = UUID.randomUUID().toString();
        return Mono.fromRunnable(
                        () ->
                                platformState.projectMemoriesToAgentWorkspace(
                                        definition, context.getUserId()))
                .then(
                        Mono.defer(
                                () ->
                                        agent(definition)
                                                .call(userMessage(message, images), context)))
                .doFinally(
                        signal ->
                                platformState.importAgentWorkspaceMemories(
                                        definition,
                                        context.getUserId(),
                                        context.getSessionId(),
                                        runId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AgentEventEnvelope> streamAgent(
            AgentDefinition definition, String message, RuntimeContext context) {
        return streamAgent(definition, message, context, null, List.of());
    }

    private Flux<AgentEventEnvelope> streamAgent(
            AgentDefinition definition,
            String message,
            RuntimeContext context,
            TaskContext taskContext,
            List<ChatImage> images) {
        String runId = UUID.randomUUID().toString();
        return Mono.fromRunnable(
                        () ->
                                platformState.projectMemoriesToAgentWorkspace(
                                        definition, context.getUserId()))
                .thenMany(
                        Flux.defer(
                                () ->
                                        agent(definition)
                                                .streamEvents(
                                                        userMessage(message, images), context)))
                .doFinally(
                        signal ->
                                platformState.importAgentWorkspaceMemories(
                                        definition,
                                        context.getUserId(),
                                        context.getSessionId(),
                                        runId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(event -> envelope(definition.agentId(), event, taskContext));
    }

    private Flux<AgentEventEnvelope> capabilityEvents(
            AgentDefinition definition, String tenantId, String userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent_id", definition.agentId());
        payload.put("mode", definition.orchestration().mode().name());
        payload.put("model", safe(definition.model(), ""));
        payload.put("model_policy", definition.modelPolicy());
        payload.put("tool_refs", definition.toolRefs());
        payload.put("mcp_refs", definition.mcpRefs());
        payload.put("skill_refs", definition.skillRefs());
        payload.put("tool_count", definition.toolRefs().size());
        payload.put("mcp_count", definition.mcpRefs().size());
        payload.put("skill_count", definition.skillRefs().size());
        long memoryCount = platformState.activeMemoryCount("platform");
        payload.put("memory_count", memoryCount);
        payload.put("memory_scope", "platform:active");
        payload.put("memory_scopes", List.of("user_global", "agent_user", "session"));
        payload.put("memory_workspace", memoryWorkspace(definition.agentId(), tenantId, userId));
        CompactionConfig compaction =
                CompactionConfig.builder()
                        .triggerMessages(PLATFORM_COMPACTION_TRIGGER_MESSAGES)
                        .build();
        payload.put("compaction_enabled", true);
        payload.put("compaction_trigger_messages", compaction.getTriggerMessages());
        payload.put("compaction_trigger_tokens", compaction.getTriggerTokens());
        payload.put("compaction_reserved_tokens", compaction.getReserved());
        payload.put("compaction_keep_messages", compaction.getKeepMessages());
        payload.put("compaction_keep_tokens", compaction.getKeepTokens());
        payload.put(
                "summary",
                "Loaded capabilities for "
                        + definition.agentId()
                        + ": tools="
                        + definition.toolRefs().size()
                        + ", mcps="
                        + definition.mcpRefs().size()
                        + ", skills="
                        + definition.skillRefs().size()
                        + ", memories="
                        + memoryCount);
        payload.put("runtime", true);
        return Flux.just(
                new AgentEventEnvelope(
                        "capability_loaded_" + Instant.now().toEpochMilli(),
                        "capability_loaded",
                        Instant.now().toString(),
                        definition.agentId(),
                        null,
                        payload));
    }

    private String workflowStepMessage(WorkflowStep step, String input) {
        String message =
                (step.instruction() == null || step.instruction().isBlank())
                        ? input
                        : step.instruction() + "\n\nInput:\n" + input;
        if (step.transitions().isEmpty()) {
            return message;
        }
        String conditions =
                step.transitions().stream()
                        .filter(transition -> !transition.defaultTransition())
                        .map(WorkflowTransition::when)
                        .filter(condition -> condition != null && !condition.isBlank())
                        .distinct()
                        .collect(java.util.stream.Collectors.joining(", "));
        return message
                + "\n\nWorkflow routing instruction:\n"
                + "After completing the task, output ONLY a JSON object in the form "
                + "{\"status\": \"<status>\", \"content\": \"<result>\"}. Allowed statuses: "
                + conditions
                + ". Do not invent a status. If none applies, use the configured default branch."
                + " The content field must contain the useful result for the next step.";
    }

    private AgentEventEnvelope workflowEvent(String source, String type, String summary) {
        return new AgentEventEnvelope(
                type + "_" + Instant.now().toEpochMilli(),
                type,
                Instant.now().toString(),
                source,
                null,
                Map.of("summary", summary, "workflow", true));
    }

    private AgentEventEnvelope singleEvent(AgentDefinition definition) {
        return runtimeEvent(
                definition.agentId(),
                "single_agent_start",
                "Start single agent " + definition.agentId(),
                Map.of("agent_id", definition.agentId(), "mode", "SINGLE"));
    }

    private AgentEventEnvelope supervisorEvent(AgentDefinition definition) {
        List<Map<String, Object>> subagents =
                definition.orchestration().subagents().stream().map(this::subagentPayload).toList();
        return runtimeEvent(
                definition.agentId(),
                "supervisor_start",
                "Start supervisor "
                        + definition.agentId()
                        + " with "
                        + subagents.size()
                        + " subagents",
                Map.of(
                        "agent_id",
                        definition.agentId(),
                        "mode",
                        "SUPERVISOR",
                        "subagents",
                        subagents));
    }

    private AgentEventEnvelope supervisorSelectionEvent(
            AgentDefinition definition, SubagentBinding binding, AgentDefinition target) {
        return runtimeEvent(
                definition.agentId(),
                "supervisor_subagent_selected",
                "Supervisor selected "
                        + safe(binding.bindingId(), target.agentId())
                        + " -> "
                        + target.agentId(),
                Map.of(
                        "agent_id",
                        definition.agentId(),
                        "target_agent_id",
                        target.agentId(),
                        "binding_id",
                        safe(binding.bindingId(), ""),
                        "role",
                        safe(binding.role(), ""),
                        "description",
                        safe(binding.description(), "")));
    }

    private AgentEventEnvelope subagentResultEvent(
            AgentDefinition definition,
            SubagentBinding binding,
            AgentDefinition target,
            Msg subagentReply) {
        String text = subagentReply == null ? "" : safe(subagentReply.getTextContent(), "");
        return runtimeEvent(
                definition.agentId(),
                "supervisor_subagent_result",
                "Subagent "
                        + target.agentId()
                        + " completed"
                        + (text.isBlank() ? "" : ": " + abbreviate(text, 160)),
                Map.of(
                        "agent_id",
                        definition.agentId(),
                        "target_agent_id",
                        target.agentId(),
                        "binding_id",
                        safe(binding.bindingId(), ""),
                        "result_preview",
                        abbreviate(text, 500)));
    }

    private AgentEventEnvelope routerEvent(AgentDefinition definition, RouteDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent_id", definition.agentId());
        payload.put("mode", "ROUTER");
        payload.put("target_agent_id", decision.target().agentId());
        payload.put("matched", decision.rule() != null);
        if (decision.rule() != null) {
            payload.put("rule_id", safe(decision.rule().ruleId(), ""));
            payload.put("contains", safe(decision.rule().contains(), ""));
            payload.put("keywords", decision.rule().keywords());
            payload.put("default_route", decision.rule().defaultRoute());
        }
        String summary =
                decision.rule() == null
                        ? "Router default -> " + decision.target().agentId()
                        : "Router matched "
                                + safe(decision.rule().ruleId(), "rule")
                                + " -> "
                                + decision.target().agentId();
        return runtimeEvent(definition.agentId(), "router_decision", summary, payload);
    }

    private AgentEventEnvelope runtimeEvent(
            String source, String type, String summary, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("runtime", true);
        payload.putAll(extra);
        return new AgentEventEnvelope(
                type + "_" + Instant.now().toEpochMilli(),
                type,
                Instant.now().toString(),
                source,
                null,
                payload);
    }

    private Map<String, Object> subagentPayload(SubagentBinding binding) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("binding_id", safe(binding.bindingId(), ""));
        row.put("target_agent_id", safe(binding.targetAgentId(), ""));
        row.put("role", safe(binding.role(), ""));
        row.put("description", safe(binding.description(), ""));
        row.put("expose_to_user", binding.exposeToUser());
        return row;
    }

    private AgentDefinition route(AgentDefinition definition, ChatRequest request) {
        return routeDecision(definition, request).target();
    }

    private RouteDecision routeDecision(AgentDefinition definition, ChatRequest request) {
        RouteRule rule =
                definition.orchestration().routes().stream()
                        .filter(candidate -> !candidate.defaultRoute())
                        .filter(candidate -> candidate.matches(request.message()))
                        .findFirst()
                        .orElseGet(
                                () ->
                                        definition.orchestration().routes().stream()
                                                .filter(RouteRule::defaultRoute)
                                                .findFirst()
                                                .orElse(null));
        AgentDefinition target =
                rule == null
                        ? definition
                        : definition(safe(rule.targetAgentId(), definition.agentId()));
        return new RouteDecision(target, rule);
    }

    private record RouteDecision(AgentDefinition target, RouteRule rule) {}

    private AgentDefinition definition(String agentId) {
        AgentDefinition definition =
                registry.findPublished(agentId)
                        .orElseThrow(
                                () -> new AgentRuntimeException("Agent not found: " + agentId));
        if (!definition.enabled()) {
            throw new AgentRuntimeException("Agent is disabled: " + agentId);
        }
        return definition;
    }

    private HarnessAgent agent(AgentDefinition definition) {
        String key = definition.agentId() + ":" + definition.version();
        return agentCache.computeIfAbsent(key, ignored -> harnessFactory.create(definition));
    }

    private RuntimeContext runtimeContext(ChatRequest request) {
        return RuntimeContext.builder()
                .userId(userKey(request))
                .sessionId(sessionKey(request))
                .put("tenant_id", safe(request.tenantId(), "default"))
                .put("task_id", request.taskContext().taskId())
                .put("root_task_id", request.taskContext().rootTaskId())
                .put("parent_task_id", safe(request.taskContext().parentTaskId(), ""))
                .build();
    }

    private RuntimeContext subagentContext(ChatRequest request, SubagentBinding binding) {
        return RuntimeContext.builder()
                .userId(userKey(request))
                .sessionId(
                        sessionKey(request)
                                + "_sub_"
                                + pathSafe(safe(binding.bindingId(), "subagent"), "subagent"))
                .put("tenant_id", safe(request.tenantId(), "default"))
                .put("supervisor_session_id", sessionKey(request))
                .build();
    }

    private String memoryWorkspace(String agentId, String tenantId, String userId) {
        return "workspace/"
                + safe(agentId, "agent")
                + "/"
                + pathSafe(safe(tenantId, "platform"), "platform")
                + "_"
                + pathSafe(safe(userId, "anonymous"), "anonymous");
    }

    private ChatResponse response(String agentId, ChatRequest request, Msg msg) {
        return response(agentId, request, msg == null ? "" : msg.getTextContent());
    }

    private ChatResponse response(String agentId, ChatRequest request, String content) {
        return new ChatResponse(agentId, userKey(request), sessionKey(request), safe(content, ""));
    }

    private static UserMessage userMessage(String text, List<ChatImage> images) {
        List<ContentBlock> blocks = new java.util.ArrayList<>();
        if (text != null && !text.isBlank()) {
            blocks.add(TextBlock.builder().text(text).build());
        }
        for (ChatImage image : images == null ? List.<ChatImage>of() : images) {
            blocks.add(
                    ImageBlock.builder()
                            .source(
                                    image.isUrl()
                                            ? URLSource.builder().url(image.url()).build()
                                            : Base64Source.builder()
                                                    .mediaType(image.mediaType())
                                                    .data(image.data())
                                                    .build())
                            .build());
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlock.builder().text("").build());
        }
        return new UserMessage(blocks);
    }

    private Mono<ChatRequest> enrichWithVision(AgentDefinition definition, ChatRequest request) {
        if (!request.hasImages()) {
            return Mono.just(request);
        }
        String vlmModel = harnessFactory.resolveVisionModel(definition);
        String visionPrompt =
                "Analyze the supplied image(s) for the downstream assistant. "
                        + "Return concise, factual observations: visible objects, layout, "
                        + "important text/OCR, numbers, charts, and uncertainty. "
                        + "Do not answer the user directly.\n\nUser request:\n"
                        + safe(request.message(), "");
        return ModelRegistry.resolve(vlmModel).stream(
                        List.of(userMessage(visionPrompt, request.images())), List.of(), null)
                .flatMapIterable(
                        response ->
                                response.getContent() == null
                                        ? List.<ContentBlock>of()
                                        : response.getContent())
                .filter(TextBlock.class::isInstance)
                .cast(TextBlock.class)
                .map(TextBlock::getText)
                .collect(java.util.stream.Collectors.joining())
                .map(
                        observation ->
                                new ChatRequest(
                                        request.tenantId(),
                                        request.userId(),
                                        request.sessionId(),
                                        request.message()
                                                + "\n\n[Visual context from the vlm slot]\n"
                                                + safe(observation, "(No visual details returned.)")
                                                + "\n[End visual context]",
                                        request.taskContext(),
                                        List.of()));
    }

    private AgentEventEnvelope envelope(String agentId, AgentEvent event) {
        return envelope(agentId, event, null);
    }

    private AgentEventEnvelope envelope(String agentId, AgentEvent event, TaskContext taskContext) {
        Map<String, Object> payload = normalizeToolSkillMetadata(event.getMetadata());
        enrichToolAndSkillPayload(payload, event);
        payload.putIfAbsent("agent_id", agentId);
        if (taskContext != null) {
            payload.putIfAbsent("task_id", taskContext.taskId());
            payload.putIfAbsent("root_task_id", taskContext.rootTaskId());
            if (taskContext.parentTaskId() != null) {
                payload.putIfAbsent("parent_task_id", taskContext.parentTaskId());
            }
            if (taskContext.stepId() != null) {
                payload.putIfAbsent("step_id", taskContext.stepId());
            }
            if (taskContext.sourceAgentId() != null) {
                payload.putIfAbsent("source_agent_id", taskContext.sourceAgentId());
            }
        }
        String delta = null;
        if (event instanceof TextBlockDeltaEvent text) {
            delta = text.getDelta();
            payload.put("replyId", text.getReplyId());
            payload.put("blockId", text.getBlockId());
            putIfAbsent(payload, "reply_id", text.getReplyId());
        } else if (event instanceof TextBlockStartEvent text) {
            putIfAbsent(payload, "reply_id", text.getReplyId());
        } else if (event instanceof TextBlockEndEvent text) {
            putIfAbsent(payload, "reply_id", text.getReplyId());
        }
        return new AgentEventEnvelope(
                event.getId(),
                event.getType().name(),
                event.getCreatedAt(),
                safe(event.getSource(), agentId),
                delta,
                payload.isEmpty() ? null : payload);
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> normalizeToolSkillMetadata(
            Map<String, Object> metadata) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> payload.put(String.valueOf(key), value));
        }
        Map<String, Object> tool = asMap(payload.get("tool"));
        Map<String, Object> skill = asMap(payload.get("skill"));
        putIfAbsent(
                payload,
                "tool_id",
                firstText(tool.get("tool_id"), tool.get("id"), tool.get("name")));
        putIfAbsent(
                payload,
                "tool_name",
                firstText(tool.get("tool_name"), tool.get("name"), tool.get("id")));
        putIfAbsent(
                payload,
                "skill_id",
                firstText(skill.get("skill_id"), skill.get("id"), skill.get("name")));
        putIfAbsent(
                payload,
                "skill_name",
                firstText(skill.get("skill_name"), skill.get("name"), skill.get("id")));
        return payload;
    }

    private static void enrichToolAndSkillPayload(Map<String, Object> payload, AgentEvent event) {
        putIfAbsent(payload, "tool_id", firstText(payload.get("tool_id")));
        putIfAbsent(payload, "tool_name", firstText(payload.get("tool_name")));
        putIfAbsent(payload, "skill_id", firstText(payload.get("skill_id")));
        putIfAbsent(payload, "skill_name", firstText(payload.get("skill_name")));
        if (event instanceof ToolCallStartEvent start) {
            applyToolInvocationPayload(
                    payload, start.getToolCallId(), start.getToolCallName(), start.getReplyId());
            putIfAbsent(payload, "tool_call_state", "start");
        } else if (event instanceof ToolCallDeltaEvent delta) {
            applyToolInvocationPayload(
                    payload, delta.getToolCallId(), delta.getToolCallName(), delta.getReplyId());
            payload.put("tool_call_delta", delta.getDelta());
            putIfAbsent(payload, "tool_call_state", "delta");
        } else if (event instanceof ToolCallEndEvent end) {
            applyToolInvocationPayload(
                    payload, end.getToolCallId(), end.getToolCallName(), end.getReplyId());
            putIfAbsent(payload, "tool_call_state", "end");
        } else if (event instanceof ToolResultStartEvent start) {
            applyToolInvocationPayload(
                    payload, start.getToolCallId(), start.getToolCallName(), start.getReplyId());
            putIfAbsent(payload, "tool_result_state", "start");
            putIfAbsent(payload, "stage", "tool_result");
        } else if (event instanceof ToolResultTextDeltaEvent delta) {
            applyToolInvocationPayload(
                    payload, delta.getToolCallId(), delta.getToolCallName(), delta.getReplyId());
            payload.put("tool_result_delta", delta.getDelta());
            putIfAbsent(payload, "tool_result_state", "delta");
        } else if (event instanceof ToolResultDataDeltaEvent delta) {
            applyToolInvocationPayload(
                    payload, delta.getToolCallId(), delta.getToolCallName(), delta.getReplyId());
            putToolResultData(payload, delta.getData());
            putIfAbsent(payload, "tool_result_state", "delta");
        } else if (event instanceof ToolResultEndEvent end) {
            applyToolInvocationPayload(
                    payload, end.getToolCallId(), end.getToolCallName(), end.getReplyId());
            putIfAbsent(payload, "tool_result_state", String.valueOf(end.getState()));
            putIfAbsent(payload, "stage", "tool_result");
        }
    }

    private static void applyToolInvocationPayload(
            Map<String, Object> payload, String toolCallId, String toolCallName, String replyId) {
        putIfAbsent(payload, "tool_call_id", firstText(toolCallId));
        putIfAbsent(payload, "tool_call_name", firstText(toolCallName));
        putIfAbsent(payload, "reply_id", firstText(replyId));
        putIfAbsent(payload, "tool_id", firstText(toolCallId, toolCallName));
        putIfAbsent(payload, "tool_name", firstText(toolCallName, toolCallId));
        maybeInferSkillInvocation(payload, firstText(toolCallName, toolCallId));
    }

    private static void putToolResultData(Map<String, Object> payload, ContentBlock data) {
        if (data == null) {
            return;
        }
        if (data instanceof TextBlock textBlock) {
            String text = firstText(textBlock.getText());
            if (!text.isBlank()) {
                putIfAbsent(payload, "tool_result_text", text);
            }
        }
        String text = firstText(data.toString());
        if (!text.isBlank()) {
            putIfAbsent(payload, "tool_result_data", text);
            putIfAbsent(payload, "tool_result_data_type", data.getClass().getSimpleName());
        }
    }

    private static void maybeInferSkillInvocation(Map<String, Object> payload, String toolName) {
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase();
        if (normalized.isBlank()) {
            return;
        }
        if (normalized.contains("skill")
                && (normalized.contains("load")
                        || normalized.contains("read")
                        || normalized.startsWith("skill:"))) {
            putIfAbsent(payload, "skill_id", normalized);
            putIfAbsent(payload, "skill_name", toolName);
            putIfAbsent(payload, "invocation_type", "skill");
        }
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static void putIfAbsent(Map<String, Object> payload, String key, Object value) {
        String valueText = value == null ? "" : String.valueOf(value).trim();
        if (!valueText.isBlank()) {
            payload.putIfAbsent(key, valueText);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> casted = new LinkedHashMap<>();
            map.forEach((key, val) -> casted.put(String.valueOf(key), val));
            return casted;
        }
        return Map.of();
    }

    private String userKey(ChatRequest request) {
        String tenant = safe(request.tenantId(), "default");
        String user = safe(request.userId(), "anonymous");
        return pathSafe(tenant + "_" + user, "default_anonymous");
    }

    private String sessionKey(ChatRequest request) {
        return pathSafe(request.sessionId(), "default");
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String abbreviate(String value, int limit) {
        String text = safe(value, "");
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private String pathSafe(String value, String fallback) {
        String text = safe(value, fallback).replaceAll("[^A-Za-z0-9._-]", "_");
        return text.isBlank() ? fallback : text;
    }
}
