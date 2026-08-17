/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.adapter.agentscope.AgentScopeHarnessFactory;
import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.ContractValue;
import io.agent.platform.control.OrchestrationMode;
import io.agent.platform.control.RouteRule;
import io.agent.platform.control.SubagentBinding;
import io.agent.platform.control.WorkflowAsset;
import io.agent.platform.control.WorkflowBindingResolver;
import io.agent.platform.control.WorkflowEdge;
import io.agent.platform.control.WorkflowNode;
import io.agent.platform.control.WorkflowNodeType;
import io.agent.platform.control.WorkflowPort;
import io.agent.platform.control.WorkflowFailurePolicy;
import io.agent.platform.control.WorkflowStep;
import io.agent.platform.control.WorkflowTransition;
import io.agent.platform.control.WorkflowValueValidationResult;
import io.agent.platform.control.WorkflowValueValidator;
import io.agent.platform.runtime.protocol.TaskContext;
import io.agent.platform.runtime.protocol.AgentTaskEnvelope;
import io.agent.platform.runtime.protocol.TaskRequest;
import io.agent.platform.runtime.protocol.TaskResult;
import io.agent.platform.runtime.protocol.TaskStatus;
import io.agent.platform.scheduled.ScheduledTaskCallContext;
import io.agent.platform.web.PlatformCompatibilityState;
import io.agent.platform.web.WorkflowAssetService;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Service
public class AgentRuntimeService implements AgentRuntime {

    private static final int PLATFORM_COMPACTION_TRIGGER_MESSAGES = 10;
    private static final HttpClient WORKFLOW_HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper WORKFLOW_JSON = new ObjectMapper();
    private static final WorkflowBindingResolver WORKFLOW_BINDING_RESOLVER = new WorkflowBindingResolver();
    private static final WorkflowValueValidator WORKFLOW_VALUE_VALIDATOR = new WorkflowValueValidator();

    private final AgentDefinitionRegistry registry;
    private final AgentScopeHarnessFactory harnessFactory;
    private final PlatformCompatibilityState platformState;
    private final WorkflowAssetService workflowAssetService;
    private final boolean tenantAwareHarnessFactory;
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    @Autowired
    public AgentRuntimeService(
            AgentDefinitionRegistry registry,
            AgentScopeHarnessFactory harnessFactory,
            PlatformCompatibilityState platformState,
            WorkflowAssetService workflowAssetService) {
        this(registry, harnessFactory, platformState, workflowAssetService, true);
    }

    private AgentRuntimeService(
            AgentDefinitionRegistry registry,
            AgentScopeHarnessFactory harnessFactory,
            PlatformCompatibilityState platformState,
            WorkflowAssetService workflowAssetService,
            boolean tenantAwareHarnessFactory) {
        this.registry = registry;
        this.harnessFactory = harnessFactory;
        this.platformState = platformState;
        this.workflowAssetService = workflowAssetService;
        this.tenantAwareHarnessFactory = tenantAwareHarnessFactory;
    }

    /** Compatibility constructor for focused runtime tests and non-Spring callers. */
    public AgentRuntimeService(
            AgentDefinitionRegistry registry,
            AgentScopeHarnessFactory harnessFactory,
            PlatformCompatibilityState platformState) {
        this(registry, harnessFactory, platformState, null, false);
    }

    @Override
    public Mono<ChatResponse> chat(String agentId, ChatRequest request) {
        AgentDefinition definition = definition(agentId);
        return enrichWithVision(definition, request)
                .flatMap(enriched -> executeDefinition(definition, enriched));
    }

    @Override
    public Mono<ChatResponse> workflow(WorkflowAsset workflow, ChatRequest request) {
        return runTypedWorkflow(workflow, request);
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

    @Override
    public Flux<AgentEventEnvelope> workflowStream(
            WorkflowAsset workflow, ChatRequest request) {
        return Flux.concat(
                Flux.just(workflowEvent(workflow.workflowId(), "workflow_start", "Running Workflow " + workflow.name())),
                runTypedWorkflow(workflow, request)
                        .flatMapMany(response -> Flux.just(
                                new AgentEventEnvelope(
                                        "workflow_output_" + UUID.randomUUID().toString().replace("-", ""),
                                        "text_block_delta",
                                        Instant.now().toString(),
                                        workflow.workflowId(),
                                        response.text(),
                                        Map.of("workflow", true, "workflow_id", workflow.workflowId(), "version", workflow.version())),
                                workflowEvent(workflow.workflowId(), "workflow_end", "Finished Workflow " + workflow.name()))));
    }

    private Mono<ChatResponse> executeDefinition(AgentDefinition definition, ChatRequest request) {
        return switch (definition.orchestration().mode()) {
            case ROUTER -> executeDefinition(route(definition, request), request);
            case WORKFLOW -> runAgentWorkflow(definition, request);
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
                        request.images(),
                        request.tenantId(),
                        request.userId()));
    }

    @Override
    public void evict(String agentId) {
        String prefix = agentId + ":";
        agentCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private Mono<ChatResponse> runSingle(AgentDefinition definition, ChatRequest request) {
        return runSingleTask(definition, request)
                .map(
                        execution ->
                                response(
                                        definition.agentId(),
                                        request,
                                        execution.message(),
                                        execution.envelope()));
    }

    private Mono<TaskExecution> runSingleTask(AgentDefinition definition, ChatRequest request) {
        RuntimeContext context = runtimeContext(request);
        return callAgent(definition, request, request.message(), context)
                .map(execution -> execution);
    }

    private Mono<ChatResponse> runSupervisor(AgentDefinition definition, ChatRequest request) {
        List<SubagentBinding> bindings = selectSubagents(definition, request.message());
        if (bindings.isEmpty()) {
            return runSingle(definition, request);
        }
        return runSubagents(definition, request, bindings)
                .flatMap(
                        replies ->
                                callAgent(
                                                definition,
                                                request,
                                                supervisorSummaryMessage(
                                                        definition, request.message(), replies),
                                                runtimeContext(request))
                                        .map(
                                                execution ->
                                                        response(
                                                                definition.agentId(),
                                                                request,
                                                                execution.message(),
                                                                supervisorEnvelope(
                                                                        execution.envelope(), replies))));
    }

    /** Executes the Agent-level ordered sequence. This is intentionally separate from the canvas graph. */
    private Mono<ChatResponse> runAgentWorkflow(AgentDefinition definition, ChatRequest request) {
        List<WorkflowStep> steps = definition.orchestration().workflow();
        if (steps.isEmpty()) {
            return Mono.error(new AgentRuntimeException("Workflow agent has no steps: " + definition.agentId()));
        }
        Instant startedAt = Instant.now();
        return runWorkflowSteps(definition, request, steps, 0, request.message(), new java.util.HashSet<>())
                .map(
                        execution ->
                                response(
                                        definition.agentId(),
                                        request,
                                        execution.text(),
                                        completedEnvelope(
                                                request,
                                                definition.agentId(),
                                                execution.text(),
                                                startedAt,
                                                Map.of("orchestration", "WORKFLOW", "steps", steps.size()))));
    }

    private Mono<WorkflowStepExecution> runWorkflowSteps(
            AgentDefinition definition,
            ChatRequest request,
            List<WorkflowStep> steps,
            int index,
            String input,
            java.util.Set<String> visited) {
        if (index >= steps.size()) {
            return Mono.just(new WorkflowStepExecution(input, null));
        }
        WorkflowStep step = steps.get(index);
        if (!visited.add(step.stepId())) {
            return Mono.error(new AgentRuntimeException("Workflow cycle detected at step: " + step.stepId()));
        }
        return runWorkflowStep(step, request, input)
                .flatMap(
                        execution -> {
                    WorkflowStepOutput output = WorkflowStepOutput.parse(execution.text());
                    return runWorkflowSteps(
                            definition, request, steps, nextWorkflowIndex(steps, index, output.status()),
                            output.content(), visited);
                });
    }

    private Mono<WorkflowStepExecution> runWorkflowStep(WorkflowStep step, ChatRequest request, String input) {
        AgentDefinition target = definition(step.agentId());
        ChatRequest child =
                new ChatRequest(
                        request.tenantId(),
                        request.userId(),
                        sessionKey(request) + "_" + pathSafe(step.stepId(), "step"),
                        workflowStepMessage(step, input),
                        request.taskContext().child(request.taskContext().targetAgentId(), target.agentId(), step.stepId()),
                        request.images());
        Mono<WorkflowStepExecution> guarded =
                executeDefinition(target, child)
                        .map(response -> new WorkflowStepExecution(response.text(), response.task()));
        if (step.timeoutMs() != null) guarded = guarded.timeout(Duration.ofMillis(step.timeoutMs()));
        if (step.maxRetries() > 0) {
            guarded = guarded.retryWhen(Retry.fixedDelay(step.maxRetries(), Duration.ofMillis(100)));
        }
        return guarded.onErrorResume(
                error ->
                        switch (step.failurePolicy()) {
                            case SKIP, USE_INPUT -> Mono.just(new WorkflowStepExecution(input, null));
                            case FAIL_FAST -> Mono.error(error);
                        });
    }

    static Mono<String> withStepPolicy(WorkflowStep step, String input, Mono<String> action) {
        Mono<String> guarded = action;
        if (step.timeoutMs() != null) guarded = guarded.timeout(Duration.ofMillis(step.timeoutMs()));
        if (step.maxRetries() > 0) guarded = guarded.retryWhen(Retry.fixedDelay(step.maxRetries(), Duration.ofMillis(100)));
        return guarded.onErrorResume(error -> switch (step.failurePolicy()) {
            case SKIP, USE_INPUT -> Mono.just(input);
            case FAIL_FAST -> Mono.error(error);
        });
    }

    static Flux<AgentEventEnvelope> withFluxStepPolicy(
            WorkflowStep step, String input, Flux<AgentEventEnvelope> action) {
        Flux<AgentEventEnvelope> guarded = action;
        if (step.timeoutMs() != null) guarded = guarded.timeout(Duration.ofMillis(step.timeoutMs()));
        if (step.maxRetries() > 0) guarded = guarded.retryWhen(Retry.fixedDelay(step.maxRetries(), Duration.ofMillis(100)));
        return guarded.onErrorResume(error -> switch (step.failurePolicy()) {
            case SKIP, USE_INPUT -> Flux.just(
                    new AgentEventEnvelope(
                            "workflow_fallback_" + Instant.now().toEpochMilli(),
                            "workflow_step_fallback",
                            Instant.now().toString(),
                            step.agentId(),
                            input,
                            Map.of("summary", "Workflow step " + safe(step.stepId(), "step") + " failed; using previous input", "workflow", true, "fallback", true)));
            case FAIL_FAST -> Flux.error(error);
        });
    }

    private Flux<AgentEventEnvelope> streamWorkflow(AgentDefinition definition, ChatRequest request) {
        List<WorkflowStep> steps = definition.orchestration().workflow();
        if (steps.isEmpty()) {
            return Flux.error(new AgentRuntimeException("Workflow agent has no steps: " + definition.agentId()));
        }
        return Flux.concat(
                Flux.just(workflowEvent(definition.agentId(), "workflow_start", "Running workflow " + definition.agentId())),
                streamWorkflowStep(steps, 0, request, request.message()));
    }

    private Flux<AgentEventEnvelope> streamWorkflowStep(
            List<WorkflowStep> steps, int index, ChatRequest request, String input) {
        if (index >= steps.size()) return Flux.empty();
        WorkflowStep step = steps.get(index);
        if (index == steps.size() - 1 && step.transitions().isEmpty()) {
            return streamWorkflowFinalStep(step, request, input);
        }
        return Flux.concat(
                Flux.just(workflowEvent(step.agentId(), "workflow_step_start", "Start workflow step " + safe(step.stepId(), "step") + " -> " + step.agentId())),
                workflowAgentSummaryEvents(step.agentId(), "start"),
                runWorkflowStep(step, request, input).flatMapMany(raw -> {
                    WorkflowStepOutput output = WorkflowStepOutput.parse(raw.text());
                    return Flux.concat(
                            workflowAgentSummaryEvents(step.agentId(), "end"),
                            Flux.just(workflowEvent(step.agentId(), "workflow_step_end", "Finished workflow step " + safe(step.stepId(), "step") + " -> " + step.agentId())),
                            streamWorkflowStep(steps, nextWorkflowIndex(steps, index, output.status()), request, output.content()));
                }));
    }

    static int nextWorkflowIndex(List<WorkflowStep> steps, int index, String status) {
        WorkflowStep step = steps.get(index);
        String normalized = safe(status, "").toLowerCase();
        for (WorkflowTransition transition : step.transitions()) {
            if (!transition.defaultTransition() && !transition.when().isBlank() && normalized.equals(transition.when().trim().toLowerCase())) {
                return findWorkflowStep(steps, transition.nextStepId(), index + 1);
            }
        }
        for (WorkflowTransition transition : step.transitions()) {
            if (transition.defaultTransition()) return findWorkflowStep(steps, transition.nextStepId(), index + 1);
        }
        return index + 1;
    }

    private static int findWorkflowStep(List<WorkflowStep> steps, String stepId, int fallback) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).stepId().equals(stepId)) return i;
        return fallback;
    }

    private Flux<AgentEventEnvelope> streamWorkflowFinalStep(
            WorkflowStep step, ChatRequest request, String input) {
        AgentDefinition target = definition(step.agentId());
        ChatRequest child =
                new ChatRequest(
                        request.tenantId(), request.userId(), sessionKey(request) + "_" + pathSafe(step.stepId(), "step"),
                        workflowStepMessage(step, input),
                        request.taskContext().child(request.taskContext().targetAgentId(), target.agentId(), step.stepId()),
                        request.images());
        return Flux.concat(
                Flux.just(workflowEvent(target.agentId(), "workflow_final_step", "Streaming final workflow step " + safe(step.stepId(), "step") + " -> " + target.agentId())),
                withFluxStepPolicy(step, input, streamDefinition(target, child)));
    }

    /** Executes the independent Workflow graph; nodes and edges are the only execution model. */
    private Mono<ChatResponse> runTypedWorkflow(WorkflowAsset workflow, ChatRequest request) {
        List<WorkflowNode> nodes = workflow.nodes();
        if (nodes.isEmpty()) {
            return Mono.error(new AgentRuntimeException("Workflow has no nodes: " + workflow.workflowId()));
        }
        Map<String, WorkflowNode> nodesById = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) nodesById.put(node.nodeId(), node);
        List<WorkflowEdge> edges = workflow.edges();
        String startNodeId = typedWorkflowStartNode(nodes, edges);
        WorkflowNode startNode = nodesById.get(startNodeId);
        Object initialData = workflowValueData(request.message(), startNode == null ? null : firstInputPort(startNode));
        Instant startedAt = Instant.now();
        return runTypedWorkflowNode(workflow, request, nodesById, edges, startNodeId,
                        ContractValue.of("", initialData), new java.util.HashSet<>())
                .map(
                        value -> {
                            String text = workflowValueText(value.data());
                            return response(
                                    "workflow:" + workflow.workflowId(),
                                    request,
                                    text,
                                    completedEnvelope(
                                            request,
                                            "workflow:" + workflow.workflowId(),
                                            text,
                                            startedAt,
                                            Map.of("orchestration", "WORKFLOW", "workflow_id", workflow.workflowId())));
                        });
    }

    private Mono<ContractValue> runTypedWorkflowNode(
            WorkflowAsset workflow,
            ChatRequest request,
            Map<String, WorkflowNode> nodesById,
            List<WorkflowEdge> edges,
            String nodeId,
            ContractValue input,
            java.util.Set<String> visited) {
        WorkflowNode node = nodesById.get(nodeId);
        if (node == null) return Mono.error(new AgentRuntimeException("Workflow node not found: " + nodeId));
        if (!visited.add(nodeId)) return Mono.error(new AgentRuntimeException("Workflow cycle detected at node: " + nodeId));
        if (node.type() == WorkflowNodeType.PARALLEL) {
            return runParallelWorkflowNode(workflow, request, nodesById, edges, node, input, visited);
        }
        WorkflowPort inputPort = firstInputPort(node);
        if (inputPort != null) {
            WorkflowValueValidationResult validation = WORKFLOW_VALUE_VALIDATOR.validate(inputPort, input);
            if (!validation.valid()) return Mono.error(new AgentRuntimeException("Workflow input validation failed at " + nodeId + ": " + String.join("; ", validation.errors())));
        }
        return runWorkflowNode(node, request, workflowValueText(input == null ? null : input.data()))
                .flatMap(rawOutput -> {
                    WorkflowStepOutput parsed = WorkflowStepOutput.parse(rawOutput);
                    List<WorkflowEdge> outgoing = edges.stream().filter(edge -> edge != null && edge.from() != null && nodeId.equals(edge.from().nodeId())).toList();
                    WorkflowEdge next = chooseTypedEdge(outgoing, parsed.content());
                    WorkflowPort outputPort = next == null ? firstOutputPort(node) : findPort(node.outputPorts(), next.from().portId());
                    ContractValue output = new ContractValue(outputPort == null ? "" : outputPort.contractRef(), workflowValueData(parsed.content(), outputPort), Map.of("source_node", nodeId));
                    if (next == null) return Mono.just(output);
                    WorkflowNode target = nodesById.get(next.to().nodeId());
                    if (target == null) return Mono.error(new AgentRuntimeException("Workflow edge target not found: " + next.to().nodeId()));
                    WorkflowPort targetPort = findPort(target.inputPorts(), next.to().portId());
                    ContractValue mapped = WORKFLOW_BINDING_RESOLVER.resolve(output, targetPort == null ? "" : targetPort.contractRef(), next.binding(), nodeId);
                    if (targetPort != null) {
                        WorkflowValueValidationResult validation = WORKFLOW_VALUE_VALIDATOR.validate(targetPort, mapped);
                        if (!validation.valid()) return Mono.error(new AgentRuntimeException("Workflow edge validation failed at " + next.edgeId() + ": " + String.join("; ", validation.errors())));
                    }
                    return runTypedWorkflowNode(workflow, request, nodesById, edges, next.to().nodeId(), mapped, visited);
                });
    }

    /** Runs each branch of a PARALLEL node concurrently and supplies an ordered array to JOIN. */
    private Mono<ContractValue> runParallelWorkflowNode(
            WorkflowAsset workflow,
            ChatRequest request,
            Map<String, WorkflowNode> nodesById,
            List<WorkflowEdge> edges,
            WorkflowNode parallel,
            ContractValue input,
            Set<String> visited) {
        WorkflowPort inputPort = firstInputPort(parallel);
        if (inputPort != null) {
            WorkflowValueValidationResult validation =
                    WORKFLOW_VALUE_VALIDATOR.validate(inputPort, input);
            if (!validation.valid()) {
                return Mono.error(
                        new AgentRuntimeException(
                                "Workflow input validation failed at "
                                        + parallel.nodeId()
                                        + ": "
                                        + String.join("; ", validation.errors())));
            }
        }
        List<WorkflowEdge> branches =
                outgoingWorkflowEdges(edges, parallel.nodeId()).stream()
                        .filter(WorkflowEdge::data)
                        .toList();
        if (branches.isEmpty()) {
            return Mono.error(new AgentRuntimeException("PARALLEL node has no branches: " + parallel.nodeId()));
        }
        List<Mono<BranchResult>> executions =
                branches.stream()
                        .map(
                                edge ->
                                        runWorkflowBranch(
                                                workflow,
                                                request,
                                                nodesById,
                                                edges,
                                                parallel.nodeId(),
                                                edge,
                                                input,
                                                new HashSet<>(visited)))
                        .toList();
        // mergeSequential subscribes to every branch eagerly (parallel execution) while keeping
        // the declared edge order stable in the JOIN array.
        return Flux.mergeSequential(executions)
                .collectList()
                .flatMap(
                        results -> {
                            if (results.isEmpty()) {
                                return Mono.error(new AgentRuntimeException("PARALLEL node produced no branch result: " + parallel.nodeId()));
                            }
                            String joinNodeId = results.get(0).joinNodeId();
                            if (results.stream().anyMatch(result -> !joinNodeId.equals(result.joinNodeId()))) {
                                return Mono.error(new AgentRuntimeException("PARALLEL branches must converge on one JOIN node: " + parallel.nodeId()));
                            }
                            WorkflowNode join = nodesById.get(joinNodeId);
                            if (join == null || join.type() != WorkflowNodeType.JOIN) {
                                return Mono.error(new AgentRuntimeException("PARALLEL branches must end at JOIN: " + joinNodeId));
                            }
                            WorkflowPort joinPort = firstInputPort(join);
                            List<Object> values = results.stream().map(result -> result.value().data()).toList();
                            ContractValue joined =
                                    new ContractValue(
                                            joinPort == null ? "" : joinPort.contractRef(),
                                            values,
                                            Map.of("source_node", parallel.nodeId(), "parallel", true));
                            Set<String> nextVisited = new HashSet<>(visited);
                            return runTypedWorkflowNode(
                                    workflow,
                                    request,
                                    nodesById,
                                    edges,
                                    joinNodeId,
                                    joined,
                                    nextVisited);
                        });
    }

    private Mono<BranchResult> runWorkflowBranch(
            WorkflowAsset workflow,
            ChatRequest request,
            Map<String, WorkflowNode> nodesById,
            List<WorkflowEdge> edges,
            String sourceNodeId,
            WorkflowEdge firstEdge,
            ContractValue input,
            Set<String> visited) {
        WorkflowNode source = nodesById.get(sourceNodeId);
        WorkflowNode target = nodesById.get(firstEdge.to().nodeId());
        if (target == null) {
            return Mono.error(new AgentRuntimeException("Workflow edge target not found: " + firstEdge.to().nodeId()));
        }
        WorkflowPort sourcePort = findPort(source.outputPorts(), firstEdge.from().portId());
        ContractValue sourceValue =
                new ContractValue(
                        sourcePort == null ? "" : sourcePort.contractRef(),
                        input == null ? null : input.data(),
                        Map.of("source_node", sourceNodeId));
        WorkflowPort targetPort = findPort(target.inputPorts(), firstEdge.to().portId());
        ContractValue mapped =
                WORKFLOW_BINDING_RESOLVER.resolve(
                        sourceValue,
                        targetPort == null ? "" : targetPort.contractRef(),
                        firstEdge.binding(),
                        sourceNodeId);
        return runWorkflowBranchNode(
                workflow, request, nodesById, edges, target.nodeId(), mapped, visited);
    }

    private Mono<BranchResult> runWorkflowBranchNode(
            WorkflowAsset workflow,
            ChatRequest request,
            Map<String, WorkflowNode> nodesById,
            List<WorkflowEdge> edges,
            String nodeId,
            ContractValue input,
            Set<String> visited) {
        WorkflowNode node = nodesById.get(nodeId);
        if (node == null) return Mono.error(new AgentRuntimeException("Workflow node not found: " + nodeId));
        if (node.type() == WorkflowNodeType.JOIN) {
            WorkflowPort inputPort = firstInputPort(node);
            if (inputPort != null) {
                WorkflowValueValidationResult validation =
                        WORKFLOW_VALUE_VALIDATOR.validate(inputPort, input);
                if (!validation.valid()) {
                    return Mono.error(
                            new AgentRuntimeException(
                                    "Workflow JOIN validation failed at "
                                            + nodeId
                                            + ": "
                                            + String.join("; ", validation.errors())));
                }
            }
            return Mono.just(new BranchResult(nodeId, input));
        }
        if (!visited.add(nodeId)) return Mono.error(new AgentRuntimeException("Workflow cycle detected at node: " + nodeId));
        WorkflowPort inputPort = firstInputPort(node);
        if (inputPort != null) {
            WorkflowValueValidationResult validation = WORKFLOW_VALUE_VALIDATOR.validate(inputPort, input);
            if (!validation.valid()) return Mono.error(new AgentRuntimeException("Workflow input validation failed at " + nodeId + ": " + String.join("; ", validation.errors())));
        }
        return runWorkflowNode(node, request, workflowValueText(input == null ? null : input.data()))
                .flatMap(
                        rawOutput -> {
                            WorkflowStepOutput parsed = WorkflowStepOutput.parse(rawOutput);
                            List<WorkflowEdge> outgoing = outgoingWorkflowEdges(edges, nodeId);
                            WorkflowEdge next = chooseTypedEdge(outgoing, parsed.content());
                            if (next == null) {
                                return Mono.error(new AgentRuntimeException("PARALLEL branch does not converge on JOIN: " + nodeId));
                            }
                            WorkflowNode target = nodesById.get(next.to().nodeId());
                            if (target == null) return Mono.error(new AgentRuntimeException("Workflow edge target not found: " + next.to().nodeId()));
                            WorkflowPort outputPort = findPort(node.outputPorts(), next.from().portId());
                            ContractValue output =
                                    new ContractValue(
                                            outputPort == null ? "" : outputPort.contractRef(),
                                            workflowValueData(parsed.content(), outputPort),
                                            Map.of("source_node", nodeId));
                            WorkflowPort targetPort = findPort(target.inputPorts(), next.to().portId());
                            ContractValue mapped =
                                    WORKFLOW_BINDING_RESOLVER.resolve(
                                            output,
                                            targetPort == null ? "" : targetPort.contractRef(),
                                            next.binding(),
                                            nodeId);
                            return runWorkflowBranchNode(
                                    workflow, request, nodesById, edges, target.nodeId(), mapped, visited);
                        });
    }

    private static List<WorkflowEdge> outgoingWorkflowEdges(List<WorkflowEdge> edges, String nodeId) {
        return edges.stream()
                .filter(edge -> edge != null && edge.from() != null && nodeId.equals(edge.from().nodeId()))
                .toList();
    }

    private WorkflowEdge chooseTypedEdge(List<WorkflowEdge> outgoing, String content) {
        if (outgoing.isEmpty()) return null;
        WorkflowEdge fallback = null;
        for (WorkflowEdge edge : outgoing) {
            if (edge.control()) {
                if (conditionMatches(edge.condition(), content)) return edge;
                if (edge.defaultEdge()) fallback = edge;
            } else if (fallback == null) fallback = edge;
        }
        return fallback;
    }

    private boolean conditionMatches(Map<String, Object> condition, String content) {
        if (condition == null || condition.isEmpty()) return false;
        String path = String.valueOf(condition.getOrDefault("path", ""));
        String operator = String.valueOf(condition.getOrDefault("operator", "equals"));
        String expected = String.valueOf(condition.getOrDefault("value", ""));
        Object data = workflowValueData(content, null);
        Object resolved = WORKFLOW_BINDING_RESOLVER.resolve(ContractValue.of("", data), "", Map.of("value", path), "").data();
        Object actual = resolved instanceof Map<?, ?> map ? map.get("value") : null;
        return "equals".equalsIgnoreCase(operator) && expected.equals(String.valueOf(actual));
    }

    private static String typedWorkflowStartNode(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (WorkflowEdge edge : edges) if (edge != null && edge.to() != null) targets.add(edge.to().nodeId());
        return nodes.stream().map(WorkflowNode::nodeId).filter(nodeId -> !targets.contains(nodeId)).findFirst().orElse(nodes.get(0).nodeId());
    }

    private static WorkflowPort firstInputPort(WorkflowNode node) { return node.inputPorts().isEmpty() ? null : node.inputPorts().get(0); }
    private static WorkflowPort firstOutputPort(WorkflowNode node) { return node.outputPorts().isEmpty() ? null : node.outputPorts().get(0); }
    private static WorkflowPort findPort(List<WorkflowPort> ports, String portId) { return ports.stream().filter(port -> port != null && port.portId().equals(portId)).findFirst().orElse(null); }

    private static Object workflowValueData(String content, WorkflowPort port) {
        String text = content == null ? "" : content.trim();
        boolean structured = port != null && port.schema() != null && !port.schema().isEmpty()
                && ("object".equals(String.valueOf(port.schema().get("type"))) || "array".equals(String.valueOf(port.schema().get("type"))));
        if (!structured && !(text.startsWith("{") || text.startsWith("["))) return content == null ? "" : content;
        try { JsonNode parsed = WORKFLOW_JSON.readTree(text); return WORKFLOW_JSON.convertValue(parsed, Object.class); }
        catch (Exception ignored) { return content == null ? "" : content; }
    }

    private static String workflowValueText(Object data) {
        if (data == null) return "";
        if (data instanceof String text) return text;
        try { return WORKFLOW_JSON.writeValueAsString(data); }
        catch (Exception ignored) { return String.valueOf(data); }
    }

    private Mono<String> runWorkflowNode(
            WorkflowNode node, ChatRequest request, String input) {
        Mono<String> action =
                switch (node.type()) {
                    case INPUT, OUTPUT, JOIN -> Mono.just(input);
                    case AGENT_INVOKE, REACT_AGENT -> runReferencedAgentNode(node, request, input);
                    case SUBFLOW_INVOKE -> runReferencedWorkflowNode(node, request, input);
                    case LLM_CHAT -> runLlmNode(node, request, input);
                    case HTTP_REQUEST -> runHttpNode(node, input);
                    default ->
                            Mono.error(
                                    new AgentRuntimeException(
                                            "Workflow node type is not executable yet: "
                                                    + node.nodeId()
                                                    + " ("
                                                    + node.type().value()
                                                    + ")"));
                };
        return withNodePolicy(node, input, action);
    }

    private Mono<String> runReferencedAgentNode(
            WorkflowNode node, ChatRequest request, String input) {
        if (node.refId() == null || node.refId().isBlank()) {
            return Mono.error(
                    new AgentRuntimeException("Workflow node refId is required: " + node.nodeId()));
        }
        AgentDefinition target = definition(node.refId());
        ChatRequest childRequest =
                new ChatRequest(
                        request.tenantId(),
                        request.userId(),
                        sessionKey(request) + "_" + pathSafe(node.nodeId(), "node"),
                        workflowNodeMessage(node, input),
                        request.taskContext()
                                .child(
                                        request.taskContext().targetAgentId(),
                                        target.agentId(),
                                        node.nodeId()),
                        request.images());
        return executeDefinition(target, childRequest).map(ChatResponse::text);
    }

    private Mono<String> runReferencedWorkflowNode(
            WorkflowNode node, ChatRequest request, String input) {
        if (workflowAssetService == null) {
            return Mono.error(
                    new AgentRuntimeException(
                            "Workflow service is unavailable for node: " + node.nodeId()));
        }
        var target = workflowAssetService.requirePublished(node.refId());
        ChatRequest childRequest =
                new ChatRequest(
                        request.tenantId(),
                        request.userId(),
                        sessionKey(request) + "_" + pathSafe(node.nodeId(), "subflow"),
                        workflowNodeMessage(node, input),
                        request.taskContext()
                                .child(
                                        request.taskContext().targetAgentId(),
                                        "workflow:" + target.workflowId(),
                                        node.nodeId()),
                        request.images());
        return workflow(target, childRequest).map(ChatResponse::text);
    }

    private Mono<String> runLlmNode(
            WorkflowNode node, ChatRequest request, String input) {
        String modelId = safe(node.refId(), platformState.defaultChatModelId());
        if (modelId.isBlank()) {
            return Mono.error(
                    new AgentRuntimeException(
                            "LLM workflow node requires refId or a default chat model: "
                                    + node.nodeId()));
        }
        return ModelRegistry.resolve(modelId)
                .stream(
                        List.of(userMessage(workflowNodeMessage(node, input), request.images())),
                        List.of(),
                        null)
                .flatMapIterable(
                        result ->
                                result.getContent() == null
                                        ? List.<ContentBlock>of()
                                        : result.getContent())
                .filter(TextBlock.class::isInstance)
                .cast(TextBlock.class)
                .map(TextBlock::getText)
                .collect(java.util.stream.Collectors.joining());
    }

    private Mono<String> runHttpNode(WorkflowNode node, String input) {
        String url = workflowConfigString(node, "url", "");
        if (url.isBlank()) {
            return Mono.error(
                    new AgentRuntimeException(
                            "http.request node requires config.url: " + node.nodeId()));
        }
        String method = workflowConfigString(node, "method", "POST").toUpperCase();
        String body = resolveWorkflowTemplate(workflowConfigString(node, "body", input), input);
        HttpRequest.BodyPublisher bodyPublisher =
                method.equals("GET") || method.equals("DELETE")
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(resolveWorkflowTemplate(url, input)))
                        .timeout(Duration.ofMillis(node.timeoutMs() == null ? 30000L : node.timeoutMs()))
                        .method(method, bodyPublisher)
                        .header("Content-Type", "application/json");
        Object headers = node.config().get("headers");
        if (headers instanceof Map<?, ?> headerMap) {
            headerMap.forEach(
                    (key, value) ->
                            builder.header(
                                    String.valueOf(key),
                                    resolveWorkflowTemplate(String.valueOf(value), input)));
        }
        return Mono.fromFuture(WORKFLOW_HTTP_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()))
                .flatMap(
                        response ->
                                response.statusCode() >= 200 && response.statusCode() < 300
                                        ? Mono.just(response.body())
                                        : Mono.error(
                                                new AgentRuntimeException(
                                                        "HTTP workflow node failed: HTTP "
                                                                + response.statusCode()
                                                                + " "
                                                                + response.body())));
    }

    private static Mono<String> withNodePolicy(
            WorkflowNode node, String input, Mono<String> action) {
        Mono<String> guarded = action;
        if (node.timeoutMs() != null) {
            guarded = guarded.timeout(Duration.ofMillis(node.timeoutMs()));
        }
        if (node.maxRetries() > 0) {
            guarded =
                    guarded.retryWhen(
                            Retry.fixedDelay(node.maxRetries(), Duration.ofMillis(100)));
        }
        return guarded.onErrorResume(
                error ->
                        switch (node.failurePolicy()) {
                            case SKIP, USE_INPUT -> Mono.just(input);
                            case FAIL_FAST -> Mono.error(error);
                        });
    }

    private static String workflowNodeMessage(WorkflowNode node, String input) {
        String message =
                node.instruction() == null || node.instruction().isBlank()
                        ? input
                        : node.instruction() + "\n\nInput:\n" + input;
        return resolveWorkflowTemplate(message, input);
    }

    private static String workflowConfigString(
            WorkflowNode node, String key, String fallback) {
        Object value = node.config().get(key);
        return value == null || String.valueOf(value).isBlank()
                ? fallback
                : String.valueOf(value);
    }

    private static String resolveWorkflowTemplate(String value, String input) {
        if (value == null) {
            return "";
        }
        String safeInput = input == null ? "" : input;
        return value.replace("{{input}}", safeInput).replace("${input}", safeInput);
    }

    private String workflowStepMessage(WorkflowStep step, String input) {
        String message =
                step.instruction() == null || step.instruction().isBlank()
                        ? input
                        : step.instruction() + "\n\nInput:\n" + input;
        return resolveWorkflowTemplate(message, input);
    }

    private Flux<AgentEventEnvelope> streamSupervisor(
            AgentDefinition definition, ChatRequest request) {
        List<SubagentBinding> bindings = selectSubagents(definition, request.message());
        if (bindings.isEmpty()) {
            return Flux.concat(
                    Flux.just(supervisorEvent(definition)),
                    capabilityEvents(definition, request.tenantId(), request.userId()),
                    streamAgent(
                            definition,
                            request.message(),
                            runtimeContext(request),
                            request.taskContext(),
                            request.images(),
                            request.tenantId(),
                            request.userId()));
        }
        return Flux.concat(
                Flux.just(supervisorEvent(definition)),
                Flux.fromIterable(bindings)
                        .map(
                                binding -> {
                                    AgentDefinition target = definition(binding.targetAgentId());
                                    return supervisorSelectionEvent(definition, binding, target);
                                }),
                runSubagents(definition, request, bindings)
                        .flatMapMany(
                                replies -> {
                                    Flux<AgentEventEnvelope> results =
                                            Flux.fromIterable(replies)
                                                    .flatMap(
                                                            reply ->
                                                                    Flux.concat(
                                                                            workflowAgentSummaryEvents(
                                                                                    reply.target().agentId(),
                                                                                    "end"),
                                                                            Flux.just(
                                                                                    subagentResultEvent(
                                                                                            definition,
                                                                                            reply.binding(),
                                                                                            reply.target(),
                                                                                            reply.execution().message(),
                                                                                            reply.execution().envelope()))));
                                    return Flux.concat(
                                            results,
                                            capabilityEvents(
                                                    definition,
                                                    request.tenantId(),
                                                    request.userId()),
                                            streamAgent(
                                                    definition,
                                                    supervisorSummaryMessage(
                                                            definition, request.message(), replies),
                                                    runtimeContext(request),
                                                    request.taskContext(),
                                                    request.images(),
                                                    request.tenantId(),
                                                    request.userId()));
                                }));
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

    private List<SubagentBinding> selectSubagents(AgentDefinition definition, String message) {
        List<SubagentBinding> bindings = definition.orchestration().subagents();
        if (bindings.isEmpty()) {
            return List.of();
        }
        if (bindings.size() == 1) {
            return List.of(bindings.get(0));
        }
        String normalized = safe(message, "").toLowerCase();
        List<ScoredSubagent> scored = new ArrayList<>();
        for (SubagentBinding binding : bindings) {
            AgentDefinition target = definition(binding.targetAgentId());
            int score =
                    matchScore(normalized, binding.bindingId())
                            + matchScore(normalized, binding.role())
                            + matchScore(normalized, binding.description())
                            + matchScore(normalized, target.agentId())
                            + matchScore(normalized, target.name());
            scored.add(new ScoredSubagent(binding, score));
        }
        List<SubagentBinding> matched =
                scored.stream()
                        .filter(item -> item.score() > 0)
                        .sorted(Comparator.comparingInt(ScoredSubagent::score).reversed())
                        .map(ScoredSubagent::binding)
                        .toList();
        // A supervisor with multiple bindings is an ensemble: when no lexical hint exists,
        // ask every declared specialist instead of silently picking an arbitrary one.
        return matched.isEmpty() ? bindings : matched;
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

    private Mono<List<SubagentReply>> runSubagents(
            AgentDefinition supervisor,
            ChatRequest request,
            List<SubagentBinding> bindings) {
        List<Mono<SubagentReply>> calls =
                bindings.stream()
                        .map(
                                binding -> {
                                    AgentDefinition target = definition(binding.targetAgentId());
                                    return callAgent(
                                                    target,
                                                    request,
                                                    subagentMessage(binding, request.message()),
                                                    subagentContext(request, binding))
                                            .map(
                                                    execution ->
                                                            new SubagentReply(
                                                                    binding, target, execution));
                                })
                        .toList();
        return Flux.merge(calls).collectList();
    }

    private String supervisorSummaryMessage(
            AgentDefinition supervisor, String userMessage, List<SubagentReply> replies) {
        StringBuilder summary =
                new StringBuilder(
                        "You are the supervisor agent. Several specialists have completed their tasks.\n\n"
                                + "User request:\n"
                                + safe(userMessage, "")
                                + "\n\n");
        for (SubagentReply reply : replies) {
            SubagentBinding binding = reply.binding();
            AgentDefinition target = reply.target();
            String text =
                    reply.execution().message() == null
                            ? ""
                            : safe(reply.execution().message().getTextContent(), "");
            summary.append("Subagent:\n");
            summary.append("- binding_id: ").append(safe(binding.bindingId(), "")).append("\n");
            summary.append("- target_agent_id: ")
                    .append(safe(target.agentId(), supervisor.agentId()))
                    .append("\n");
            summary.append("- role: ").append(safe(binding.role(), "")).append("\n");
            summary.append("- description: ")
                    .append(safe(binding.description(), ""))
                    .append("\n");
            summary.append("- task_id: ")
                    .append(reply.execution().envelope().taskId())
                    .append("\n");
            summary.append("- duration_ms: ")
                    .append(reply.execution().envelope().durationMs())
                    .append("\n");
            summary.append("Subagent result:\n").append(text).append("\n\n");
        }
        summary.append(
                "Produce the final answer for the user. Preserve useful details, resolve contradictions, "
                        + "and do not mention internal orchestration unless it helps the user understand the result.");
        return summary.toString();
    }

    private AgentTaskEnvelope supervisorEnvelope(
            AgentTaskEnvelope supervisorTask, List<SubagentReply> replies) {
        Map<String, Object> metadata = new LinkedHashMap<>(supervisorTask.metadata());
        metadata.put("orchestration", "SUPERVISOR");
        metadata.put("parallel", true);
        metadata.put("child_call_count", replies.size());
        metadata.put(
                "child_tasks",
                replies.stream()
                        .map(
                                reply ->
                                        Map.of(
                                                "task_id", reply.execution().envelope().taskId(),
                                                "agent_id", reply.target().agentId(),
                                                "duration_ms", reply.execution().envelope().durationMs()))
                        .toList());
        return new AgentTaskEnvelope(
                supervisorTask.contractVersion(),
                supervisorTask.request(),
                supervisorTask.result(),
                supervisorTask.startedAt(),
                supervisorTask.finishedAt(),
                metadata);
    }

    private Mono<TaskExecution> callAgent(
            AgentDefinition definition, String message, RuntimeContext context) {
        return callAgent(
                definition,
                List.of(),
                message,
                context,
                "",
                "",
                TaskContext.root("", definition.agentId()));
    }

    private Mono<TaskExecution> callAgent(
            AgentDefinition definition,
            ChatRequest request,
            String message,
            RuntimeContext context) {
        return callAgent(
                definition,
                request.images(),
                message,
                context,
                request.tenantId(),
                request.userId(),
                request.taskContext().child(
                        request.taskContext().sourceAgentId(), definition.agentId(),
                        request.taskContext().stepId()));
    }

    private Mono<TaskExecution> callAgent(
            AgentDefinition definition,
            List<ChatImage> images,
            String message,
            RuntimeContext context,
            String tenantId,
            String userId,
            TaskContext taskContext) {
        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        TaskRequest taskRequest =
                new TaskRequest(
                        taskContext.withTarget(definition.agentId()),
                        Map.of("text", safe(message, "")));
        Mono<Msg> invocation =
                Mono.fromRunnable(
                        () ->
                                platformState.projectMemoriesToAgentWorkspace(
                                        definition, context.getUserId()))
                .then(
                        Mono.defer(
                                () -> {
                                     HarnessAgent harness = agent(definition, tenantId, userId);
                                    if (harness == null) {
                                        return Mono.error(
                                                new AgentRuntimeException(
                                                        "Unable to create agent harness: "
                                                                + definition.agentId()));
                                    }
                                    ScheduledTaskCallContext.Scope scope =
                                            ScheduledTaskCallContext.open(userId, tenantId);
                                    Mono<Msg> result;
                                    try {
                                        result = harness.call(userMessage(message, images), context);
                                    } catch (Throwable error) {
                                        scope.close();
                                        return Mono.error(error);
                                    }
                                    return result == null
                                            ? Mono.using(
                                                    () -> scope,
                                                    ignored ->
                                                            Mono.error(
                                                                    new AgentRuntimeException(
                                                                            "Agent harness returned no result: "
                                                                                    + definition.agentId())),
                                                    ScheduledTaskCallContext.Scope::close)
                                            : result.doFinally(signal -> scope.close());
                                }));
        Duration remaining = remaining(taskRequest.context().deadlineAt());
        if (remaining != null) {
            invocation = invocation.timeout(remaining);
        }
        return invocation
                .map(
                        msg -> {
                            Instant finishedAt = Instant.now();
                            TaskResult taskResult =
                                    new TaskResult(
                                            taskRequest.context().taskId(),
                                            TaskStatus.COMPLETED,
                                            msg.getTextContent(),
                                            Map.of(),
                                            null,
                                            Map.of("duration_ms", Duration.between(startedAt, finishedAt).toMillis()));
                            return new TaskExecution(
                                    msg,
                                    AgentTaskEnvelope.completed(
                                            taskRequest,
                                            taskResult,
                                            startedAt,
                                            finishedAt,
                                            Map.of("agent_id", definition.agentId(), "tenant_id", safe(tenantId, ""), "user_id", safe(userId, ""))));
                        })
                .onErrorMap(
                        error -> {
                            if (error instanceof AgentTaskException) return error;
                            Instant finishedAt = Instant.now();
                            TaskStatus status = taskStatus(error);
                            AgentTaskEnvelope task =
                                    AgentTaskEnvelope.failed(
                                            taskRequest,
                                            status,
                                            error,
                                            startedAt,
                                            finishedAt,
                                            Map.of(
                                                    "agent_id",
                                                    definition.agentId(),
                                                    "tenant_id",
                                                    safe(tenantId, ""),
                                                    "user_id",
                                                    safe(userId, "")));
                            return new AgentTaskException(
                                    "Agent task "
                                            + task.taskId()
                                            + " failed for "
                                            + definition.agentId()
                                            + " ("
                                            + status.name()
                                            + ")",
                                    task,
                                    error);
                        })
                .doFinally(
                        signal ->
                                platformState.importAgentWorkspaceMemories(
                                        definition,
                                        context.getUserId(),
                                        context.getSessionId(),
                                        runId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Duration remaining(Instant deadlineAt) {
        if (deadlineAt == null) return null;
        long millis = Duration.between(Instant.now(), deadlineAt).toMillis();
        return Duration.ofMillis(Math.max(1L, millis));
    }

    private static TaskStatus taskStatus(Throwable error) {
        if (error instanceof TimeoutException) return TaskStatus.TIMEOUT;
        if (error instanceof CancellationException) return TaskStatus.CANCELLED;
        return TaskStatus.FAILED;
    }

    private AgentTaskEnvelope completedEnvelope(
            ChatRequest request,
            String targetAgentId,
            String content,
            Instant startedAt,
            Map<String, Object> metadata) {
        Instant finishedAt = Instant.now();
        TaskRequest taskRequest =
                new TaskRequest(
                        request.taskContext().child(
                                request.taskContext().sourceAgentId(), targetAgentId, "workflow"),
                        Map.of("text", safe(content, "")));
        TaskResult taskResult =
                new TaskResult(
                        taskRequest.context().taskId(),
                        TaskStatus.COMPLETED,
                        safe(content, ""),
                        Map.of(),
                        null,
                        Map.of("duration_ms", Duration.between(startedAt, finishedAt).toMillis()));
        return AgentTaskEnvelope.completed(
                taskRequest, taskResult, startedAt, finishedAt, metadata);
    }

    private Flux<AgentEventEnvelope> streamAgent(
            AgentDefinition definition, String message, RuntimeContext context) {
        return streamAgent(definition, message, context, null, List.of(), "", "");
    }

    private Flux<AgentEventEnvelope> streamAgent(
            AgentDefinition definition,
            String message,
            RuntimeContext context,
            TaskContext taskContext,
            List<ChatImage> images,
            String tenantId,
            String userId) {
        String runId = UUID.randomUUID().toString();
        return Mono.fromRunnable(
                        () ->
                                platformState.projectMemoriesToAgentWorkspace(
                                        definition, context.getUserId()))
                .thenMany(
                        Flux.defer(
                                () ->
                                        agent(definition, tenantId, userId)
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
            Msg subagentReply,
            AgentTaskEnvelope task) {
        String text = subagentReply == null ? "" : safe(subagentReply.getTextContent(), "");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent_id", definition.agentId());
        payload.put("target_agent_id", target.agentId());
        payload.put("binding_id", safe(binding.bindingId(), ""));
        payload.put("result_preview", abbreviate(text, 500));
        if (task != null) {
            payload.put("task_id", task.taskId());
            payload.put("duration_ms", task.durationMs());
            payload.put("contract_version", task.contractVersion());
        }
        return runtimeEvent(
                definition.agentId(),
                "supervisor_subagent_result",
                "Subagent "
                        + target.agentId()
                        + " completed"
                        + (text.isBlank() ? "" : ": " + abbreviate(text, 160)),
                payload);
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

    private record TaskExecution(Msg message, AgentTaskEnvelope envelope) {}

    private record ScoredSubagent(SubagentBinding binding, int score) {}

    private record SubagentReply(
            SubagentBinding binding, AgentDefinition target, TaskExecution execution) {}

    private record BranchResult(String joinNodeId, ContractValue value) {}

    private record WorkflowStepExecution(String text, AgentTaskEnvelope task) {}

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

    private HarnessAgent agent(
            AgentDefinition definition, String tenantId, String userId) {
        String key =
                definition.agentId()
                        + ":"
                        + definition.version()
                        + ":"
                        + safe(tenantId, "platform")
                        + ":"
                        + safe(userId, "anonymous");
        return agentCache.computeIfAbsent(
                key,
                ignored ->
                        tenantAwareHarnessFactory
                                ? harnessFactory.create(definition, tenantId, userId)
                                : harnessFactory.create(definition));
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

    private ChatResponse response(
            String agentId, ChatRequest request, Msg msg, AgentTaskEnvelope task) {
        return response(
                agentId,
                request,
                msg == null ? "" : msg.getTextContent(),
                task);
    }

    private ChatResponse response(String agentId, ChatRequest request, String content) {
        return new ChatResponse(agentId, userKey(request), sessionKey(request), safe(content, ""));
    }

    private ChatResponse response(
            String agentId, ChatRequest request, String content, AgentTaskEnvelope task) {
        return new ChatResponse(
                agentId,
                userKey(request),
                sessionKey(request),
                safe(content, ""),
                task);
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
