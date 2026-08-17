/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.EmbeddingModelRegistry;
import io.agent.platform.control.PlatformArtifactStore;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.ToolRegistry;
import io.agent.platform.control.ToolSpec;
import io.agent.platform.runtime.AgentEventEnvelope;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.runtime.ChatRequest;
import io.agent.platform.tool.PythonScriptTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/platform/frontend")
public class PlatformFrontendCompatibilityController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformCompatibilityState state;
    private final PlatformArtifactStore artifactStore;
    private final AgentRuntime runtime;
    private final EmbeddingModelRegistry embeddingModelRegistry;
    private final McpToolDiscoveryService mcpToolDiscoveryService;
    private final ToolRegistry toolRegistry;
    private final PlatformStorageLayer storage;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final KnowledgeCollectionService knowledgeCollectionService;
    private final WorkflowAssetService workflowAssetService;
    private final WorkflowToolRegistry workflowToolRegistry;
    private final PlatformAuthService auth;
    private final AgentAssetService agentAssetService;
    private final PlatformAssetAccessService assetAccess;
    private final PlatformUserCapabilityService userCapabilities;
    private final WebClient webClient = WebClient.builder().build();

    public PlatformFrontendCompatibilityController(
            PlatformCompatibilityState state,
            PlatformArtifactStore artifactStore,
            AgentRuntime runtime,
            EmbeddingModelRegistry embeddingModelRegistry,
            McpToolDiscoveryService mcpToolDiscoveryService,
            ToolRegistry toolRegistry,
            PlatformStorageLayer storage,
            DocumentKnowledgeService documentKnowledgeService,
            KnowledgeCollectionService knowledgeCollectionService,
            WorkflowAssetService workflowAssetService,
            WorkflowToolRegistry workflowToolRegistry,
            PlatformAuthService auth,
            AgentAssetService agentAssetService,
            PlatformAssetAccessService assetAccess,
            PlatformUserCapabilityService userCapabilities) {
        this.state = state;
        this.artifactStore = artifactStore;
        this.runtime = runtime;
        this.embeddingModelRegistry = embeddingModelRegistry;
        this.mcpToolDiscoveryService = mcpToolDiscoveryService;
        this.toolRegistry = toolRegistry;
        this.storage = storage;
        this.documentKnowledgeService = documentKnowledgeService;
        this.knowledgeCollectionService = knowledgeCollectionService;
        this.workflowAssetService = workflowAssetService;
        this.workflowToolRegistry = workflowToolRegistry;
        this.auth = auth;
        this.agentAssetService = agentAssetService;
        this.assetAccess = assetAccess;
        this.userCapabilities = userCapabilities;
    }

    private PlatformAuthService.Principal principal(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst("platform_session");
        return auth.current(cookie == null ? "" : cookie.getValue());
    }

    private PlatformAuthService.Principal requirePrincipal(ServerHttpRequest request) {
        var current = principal(request);
        if (current == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录");
        }
        return current;
    }

    private Map<String, Object> readableRun(
            String runId, PlatformAuthService.Principal current) {
        Map<String, Object> run = state.run(runId);
        String owner = String.valueOf(run.getOrDefault("user_id", ""));
        if (run.isEmpty() || (!"PLATFORM_ADMIN".equals(current.role()) && !current.userId().equals(owner))) {
            throw new PlatformAuthService.AuthException(404, "运行记录不存在或当前账号无权访问");
        }
        return run;
    }

    private boolean memoryReadable(
            Map<String, Object> row, PlatformAuthService.Principal current) {
        if (row == null || row.isEmpty() || "not_found".equals(row.get("status"))) return false;
        if ("PLATFORM_ADMIN".equals(current.role())) return true;
        return current.userId().equals(String.valueOf(row.get("user_id")))
                && current.orgId().equals(String.valueOf(row.get("org_id")));
    }

    private void requireMemoryReadable(
            Map<String, Object> row, PlatformAuthService.Principal current) {
        if (!memoryReadable(row, current)) {
            throw new PlatformAuthService.AuthException(404, "记忆不存在或当前账号无权访问");
        }
    }

    private void requireMemoryWritable(
            Map<String, Object> row, PlatformAuthService.Principal current) {
        requireMemoryReadable(row, current);
    }

    @GetMapping("/infra/health")
    public Map<String, Object> health() {
        return map("status", "ok", "time", Instant.now().toString());
    }

    @GetMapping("/infra/status")
    public Map<String, Object> status() {
        return map(
                "app_domain",
                "platform",
                "domains",
                domainsMap(),
                "databases",
                map("platform_configured", true, "active_domain_configured", true),
                "redis",
                map("enabled", false, "configured", false),
                "kafka",
                map("enabled", false, "available", false),
                "object_storage",
                map("minio_enabled", false, "bucket", ""),
                "rag",
                map(
                        "bm25_service",
                        map("configured", false, "type", "compat"),
                        "vector_service",
                        map("configured", false),
                        "vector_secondary_service",
                        map("configured", false)),
                "runtime_sandbox",
                map("status", "compat", "platform_service", true, "backends", List.of("compat")));
    }

    @GetMapping("/domains")
    public Map<String, Object> domains() {
        return map("items", state.domains(), "domains", state.domains());
    }

    @PostMapping("/domains")
    public Map<String, Object> upsertDomain(@RequestBody Map<String, Object> payload) {
        Map<String, Object> domain = state.upsertDomain(payload);
        return map("ok", true, "item", domain, "domain", domain);
    }

    @GetMapping("/agents")
    public Map<String, Object> agents(
            @RequestParam(name = "domain", required = false) String domain,
            ServerHttpRequest request) {
        var rows = agentAssetService.filterRows(state.agents(), principal(request));
        return map("items", rows, "agents", rows);
    }

    @GetMapping("/agents/{agentId}")
    public Map<String, Object> agent(
            @PathVariable("agentId") String agentId, ServerHttpRequest request) {
        agentAssetService.requireReadable(agentId, principal(request));
        return state.agents().stream()
                .filter(row -> agentId.equals(row.get("agent_id")))
                .findFirst()
                .orElseGet(() -> map("agent_id", agentId, "display_name", agentId));
    }

    @GetMapping("/agents/{agentId}/spec")
    public Map<String, Object> agentSpec(
            @PathVariable("agentId") String agentId, ServerHttpRequest request) {
        agentAssetService.requireReadable(agentId, principal(request));
        return state.agentSpec(agentId);
    }

    @PutMapping("/agents/{agentId}/spec")
    public Map<String, Object> saveAgentSpec(
            @PathVariable("agentId") String agentId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        var current = requirePrincipal(request);
        if (agentAssetService.hasMetadata(agentId)) {
            agentAssetService.requireWritable(agentId, current);
        }
        Map<String, Object> agent = state.saveAgentSpec(agentId, payload);
        agentAssetService.registerUserAsset(agentId, current, payload);
        runtime.evict(agentId);
        return map("ok", true, "item", agent, "agent", agent);
    }

    @DeleteMapping("/agents/{agentId}/spec")
    public Map<String, Object> deleteAgentSpec(
            @PathVariable("agentId") String agentId, ServerHttpRequest request) {
        var current = requirePrincipal(request);
        agentAssetService.requireWritable(agentId, current);
        state.deleteAgentSpec(agentId);
        agentAssetService.remove(agentId, current);
        runtime.evict(agentId);
        return map("ok", true, "agent_id", agentId);
    }

    @PostMapping("/agents")
    public Map<String, Object> upsertAgent(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        var current = requirePrincipal(request);
        Map<String, Object> agent = state.upsertAgent(payload);
        agentAssetService.registerUserAsset(String.valueOf(agent.get("agent_id")), current, payload);
        return map("ok", true, "agent_id", agent.get("agent_id"), "item", agent, "agent", agent);
    }

    @PatchMapping("/agents/{agentId}")
    public Map<String, Object> patchAgent(
            @PathVariable("agentId") String agentId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        var current = requirePrincipal(request);
        if (agentAssetService.hasMetadata(agentId)) {
            agentAssetService.requireWritable(agentId, current);
        }
        payload.put("agent_id", agentId);
        Map<String, Object> agent = state.upsertAgent(payload);
        agentAssetService.registerUserAsset(agentId, current, payload);
        return map("item", agent, "agent", agent);
    }

    @GetMapping("/agents/runs")
    public Map<String, Object> runs(
            @RequestParam(name = "agent_id", required = false) String agent_id,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            ServerHttpRequest request) {
        var current = requirePrincipal(request);
        List<Map<String, Object>> rows = state.runs(agent_id, status, limit, current.userId());
        return map("items", rows, "runs", rows);
    }

    @PostMapping("/agents/runs")
    public Mono<Map<String, Object>> createRun(
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        var current = requirePrincipal(request);
        String userId = current.userId();
        String orgId = current.orgId();
        String agentId = string(payload.get("agent_id"), "platform_knowledge_agent");
        agentAssetService.requireReadable(agentId, current);
        Map<String, Object> body = objectMap(payload.get("payload"));
        String query = string(body.get("query"), string(payload.get("query"), ""));
        DocumentKnowledgeService.Retrieval retrieval =
                documentKnowledgeService.retrieveScoped(
                        query, requestedDocumentScope(payload), 4, current);
        String runtimeQuery = DocumentKnowledgeService.withContext(query, retrieval);
        String sessionId = string(payload.get("session_id"), "default");
        Map<String, Object> run = state.createRun(agentId, query, userId);
        String runId = string(run.get("run_id"), "");
        state.appendSessionMessage(agentId, sessionId, userId, "user", query);
        return runtime.chat(agentId, new ChatRequest(orgId, userId, sessionId, runtimeQuery))
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        response -> {
                            String answer = string(response.text(), "");
                            Map<String, Object> finished = state.finishRun(runId, answer);
                            boolean succeeded = "succeeded".equals(finished.get("status"));
                            state.appendSessionMessage(
                                    agentId, sessionId, userId, "assistant", answer);
                            return map(
                                    "ok",
                                    succeeded,
                                    "run",
                                    finished,
                                    "run_id",
                                    finished.get("run_id"),
                                    "status",
                                    finished.get("status"),
                                    "answer",
                                    answer,
                                    "result",
                                    map(
                                            "answer",
                                            answer,
                                            "text",
                                            answer,
                                            "citations",
                                            retrieval.citations()));
                        })
                .onErrorResume(
                        error -> {
                            Map<String, Object> failed = state.failRun(runId, error);
                            String message = string(error.getMessage(), "执行失败");
                            return Mono.just(
                                    map(
                                            "ok",
                                            false,
                                            "run",
                                            failed,
                                            "run_id",
                                            failed.get("run_id"),
                                            "status",
                                            failed.get("status"),
                                            "error",
                                            message,
                                            "result",
                                            map("error", message)));
                        });
    }

    @GetMapping("/agents/runs/{runId}")
    public Map<String, Object> run(
            @PathVariable("runId") String runId, ServerHttpRequest request) {
        return map("run", readableRun(runId, requirePrincipal(request)));
    }

    @GetMapping("/agents/runs/{runId}/steps")
    public Map<String, Object> runSteps(
            @PathVariable("runId") String runId, ServerHttpRequest request) {
        readableRun(runId, requirePrincipal(request));
        List<Map<String, Object>> rows = state.runSteps(runId);
        return map("items", rows, "steps", rows);
    }

    @GetMapping("/agents/runs/{runId}/events")
    public Map<String, Object> runEvents(
            @PathVariable("runId") String runId, ServerHttpRequest request) {
        readableRun(runId, requirePrincipal(request));
        List<Map<String, Object>> rows = state.runEvents(runId);
        return map("items", rows, "events", rows, "next_after_id", rows.size());
    }

    @GetMapping("/agents/runs/{runId}/waiting")
    public Map<String, Object> waiting(
            @PathVariable("runId") String runId, ServerHttpRequest request) {
        readableRun(runId, requirePrincipal(request));
        return map("item", state.waiting(runId));
    }

    @PostMapping("/agents/runs/{runId}/waiting/{waitingId}/resume")
    public Map<String, Object> resumeWaiting(
            @PathVariable("runId") String runId,
            @PathVariable("waitingId") String waitingId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        readableRun(runId, requirePrincipal(request));
        Map<String, Object> item =
                state.resumeWaiting(runId, waitingId, payload == null ? Map.of() : payload);
        return map(
                "ok",
                !"not_found".equals(item.get("status")),
                "run_id",
                runId,
                "waiting_id",
                waitingId,
                "status",
                item.get("status"),
                "item",
                item);
    }

    @PostMapping("/agents/runs/{runId}/waiting/{waitingId}/reject")
    public Map<String, Object> rejectWaiting(
            @PathVariable("runId") String runId,
            @PathVariable("waitingId") String waitingId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        readableRun(runId, requirePrincipal(request));
        Map<String, Object> item =
                state.rejectWaiting(runId, waitingId, payload == null ? Map.of() : payload);
        return map(
                "ok",
                !"not_found".equals(item.get("status")),
                "run_id",
                runId,
                "waiting_id",
                waitingId,
                "status",
                item.get("status"),
                "item",
                item);
    }

    @GetMapping("/workflows")
    public Map<String, Object> workflows(
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "status", required = false) String status,
            ServerHttpRequest request) {
        List<Map<String, Object>> rows =
                workflowAssetService.list(domain, status, requirePrincipal(request));
        return map("items", rows, "workflows", rows);
    }

    @GetMapping("/workflows/{workflowId}")
    public Map<String, Object> workflow(
            @PathVariable("workflowId") String workflowId, ServerHttpRequest request) {
        Map<String, Object> item = workflowAssetService.get(workflowId, requirePrincipal(request));
        return map("item", item, "workflow", item);
    }

    @PostMapping("/workflows/{workflowId}/validate")
    public Map<String, Object> validateWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        var principal = requirePrincipal(request);
        var result =
                payload == null || payload.isEmpty()
                        ? workflowAssetService.validateContracts(workflowId, principal)
                        : workflowAssetService.validateContracts(workflowId, payload, principal);
        List<Map<String, Object>> diagnostics =
                result.diagnostics().stream()
                        .map(
                                diagnostic ->
                                        map(
                                                "severity",
                                                diagnostic.severity().name().toLowerCase(),
                                                "code",
                                                diagnostic.code(),
                                                "node_id",
                                                diagnostic.nodeId(),
                                                "port_id",
                                                diagnostic.portId(),
                                                "edge_id",
                                                diagnostic.edgeId(),
                                                "message",
                                                diagnostic.message(),
                                                "suggestions",
                                                diagnostic.suggestions()))
                        .toList();
        return map("ok", result.valid(), "valid", result.valid(), "diagnostics", diagnostics);
    }

    @PostMapping("/workflows")
    public Map<String, Object> createWorkflow(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        Map<String, Object> item = workflowAssetService.create(payload, requirePrincipal(request));
        return map("ok", true, "item", item, "workflow", item);
    }

    @PutMapping("/workflows/{workflowId}")
    public Map<String, Object> saveWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        Map<String, Object> item =
                workflowAssetService.save(workflowId, payload, requirePrincipal(request));
        return map("ok", true, "item", item, "workflow", item);
    }

    @DeleteMapping("/workflows/{workflowId}")
    public Map<String, Object> deleteWorkflow(
            @PathVariable("workflowId") String workflowId, ServerHttpRequest request) {
        workflowAssetService.delete(workflowId, requirePrincipal(request));
        return map("ok", true, "workflow_id", workflowId);
    }

    @PostMapping("/workflows/{workflowId}/publish")
    public Map<String, Object> publishWorkflow(
            @PathVariable("workflowId") String workflowId, ServerHttpRequest request) {
        Map<String, Object> item =
                workflowAssetService.publish(workflowId, requirePrincipal(request));
        return map("ok", true, "item", item, "workflow", item);
    }

    @PostMapping("/workflows/{workflowId}/unpublish")
    public Map<String, Object> unpublishWorkflow(
            @PathVariable("workflowId") String workflowId, ServerHttpRequest request) {
        Map<String, Object> item =
                workflowAssetService.unpublish(workflowId, requirePrincipal(request));
        return map("ok", true, "item", item, "workflow", item);
    }

    @GetMapping("/workflow-tools")
    public Map<String, Object> workflowTools(ServerHttpRequest request) {
        requirePrincipal(request);
        return map("items", workflowToolRegistry.rows(), "tools", workflowToolRegistry.rows());
    }

    @PostMapping("/workflow-tools")
    public Map<String, Object> registerWorkflowTool(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        var item = workflowToolRegistry.register(payload);
        return map("ok", true, "item", item, "tool", item);
    }

    @PutMapping("/workflow-tools/{toolId}")
    public Map<String, Object> updateWorkflowTool(
            @PathVariable("toolId") String toolId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        var item = workflowToolRegistry.update(toolId, payload);
        return map("ok", true, "item", item, "tool", item);
    }

    @DeleteMapping("/workflow-tools/{toolId}")
    public Map<String, Object> deleteWorkflowTool(
            @PathVariable("toolId") String toolId, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        workflowToolRegistry.delete(toolId);
        return map("ok", true, "tool_id", toolId);
    }

    @PostMapping("/workflows/{workflowId}/run")
    public Mono<Map<String, Object>> runWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        var principal = requirePrincipal(request);
        var workflow = workflowAssetService.requirePublished(workflowId, principal);
        String userId = principal.userId();
        String orgId = principal.orgId();
        Map<String, Object> body = payload == null ? Map.of() : payload;
        String query = string(body.get("query"), string(body.get("message"), ""));
        String sessionId = string(body.get("session_id"), "workflow_" + workflowId);
        String runtimeId = "workflow:" + workflowId;
        Map<String, Object> run = state.createRun(runtimeId, query, userId);
        String runId = string(run.get("run_id"), "");
        state.appendSessionMessage(runtimeId, sessionId, userId, "user", query);
        return runtime.workflow(workflow, new ChatRequest(orgId, userId, sessionId, query))
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        response -> {
                            String answer = string(response.text(), "");
                            Map<String, Object> finished = state.finishRun(runId, answer);
                            state.appendSessionMessage(
                                    runtimeId, sessionId, userId, "assistant", answer);
                            return map(
                                    "ok",
                                    true,
                                    "run",
                                    finished,
                                    "run_id",
                                    runId,
                                    "status",
                                    finished.get("status"),
                                    "answer",
                                    answer,
                                    "result",
                                    map("answer", answer, "text", answer));
                        })
                .onErrorResume(
                        error -> {
                            Map<String, Object> failed = state.failRun(runId, error);
                            String message = string(error.getMessage(), "执行失败");
                            return Mono.just(
                                    map(
                                            "ok",
                                            false,
                                            "run",
                                            failed,
                                            "run_id",
                                            runId,
                                            "status",
                                            "failed",
                                            "error",
                                            message));
                        });
    }

    @PostMapping(
            value = "/workflows/{workflowId}/run/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> streamWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        var principal = requirePrincipal(request);
        var workflow = workflowAssetService.requirePublished(workflowId, principal);
        String userId = principal.userId();
        String orgId = principal.orgId();
        Map<String, Object> body = payload == null ? Map.of() : payload;
        String query = string(body.get("query"), string(body.get("message"), ""));
        String sessionId = string(body.get("session_id"), "workflow_" + workflowId);
        String runtimeId = "workflow:" + workflowId;
        Map<String, Object> run = state.createRun(runtimeId, query, userId);
        String runId = string(run.get("run_id"), "");
        state.appendSessionMessage(runtimeId, sessionId, userId, "user", query);
        AtomicReference<StringBuilder> answer = new AtomicReference<>(new StringBuilder());
        return Flux.concat(
                        Flux.just(
                                sse(
                                        "activity",
                                        map(
                                                "type",
                                                "activity",
                                                "step",
                                                "receive",
                                                "title",
                                                "接收 Workflow 请求",
                                                "status",
                                                "success",
                                                "summary",
                                                workflow.name(),
                                                "run_id",
                                                runId))),
                        runtime.workflowStream(
                                        workflow,
                                        new ChatRequest(orgId, userId, sessionId, query))
                                .map(
                                        event -> {
                                            state.appendRunEventFromEnvelope(runId, event);
                                            return frontendStreamEvent(
                                                    event, answer.get(), runId);
                                        }),
                        Mono.fromSupplier(
                                () -> {
                                    String text = answer.get().toString();
                                    Map<String, Object> finished = state.finishRun(runId, text);
                                    state.appendSessionMessage(
                                            runtimeId, sessionId, userId, "assistant", text);
                                    return sse(
                                            "done",
                                            map(
                                                    "type",
                                                    "done",
                                                    "run_id",
                                                    runId,
                                                    "status",
                                                    "succeeded",
                                                    "result",
                                                    map("answer", text, "text", text),
                                                    "run",
                                                    finished));
                                }))
                .onErrorResume(
                        error -> {
                            Map<String, Object> failed = state.failRun(runId, error);
                            String message = string(error.getMessage(), "执行失败");
                            return Flux.just(
                                    sse(
                                            "error",
                                            map(
                                                    "type",
                                                    "error",
                                                    "run_id",
                                                    runId,
                                                    "status",
                                                    "failed",
                                                    "message",
                                                    message,
                                                    "run",
                                                    failed)));
                        });
    }

    @GetMapping("/flows")
    public Map<String, Object> flows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(
                map(
                        "flow_id",
                        "agentscope_runtime",
                        "id",
                        "agentscope_runtime",
                        "display_name",
                        "AgentScope Runtime",
                        "capabilities",
                        map(
                                "supports_tool_calling",
                                true,
                                "supports_skill_tools",
                                true,
                                "supports_memory",
                                false),
                        "nodes",
                        List.of()));
        workflowAssetService.list(null, "PUBLISHED").forEach(row -> {
            Map<String, Object> flow = new LinkedHashMap<>(row);
            flow.put("flow_id", row.get("workflow_id"));
            flow.put("id", row.get("workflow_id"));
            flow.put("display_name", row.get("name"));
            rows.add(flow);
        });
        return map("items", rows, "flows", rows);
    }

    @GetMapping("/tools")
    public Map<String, Object> tools(
            @RequestParam(name = "domain", required = false) String domain,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        List<Map<String, Object>> rows =
                new ArrayList<>(assetAccess.filterRows("TOOL", state.tools(), "tool_id", current));
        userCapabilities.tools(current).stream()
                .map(tool -> userCapabilities.enrichToolRow(tool, current))
                .forEach(rows::add);
        workflowToolRegistry.rows().forEach(rows::add);
        return map("items", rows, "tools", rows);
    }

    @PutMapping("/tools/bindings/{toolId}")
    public Map<String, Object> toolBinding(
            @PathVariable("toolId") String toolId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        Map<String, Object> binding = state.saveToolBinding(toolId, payload);
        return map("ok", true, "tool_id", toolId, "binding", binding);
    }

    @PutMapping("/tools/agents/{agentId}/policies/{toolId}")
    public Map<String, Object> toolPolicy(
            @PathVariable("agentId") String agentId,
            @PathVariable("toolId") String toolId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return map("ok", true, "agent_id", agentId, "tool_id", toolId, "policy", payload);
    }

    @PostMapping({"/tools/http", "/tools/db-query", "/tools/sandbox-script"})
    public Map<String, Object> createTool(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        String endpoint = string(payload.get("endpoint"), string(payload.get("url"), ""));
        if (!endpoint.isBlank()) {
            return map("ok", true, "item", userCapabilities.createTool(payload, current));
        }
        auth.requireAdmin(current);
        return map(
                "ok",
                true,
                "tool_id",
                payload.getOrDefault("tool_id", payload.getOrDefault("name", "tool")),
                "message",
                "工具已由兼容层接收",
                "item",
                payload);
    }

    @PostMapping("/tools/python")
    public Map<String, Object> createPythonTool(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        String toolId = string(payload.getOrDefault("tool_id", payload.get("name")), "").strip();
        if (toolId.isBlank()) {
            throw new IllegalArgumentException("tool_id is required.");
        }
        String script = string(payload.get("script"), "").strip();
        if (script.isBlank()) {
            throw new IllegalArgumentException("script is required.");
        }
        Path dir = storage.toolCodeDirectory(toolId);
        Path scriptPath = dir.resolve("tool.py");
        try {
            Files.createDirectories(dir);
            Files.writeString(scriptPath, script);
            artifactStore.saveToolFile(toolId, "tool.py", script.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save Python tool script: " + toolId, e);
        }
        Map<String, Object> schema =
                objectMap(payload.getOrDefault("parameter_schema", payload.get("parameters")));
        if (schema.isEmpty()) {
            schema = Map.of("type", "object", "properties", Map.of());
        }
        ToolSpec spec =
                new ToolSpec(
                        toolId,
                        "python",
                        "",
                        string(payload.get("description"), toolId),
                        true,
                        storage.toWorkspaceRelative(scriptPath),
                        schema,
                        longValue(payload.get("timeout_ms"), 5000));
        toolRegistry.upsert(spec);
        assetAccess.ensurePublic("TOOL", spec.toolId());
        return map(
                "ok",
                true,
                "tool_id",
                toolId,
                "message",
                "Python 工具已保存并注册到工具目录",
                "item",
                map(
                        "tool_id",
                        toolId,
                        "type",
                        "python",
                        "script_path",
                        spec.scriptPath(),
                        "parameter_schema",
                        schema));
    }

    @GetMapping("/tools/python/{toolId}")
    public Map<String, Object> getPythonTool(
            @PathVariable("toolId") String toolId, ServerHttpRequest request) {
        assetAccess.requireReadable("TOOL", toolId, requirePrincipal(request));
        ToolSpec spec =
                toolRegistry
                        .find(toolId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Tool not found: " + toolId));
        if (!"python".equals(spec.type())) {
            throw new IllegalArgumentException("Tool is not a Python tool: " + toolId);
        }
        Path scriptPath = resolveWorkspacePath(spec.scriptPath());
        String script;
        try {
            if (Files.isRegularFile(scriptPath)) {
                script = Files.readString(scriptPath);
            } else {
                byte[] persisted = artifactStore.loadToolFiles(toolId).get("tool.py");
                if (persisted == null) {
                    throw new IllegalStateException("Python tool script does not exist: " + toolId);
                }
                Files.createDirectories(scriptPath.getParent());
                Files.write(scriptPath, persisted);
                script = new String(persisted, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Python tool script: " + toolId, e);
        }
        return map(
                "ok",
                true,
                "tool_id",
                spec.toolId(),
                "description",
                spec.description(),
                "enabled",
                spec.enabled(),
                "script_path",
                spec.scriptPath(),
                "script",
                script,
                "parameter_schema",
                spec.parameterSchema(),
                "timeout_ms",
                spec.timeoutMs());
    }

    @PutMapping("/tools/python/{toolId}")
    public Map<String, Object> updatePythonTool(
            @PathVariable("toolId") String toolId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        ToolSpec existing =
                toolRegistry
                        .find(toolId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Tool not found: " + toolId));
        if (!"python".equals(existing.type())) {
            throw new IllegalArgumentException("Tool is not a Python tool: " + toolId);
        }
        String script = string(payload.get("script"), "").strip();
        if (script.isBlank()) {
            throw new IllegalArgumentException("script is required.");
        }
        Path scriptPath = resolveWorkspacePath(existing.scriptPath());
        try {
            Files.createDirectories(scriptPath.getParent());
            Files.writeString(scriptPath, script);
            artifactStore.saveToolFile(toolId, "tool.py", script.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update Python tool script: " + toolId, e);
        }
        Map<String, Object> schema =
                objectMap(payload.getOrDefault("parameter_schema", payload.get("parameters")));
        if (schema.isEmpty()) {
            schema =
                    existing.parameterSchema().isEmpty()
                            ? Map.of("type", "object", "properties", Map.of())
                            : existing.parameterSchema();
        }
        ToolSpec spec =
                new ToolSpec(
                        existing.toolId(),
                        existing.type(),
                        existing.className(),
                        string(payload.get("description"), existing.description()),
                        bool(payload.get("enabled"), existing.enabled()),
                        existing.scriptPath(),
                        schema,
                        longValue(payload.get("timeout_ms"), existing.timeoutMs()));
        toolRegistry.upsert(spec);
        return map(
                "ok",
                true,
                "tool_id",
                toolId,
                "message",
                "Python 工具已更新",
                "item",
                map(
                        "tool_id",
                        spec.toolId(),
                        "type",
                        "python",
                        "script_path",
                        spec.scriptPath(),
                        "parameter_schema",
                        schema,
                        "timeout_ms",
                        spec.timeoutMs()));
    }

    @PostMapping("/tools/python/validate")
    public Map<String, Object> validatePythonTool(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        Instant started = Instant.now();
        String toolId =
                string(payload.getOrDefault("tool_id", payload.get("name")), "draft").strip();
        String script = string(payload.get("script"), "").strip();
        if (script.isBlank()) {
            return map("ok", false, "stage", "input", "error", "script is required.");
        }
        Map<String, Object> args =
                objectMap(payload.getOrDefault("arguments", payload.get("sample_arguments")));
        Path dir = storage.toolTempDirectory(toolId);
        Path scriptPath = dir.resolve("tool.py");
        try {
            Files.createDirectories(dir);
            Files.writeString(scriptPath, script);
            Map<String, Object> compile = pythonCompile(scriptPath);
            if (!Boolean.TRUE.equals(compile.get("ok"))) {
                return map(
                        "ok",
                        false,
                        "stage",
                        "syntax",
                        "syntax",
                        compile,
                        "latency_ms",
                        Duration.between(started, Instant.now()).toMillis());
            }
            PythonScriptTool.ExecutionResult execution =
                    PythonScriptTool.execute(
                            pythonCommand(),
                            scriptPath,
                            toolId,
                            args,
                            Duration.ofMillis(longValue(payload.get("timeout_ms"), 5000)));
            return pythonExecutionResponse(
                    toolId,
                    Duration.between(started, Instant.now()).toMillis(),
                    execution,
                    compile);
        } catch (Exception e) {
            return map(
                    "ok",
                    false,
                    "stage",
                    "exception",
                    "error",
                    e.getMessage(),
                    "latency_ms",
                    Duration.between(started, Instant.now()).toMillis());
        }
    }

    @PostMapping("/tools/{toolId}/test")
    public Map<String, Object> testTool(
            @PathVariable("toolId") String toolId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (toolId.startsWith("mcp:")) {
            String[] parts = toolId.split(":", 3);
            if (parts.length >= 2) {
                assetAccess.requireReadable("MCP", parts[1], current);
            }
        } else {
            assetAccess.requireReadable("TOOL", toolId, current);
        }
        Instant started = Instant.now();
        if (!toolId.startsWith("mcp:")) {
            if (userCapabilities.findTool(toolId, current).isPresent()) {
                return userCapabilities.invokeTool(
                        toolId, objectMap(payload == null ? null : payload.get("arguments")), current);
            }
            return testLocalTool(toolId, payload, started);
        }
        String[] parts = toolId.split(":", 3);
        if (parts.length < 3) {
            return map("ok", false, "tool_id", toolId, "error", "Invalid MCP tool id.");
        }
        String serverId = parts[1];
        String toolName = parts[2];
        Map<String, Object> server =
                state.mcpServers().stream()
                        .filter(row -> serverId.equals(String.valueOf(row.get("id"))))
                        .findFirst()
                        .orElse(null);
        if (server == null) {
            return map(
                    "ok", false, "tool_id", toolId, "error", "MCP server not found: " + serverId);
        }
        Map<String, Object> args = objectMap(payload == null ? null : payload.get("arguments"));
        Map<String, Object> result = mcpToolDiscoveryService.callTool(server, toolName, args);
        long latency = Duration.between(started, Instant.now()).toMillis();
        return map(
                "ok",
                result.getOrDefault("ok", false),
                "tool_id",
                toolId,
                "latency_ms",
                latency,
                "result",
                result.get("result"),
                "result_preview",
                result.getOrDefault("result_preview", ""));
    }

    private Map<String, Object> testLocalTool(
            String toolId, Map<String, Object> payload, Instant started) {
        ToolSpec spec = toolRegistry.find(toolId).orElse(null);
        if (spec == null) {
            return map("ok", false, "tool_id", toolId, "error", "Tool not found: " + toolId);
        }
        if (!"python".equals(spec.type())) {
            return map(
                    "ok",
                    false,
                    "tool_id",
                    toolId,
                    "error",
                    "Only MCP and Python tools can be tested by this endpoint.");
        }
        try {
            Path scriptPath = resolveWorkspacePath(spec.scriptPath());
            PythonScriptTool.ExecutionResult execution =
                    PythonScriptTool.execute(
                            pythonCommand(),
                            scriptPath,
                            toolId,
                            objectMap(payload == null ? null : payload.get("arguments")),
                            Duration.ofMillis(spec.timeoutMs()));
            return pythonExecutionResponse(
                    toolId, Duration.between(started, Instant.now()).toMillis(), execution, null);
        } catch (Exception e) {
            return map(
                    "ok",
                    false,
                    "tool_id",
                    toolId,
                    "stage",
                    "exception",
                    "error",
                    e.getMessage(),
                    "latency_ms",
                    Duration.between(started, Instant.now()).toMillis());
        }
    }

    @GetMapping("/tools/{toolId}/schema-snapshots")
    public Map<String, Object> schemaSnapshots(
            @PathVariable("toolId") String toolId, ServerHttpRequest request) {
        assetAccess.requireReadable("TOOL", toolId, requirePrincipal(request));
        return map("items", state.toolSchemaSnapshots(toolId));
    }

    @GetMapping("/tools/audit")
    public Map<String, Object> toolAudit(ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return map("items", state.audit());
    }

    @GetMapping("/mcp")
    public Map<String, Object> mcps(ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        List<Map<String, Object>> rows =
                new ArrayList<>(assetAccess.filterRows("MCP", state.mcpServers(), "id", current));
        userCapabilities.mcps(current).stream()
                .map(spec -> userCapabilities.enrichMcpRow(spec, current))
                .forEach(rows::add);
        return map("items", rows, "mcp_servers", rows);
    }

    @PostMapping("/mcp")
    public Map<String, Object> createMcp(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        String transport = string(payload.get("transport"), "streamable-http");
        if (!"stdio".equalsIgnoreCase(transport)) {
            Map<String, Object> personal = userCapabilities.createMcp(payload, current);
            return map("item", personal, "server", personal);
        }
        auth.requireAdmin(current);
        Map<String, Object> server = state.upsertMcpServer(null, payload);
        assetAccess.ensurePublic("MCP", String.valueOf(server.get("id")));
        return map("item", server, "server", server);
    }

    @PatchMapping("/mcp/{id}")
    public Map<String, Object> patchMcp(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (userCapabilities.findMcp(id, current).isPresent()) {
            Map<String, Object> personal = userCapabilities.updateMcp(id, payload, current);
            return map("item", personal, "server", personal);
        }
        auth.requireAdmin(current);
        assetAccess.requireReadable("MCP", id, current);
        Map<String, Object> server = state.upsertMcpServer(id, payload);
        return map("item", server, "server", server);
    }

    @DeleteMapping("/mcp/{id}")
    public Map<String, Object> deleteMcp(
            @PathVariable("id") String id, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (userCapabilities.findMcp(id, current).isPresent()) {
            userCapabilities.delete("MCP", id, current);
            return map("ok", true, "id", id);
        }
        auth.requireAdmin(current);
        assetAccess.requireReadable("MCP", id, current);
        state.deleteMcpServer(id);
        return map("ok", true, "id", id);
    }

    @PostMapping("/mcp/probe")
    public Map<String, Object> probeMcp(
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        Map<String, Object> probe =
                mcpToolDiscoveryService.probe(payload == null ? Map.of() : payload);
        return map("probe", probe);
    }

    @PostMapping("/mcp/{id}/probe")
    public Map<String, Object> probeMcpById(
            @PathVariable("id") String id, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        Map<String, Object> personal = null;
        if (userCapabilities.findMcp(id, current).isPresent()) {
            personal = userCapabilities.mcpRuntimeRow(id, current);
        } else {
            auth.requireAdmin(current);
            assetAccess.requireReadable("MCP", id, current);
        }
        Map<String, Object> server =
                personal != null
                        ? personal
                        : state.mcpServers().stream()
                        .filter(row -> id.equals(String.valueOf(row.get("id"))))
                        .findFirst()
                        .orElse(null);
        if (server == null) {
            return state.updateMcpProbe(
                    id,
                    map("ok", false, "stage", "lookup", "error", "MCP server not found: " + id));
        }
        return state.updateMcpProbe(id, mcpToolDiscoveryService.probe(server));
    }

    @GetMapping("/mcp/{id}/tools")
    public Map<String, Object> mcpTools(
            @PathVariable("id") String id, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        Map<String, Object> personal =
                userCapabilities.findMcp(id, current).isPresent()
                        ? userCapabilities.mcpRuntimeRow(id, current)
                        : null;
        if (personal == null) assetAccess.requireReadable("MCP", id, current);
        Map<String, Object> server =
                personal != null
                        ? personal
                        : state.mcpServers().stream()
                        .filter(row -> id.equals(String.valueOf(row.get("id"))))
                        .findFirst()
                        .orElseGet(() -> map("id", id, "name", "mcp-" + id));
        List<Map<String, Object>> rows = state.mcpTools(server);
        rows = state.enrichTools(rows);
        return map("items", rows, "tools", rows);
    }

    @GetMapping("/mcp/{id}/agents")
    public Map<String, Object> mcpBoundAgents(
            @PathVariable("id") String id, ServerHttpRequest request) {
        assetAccess.requireReadable("MCP", id, requirePrincipal(request));
        List<Map<String, Object>> rows = state.mcpBoundAgents(id);
        return map("items", rows, "agents", rows);
    }

    @GetMapping("/skills")
    public Map<String, Object> skills(
            @RequestParam(name = "domain", required = false) String domain,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        List<Map<String, Object>> rows =
                new ArrayList<>(assetAccess.filterRows("SKILL", state.skills(), "skill_id", current));
        userCapabilities.skills(current).stream()
                .map(skill -> userCapabilities.enrichSkillRow(skill, current))
                .forEach(rows::add);
        return map("items", rows, "skills", rows);
    }

    @PostMapping("/skills")
    public Map<String, Object> createSkill(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (payload.containsKey("content")) {
            Map<String, Object> personal = userCapabilities.createSkill(payload, current);
            return map("ok", true, "skill", personal, "skill_id", personal.get("skill_id"));
        }
        auth.requireAdmin(current);
        Map<String, Object> skill = state.upsertSkill(payload);
        assetAccess.ensurePublic("SKILL", String.valueOf(skill.get("skill_id")));
        return map("ok", true, "skill", skill, "skill_id", skill.get("skill_id"));
    }

    @PutMapping("/skills/{skillId}")
    public Map<String, Object> updateSkill(
            @PathVariable("skillId") String skillId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (userCapabilities.findSkill(skillId, current).isPresent()) {
            String path = string(payload.get("path"), "SKILL.md");
            return map(
                    "ok",
                    true,
                    "skill",
                    userCapabilities.updateSkillFile(skillId, path, payload, current));
        }
        auth.requireAdmin(current);
        assetAccess.requireReadable("SKILL", skillId, current);
        payload = new LinkedHashMap<>(payload);
        payload.put("skill_id", skillId);
        Map<String, Object> skill = state.upsertSkill(payload);
        return map("ok", true, "skill", skill, "skill_id", skill.get("skill_id"));
    }

    @PostMapping("/skills/sync")
    public Map<String, Object> syncSkills(ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return map("synced", state.skills());
    }

    @PostMapping("/skills/{skillId}/enable")
    public Map<String, Object> enableSkill(
            @PathVariable("skillId") String skillId, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        assetAccess.requireReadable("SKILL", skillId, requirePrincipal(request));
        Map<String, Object> skill = state.setSkillEnabled(skillId, true);
        return map("ok", true, "skill_id", skillId, "enabled", true, "skill", skill);
    }

    @PostMapping("/skills/{skillId}/disable")
    public Map<String, Object> disableSkill(
            @PathVariable("skillId") String skillId, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        assetAccess.requireReadable("SKILL", skillId, requirePrincipal(request));
        Map<String, Object> skill = state.setSkillEnabled(skillId, false);
        return map("ok", true, "skill_id", skillId, "enabled", false, "skill", skill);
    }

    @PostMapping("/skills/{skillId}/test")
    public Map<String, Object> testSkill(
            @PathVariable("skillId") String skillId, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        assetAccess.requireReadable("SKILL", skillId, requirePrincipal(request));
        return state.testSkill(skillId);
    }

    @GetMapping("/skills/{skillId}")
    public Map<String, Object> skillDetail(
            @PathVariable("skillId") String skillId, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (userCapabilities.findSkill(skillId, current).isPresent()) {
            return userCapabilities.skillDetail(skillId, current);
        }
        assetAccess.requireReadable("SKILL", skillId, current);
        return state.skillDetail(skillId);
    }

    @GetMapping("/skills/{skillId}/files")
    public Map<String, Object> skillFile(
            @PathVariable("skillId") String skillId,
            @RequestParam("path") String path,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (userCapabilities.findSkill(skillId, current).isPresent()) {
            return userCapabilities.skillFile(skillId, path, current);
        }
        assetAccess.requireReadable("SKILL", skillId, current);
        return state.skillFileContent(skillId, path);
    }

    @PutMapping("/skills/{skillId}/files")
    public Map<String, Object> updateSkillFile(
            @PathVariable("skillId") String skillId,
            @RequestParam("path") String path,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (userCapabilities.findSkill(skillId, current).isPresent()) {
            return map("ok", true, "skill", userCapabilities.updateSkillFile(skillId, path, payload, current));
        }
        auth.requireAdmin(current);
        assetAccess.requireReadable("SKILL", skillId, current);
        return map("ok", true, "skill", state.updateSkillFile(skillId, path, payload));
    }

    @DeleteMapping("/skills/{skillId}")
    public Map<String, Object> deleteSkill(
            @PathVariable("skillId") String skillId, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        if (userCapabilities.findSkill(skillId, current).isPresent()) {
            userCapabilities.delete("SKILL", skillId, current);
            return map("ok", true, "skill_id", skillId);
        }
        auth.requireAdmin(current);
        assetAccess.requireReadable("SKILL", skillId, current);
        state.deleteSkill(skillId);
        return map("ok", true, "skill_id", skillId);
    }

    @GetMapping("/skills/packages")
    public Map<String, Object> packages(
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "status", required = false) String status,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return map("items", state.skillPackages(domain, status));
    }

    @PostMapping(value = "/skills/packages/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadSkillPackage(
            @RequestPart("file") FilePart file,
            @RequestParam(name = "domain", defaultValue = "platform") String domain,
            @RequestParam(required = false, name = "skill_id") String skillId,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam(required = false, name = "source_note") String sourceNote,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return Mono.fromCallable(() -> Files.createTempFile("skill-package-", ".zip"))
                .flatMap(
                        temp ->
                                file.transferTo(temp)
                                        .then(
                                                Mono.fromCallable(
                                                        () ->
                                                                state.uploadSkillPackage(
                                                                        temp,
                                                                        file.filename(),
                                                                        domain,
                                                                        packageMetadata(
                                                                                skillId,
                                                                                name,
                                                                                version,
                                                                                description,
                                                                                sourceNote))))
                                        .doFinally(
                                                signal -> {
                                                    try {
                                                        Files.deleteIfExists(temp);
                                                    } catch (Exception ignored) {
                                                        // best effort temp cleanup
                                                    }
                                                }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(
            value = "/skills/packages/upload-folder",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadSkillPackageFolder(
            @RequestPart("files") Flux<FilePart> files,
            @RequestPart("manifest") FormFieldPart manifest,
            @RequestParam(name = "domain", defaultValue = "platform") String domain,
            @RequestParam(required = false, name = "skill_id") String skillId,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam(required = false, name = "source_note") String sourceNote,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return files.collectList()
                .flatMap(
                        parts ->
                                Mono.fromCallable(() -> Files.createTempDirectory("skill-folder-"))
                                        .flatMap(
                                                tempDir ->
                                                        writeSkillFolderUpload(
                                                                        parts,
                                                                        manifest.value(),
                                                                        tempDir)
                                                                .then(
                                                                        Mono.fromCallable(
                                                                                () ->
                                                                                        state
                                                                                                .uploadSkillPackageDirectory(
                                                                                                        tempDir,
                                                                                                        "skill-folder",
                                                                                                        domain,
                                                                                                        packageMetadata(
                                                                                                                skillId,
                                                                                                                name,
                                                                                                                version,
                                                                                                                description,
                                                                                                                sourceNote))))
                                                                .doFinally(
                                                                        signal ->
                                                                                deleteRecursively(
                                                                                        tempDir))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/skills/packages/{id}/publish")
    public Map<String, Object> publishPackage(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        List<String> permissions =
                payload == null || !(payload.get("permissions") instanceof List<?> list)
                        ? null
                        : list.stream().map(String::valueOf).toList();
        return state.publishSkillPackage(id, permissions);
    }

    @GetMapping("/skills/packages/{id}/preview")
    public Map<String, Object> previewPackage(
            @PathVariable("id") String id, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return state.previewSkillPackage(id);
    }

    @GetMapping("/skills/packages/{id}/preview-file")
    public Map<String, Object> previewPackageFile(
            @PathVariable("id") String id,
            @RequestParam("path") String path,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return state.previewSkillPackageFile(id, path);
    }

    @PostMapping("/skills/packages/{id}/reject")
    public Map<String, Object> rejectPackage(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        return state.rejectSkillPackage(
                id, string(payload == null ? null : payload.get("reason"), ""));
    }

    @PatchMapping("/skills/packages/{id}/permissions")
    public Map<String, Object> packagePermissions(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        List<String> permissions =
                payload == null || !(payload.get("permissions") instanceof List<?> list)
                        ? null
                        : list.stream().map(String::valueOf).toList();
        return state.updateSkillPackagePermissions(id, permissions);
    }

    @DeleteMapping("/skills/packages/{id}")
    public Map<String, Object> deletePackage(
            @PathVariable("id") String id, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        state.deleteSkillPackage(id);
        return map("id", id, "ok", true);
    }

    @GetMapping("/models/schema")
    public Map<String, Object> modelSchema() {
        return map(
                "provider_types",
                List.of("openai-compatible", "http_chat", "ollama", "dashscope"),
                "model_kinds",
                List.of("chat", "embedding", "rerank"),
                "provider_call_types",
                List.of("generate", "embed", "rerank"),
                "model_capabilities",
                List.of(
                        map("key", "vision", "label", "视觉理解", "description", "可读取图片、OCR 和图表"),
                        map("key", "tool_calling", "label", "工具调用", "description", "支持原生函数/工具调用"),
                        map(
                                "key",
                                "json_schema",
                                "label",
                                "结构化输出",
                                "description",
                                "支持 JSON Schema 输出"),
                        map("key", "reasoning", "label", "推理", "description", "支持推理模式或思考预算"),
                        map("key", "streaming", "label", "流式输出", "description", "支持 token 流式返回"),
                        map("key", "audio_input", "label", "音频输入", "description", "可理解音频"),
                        map("key", "video_input", "label", "视频输入", "description", "可理解视频")),
                "statuses",
                List.of("active", "disabled"));
    }

    @GetMapping("/models/providers")
    public Map<String, Object> providers() {
        return map("providers", state.providers());
    }

    @PostMapping("/models/providers")
    public Map<String, Object> createProvider(@RequestBody Map<String, Object> payload) {
        Map<String, Object> provider = state.providerUpsert(null, payload);
        return map("provider", provider);
    }

    @PatchMapping("/models/providers/{id}")
    public Map<String, Object> patchProvider(
            @PathVariable("id") String id, @RequestBody Map<String, Object> payload) {
        Map<String, Object> provider = state.providerUpsert(id, payload);
        return map("provider", provider);
    }

    @DeleteMapping("/models/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable("id") String id) {
        state.deleteProvider(id);
        return map("ok", true, "provider_id", id);
    }

    @PostMapping("/models/providers/{id}/ping")
    public Map<String, Object> pingProvider(@PathVariable("id") String id) {
        return map("ok", true, "provider_id", id, "duration_ms", 1);
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return map("models", state.modelRows());
    }

    @PostMapping("/models")
    public Map<String, Object> createModel(@RequestBody Map<String, Object> payload) {
        Map<String, Object> model = state.modelUpsert(null, payload);
        return map("model", model);
    }

    @PatchMapping("/models/{id}")
    public Map<String, Object> patchModel(
            @PathVariable("id") String id, @RequestBody Map<String, Object> payload) {
        Map<String, Object> model = state.modelUpsert(id, payload);
        return map("model", model);
    }

    @DeleteMapping("/models/{id}")
    public Map<String, Object> deleteModel(@PathVariable("id") String id) {
        state.deleteModel(id);
        return map("ok", true, "model_id", id);
    }

    @PostMapping("/models/{id}/ping")
    public Map<String, Object> pingModel(@PathVariable("id") String id) {
        return map("ok", true, "model_id", id, "duration_ms", 1);
    }

    @PostMapping("/models/{id}/test")
    public Mono<Map<String, Object>> testModel(
            @PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> model = state.modelRow(id);
        if (model.isEmpty()) {
            return Mono.just(map("ok", false, "model_id", id, "error", "model not found"));
        }
        Map<String, Object> provider =
                state.provider(string(model.get("provider_id"), "openai-compatible"));
        String callType = string(model.get("provider_call_type"), "generate");
        String modelKind = string(model.get("model_kind"), string(model.get("kind"), "chat"));
        String providerType = string(provider.get("provider_type"), "openai");
        if ("echo".equals(providerType)) {
            String prompt = string(payload == null ? null : payload.get("prompt"), "ping");
            return Mono.just(
                    map(
                            "ok",
                            true,
                            "model_id",
                            id,
                            "provider_id",
                            model.get("provider_id"),
                            "answer",
                            "无真实外部请求: " + prompt,
                            "result",
                            map("text", "无真实外部请求: " + prompt)));
        }
        if ("embedding".equals(modelKind) || "embed".equals(callType)) {
            String input =
                    firstText(
                            payload == null ? null : payload.get("input"),
                            payload == null ? null : payload.get("prompt"));
            if (input.isBlank()) {
                input = "ping";
            }
            String finalInput = input;
            long started = System.nanoTime();
            return Mono.fromCallable(
                            () -> {
                                double[] vector =
                                        embeddingModelRegistry
                                                .resolveEmbeddingModel(id)
                                                .embed(TextBlock.builder().text(finalInput).build())
                                                .block();
                                int dimensions = vector == null ? 0 : vector.length;
                                return map(
                                        "ok",
                                        true,
                                        "model_id",
                                        id,
                                        "provider_id",
                                        model.get("provider_id"),
                                        "model_name",
                                        string(model.get("model_name"), id),
                                        "duration_ms",
                                        (System.nanoTime() - started) / 1_000_000,
                                        "result",
                                        map(
                                                "dimensions",
                                                dimensions,
                                                "sample",
                                                sampleVector(vector)));
                            })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(
                            e ->
                                    Mono.just(
                                            map(
                                                    "ok",
                                                    false,
                                                    "model_id",
                                                    id,
                                                    "error",
                                                    e.getMessage())));
        }
        if (!"generate".equals(callType)) {
            return Mono.just(
                    map("ok", false, "model_id", id, "error", "当前只实现 chat/generate 模型真实测试"));
        }

        String baseUrl = firstText(model.get("base_url"), provider.get("default_base_url"));
        String endpointPath =
                firstText(
                        provider.get("endpoint_path"), defaultEndpointPath(providerType, callType));
        String apiKey =
                resolveSecret(firstText(model.get("secret_ref"), provider.get("secret_ref")));
        String modelName = string(model.get("model_name"), id);
        String prompt = string(payload == null ? null : payload.get("prompt"), "ping");
        List<Map<String, Object>> images =
                testImages(payload == null ? null : payload.get("images"));
        if (baseUrl.isBlank()) {
            return Mono.just(map("ok", false, "model_id", id, "error", "base_url is required"));
        }
        if (apiKey.isBlank() && !"http_chat".equals(providerType)) {
            return Mono.just(
                    map("ok", false, "model_id", id, "error", "secret_ref/api key is required"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", List.of(testUserMessage(providerType, prompt, images)));
        body.put("prompt", prompt);
        body.put("query", prompt);
        body.put("input", prompt);
        body.put("stream", false);
        body.put("thinking", map("type", "disabled"));
        body.putAll(objectMap(model.get("extra_body")));
        if (payload != null) {
            Integer maxTokens = integer(payload.get("max_tokens"));
            if (maxTokens != null) {
                body.put("max_tokens", maxTokens);
            }
        }

        long started = System.nanoTime();
        WebClient.RequestBodySpec request =
                webClient
                        .post()
                        .uri(resolveRequestUrl(baseUrl, endpointPath, providerType))
                        .contentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isBlank()) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        objectMap(model.get("extra_headers"))
                .forEach(
                        (key, value) -> {
                            if (value != null && !String.valueOf(key).isBlank()) {
                                request.header(String.valueOf(key), String.valueOf(value));
                            }
                        });
        return request.bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(
                        raw -> {
                            Map<String, Object> response = objectMap(raw);
                            String text = extractOpenAiText(response);
                            return map(
                                    "ok",
                                    true,
                                    "model_id",
                                    id,
                                    "provider_id",
                                    model.get("provider_id"),
                                    "model_name",
                                    modelName,
                                    "duration_ms",
                                    (System.nanoTime() - started) / 1_000_000,
                                    "image_count",
                                    images.size(),
                                    "answer",
                                    text,
                                    "result",
                                    map("text", text, "raw", response));
                        })
                .onErrorResume(
                        e -> Mono.just(map("ok", false, "model_id", id, "error", e.getMessage())));
    }

    private static Map<String, Object> testUserMessage(
            String providerType, String prompt, List<Map<String, Object>> images) {
        if (images.isEmpty()) {
            return map("role", "user", "content", prompt);
        }
        if ("ollama".equals(providerType)) {
            return map(
                    "role",
                    "user",
                    "content",
                    prompt,
                    "images",
                    images.stream()
                            .filter(image -> !string(image.get("data"), "").isBlank())
                            .map(image -> string(image.get("data"), ""))
                            .toList());
        }
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(map("type", "text", "text", prompt));
        for (Map<String, Object> image : images) {
            String url = string(image.get("url"), "");
            if (url.isBlank()) {
                String data = string(image.get("data"), "");
                String mediaType = string(image.get("media_type"), "image/jpeg");
                url = "data:" + mediaType + ";base64," + data;
            }
            content.add(map("type", "image_url", "image_url", map("url", url)));
        }
        return map("role", "user", "content", content);
    }

    private static List<Map<String, Object>> testImages(Object payload) {
        if (!(payload instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> images = new ArrayList<>();
        for (Object item : rows) {
            Map<String, Object> image = objectMap(item);
            String url = string(image.get("url"), "");
            String data = string(image.get("data"), "");
            String mediaType =
                    firstText(image.get("media_type"), image.get("mediaType"), "image/jpeg");
            if (url.isBlank() == data.isBlank() || !mediaType.startsWith("image/")) {
                throw new IllegalArgumentException("Each test image needs one URL or Base64 data");
            }
            images.add(map("url", url, "data", data, "media_type", mediaType));
        }
        return List.copyOf(images);
    }

    @GetMapping("/models/slots")
    public Map<String, Object> slots() {
        return map("slots", state.slots());
    }

    @GetMapping("/models/slots/bindings")
    public Map<String, Object> slotBindings() {
        return map("bindings", state.slotBindings());
    }

    @PutMapping("/models/slots/{slotKey}/platform/_")
    public Map<String, Object> bindSlot(
            @PathVariable("slotKey") String slotKey, @RequestBody Map<String, Object> payload) {
        return map("binding", state.bindSlot(slotKey, payload));
    }

    @DeleteMapping("/models/slots/{slotKey}/platform/_")
    public Map<String, Object> clearSlot(@PathVariable("slotKey") String slotKey) {
        state.clearSlot(slotKey);
        return map("ok", true, "slot_key", slotKey);
    }

    @GetMapping("/models/aliases")
    public Map<String, Object> aliases() {
        return map("aliases", state.aliases());
    }

    @PostMapping("/models/aliases")
    public Map<String, Object> createAlias(@RequestBody Map<String, Object> payload) {
        return map("alias", state.alias(payload));
    }

    @DeleteMapping("/models/aliases/{id}")
    public Map<String, Object> deleteAlias(@PathVariable("id") String id) {
        state.deleteAlias(id);
        return map("ok", true, "id", id);
    }

    @GetMapping("/models/aliases/available")
    public Map<String, Object> aliasesAvailable() {
        return map(
                "fixed_slots",
                state.slots(),
                "aliases",
                state.aliases(),
                "models",
                state.modelRows());
    }

    @GetMapping("/models/resolve")
    public Map<String, Object> resolveModel(@RequestParam(name = "slot", defaultValue = "qa") String slot) {
        Map<String, Object> binding =
                state.slotBindings().stream()
                        .filter(row -> slot.equals(row.get("slot_key")))
                        .findFirst()
                        .orElse(Map.of());
        return map(
                "resolved",
                map("slot_key", slot, "scope", "platform", "model_id", binding.get("model_id")));
    }

    @GetMapping("/models/audit")
    public Map<String, Object> modelAudit() {
        return map("events", state.audit());
    }

    @GetMapping("/chat/sessions")
    public Map<String, Object> sessions(
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "agent_id", required = false) String agent_id,
            ServerHttpRequest request) {
        var current = requirePrincipal(request);
        List<Map<String, Object>> rows =
                state.sessions(domain, agent_id, current.orgId(), current.userId());
        return map("items", rows, "sessions", rows);
    }

    @PostMapping("/chat/sessions")
    public Map<String, Object> createSession(
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        var current = requirePrincipal(request);
        Map<String, Object> owned = new LinkedHashMap<>(payload);
        owned.put("user_id", current.userId());
        return state.newSession(owned, current.orgId(), current.userId());
    }

    @GetMapping("/chat/sessions/{id}")
    public Map<String, Object> session(
            @PathVariable("id") String id,
            @RequestParam(name = "agent_id", required = false) String agent_id,
            ServerHttpRequest request) {
        var current = requirePrincipal(request);
        return state.session(id, agent_id, current.orgId(), current.userId());
    }

    @DeleteMapping("/chat/sessions/{id}")
    public Map<String, Object> deleteSession(
            @PathVariable("id") String id,
            @RequestParam(name = "agent_id", required = false) String agent_id,
            ServerHttpRequest request) {
        var current = requirePrincipal(request);
        state.deleteSession(id, agent_id, current.orgId(), current.userId());
        return map("ok", true, "session_id", id);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chatStream(
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        String userId = current.userId();
        Instant requestStartedAt = Instant.now();
        String query = string(payload.get("query"), "");
        String sessionId = string(payload.get("session_id"), "default");
        String domain = string(payload.get("domain"), "platform");
        String agentId = string(payload.get("agent_id"), "platform_knowledge_agent");
        agentAssetService.requireReadable(agentId, current);
        Instant retrievalStartedAt = Instant.now();
        DocumentKnowledgeService.Retrieval retrieval =
                documentKnowledgeService.retrieveScoped(
                        query,
                        requestedDocumentScope(payload),
                        number(payload.get("top_k"), 4),
                        current);
        Instant retrievalFinishedAt = Instant.now();
        long retrievalDurationMs =
                Duration.between(retrievalStartedAt, retrievalFinishedAt).toMillis();
        String runtimeQuery = DocumentKnowledgeService.withContext(query, retrieval);
        Map<String, Object> run = state.createRun(agentId, query, userId);
        String runId = string(run.get("run_id"), "");
        state.appendSessionMessage(agentId, sessionId, userId, "user", query);
        AtomicReference<StringBuilder> answer = new AtomicReference<>(new StringBuilder());
        AtomicReference<Instant> agentStartedAt = new AtomicReference<>();
        AtomicReference<Instant> firstOutputAt = new AtomicReference<>();
        Flux<ServerSentEvent<Map<String, Object>>> received =
                Flux.just(
                        sse(
                                "activity",
                                map(
                                        "type",
                                        "activity",
                                        "id",
                                        "receive",
                                        "step",
                                        "receive",
                                        "title",
                                        "接收问题",
                                        "status",
                                        "success",
                                        "run_id",
                                        runId)));
        Flux<ServerSentEvent<Map<String, Object>>> retrieved =
                Flux.just(
                        sse(
                                "activity",
                                map(
                                        "type",
                                        "activity",
                                        "id",
                                        "knowledge_retrieval",
                                        "step",
                                        "knowledge_retrieval",
                                        "title",
                                        "检索文档知识库",
                                        "status",
                                        "success",
                                        "summary",
                                        "命中 " + retrieval.citations().size() + " 个文档片段",
                                        "citations",
                                        retrieval.citations(),
                                        "duration_ms",
                                        retrievalDurationMs,
                                        "started_at",
                                        retrievalStartedAt.toString(),
                                        "finished_at",
                                        retrievalFinishedAt.toString(),
                                        "run_id",
                                        runId)));
        Flux<ServerSentEvent<Map<String, Object>>> events =
                runtime.stream(agentId, new ChatRequest(domain, userId, sessionId, runtimeQuery))
                        .filter(PlatformFrontendCompatibilityController::visibleStreamEvent)
                        .doOnSubscribe(ignored -> agentStartedAt.set(Instant.now()))
                        .doOnNext(
                                event -> {
                                    if (event.delta() != null && !event.delta().isEmpty()) {
                                        firstOutputAt.compareAndSet(null, Instant.now());
                                    }
                                })
                        .map(
                                event -> {
                                    state.appendRunEventFromEnvelope(runId, event);
                                    return frontendStreamEvent(event, answer.get(), runId);
                                });
        Flux<ServerSentEvent<Map<String, Object>>> agentStarted =
                Flux.just(
                        sse(
                                "activity",
                                map(
                                        "type",
                                        "activity",
                                        "id",
                                        "agent_execution",
                                        "step",
                                        "agent_execution",
                                        "title",
                                        "Agent 执行与生成",
                                        "status",
                                        "running",
                                        "summary",
                                        "正在等待模型、工具或工作流输出",
                                        "run_id",
                                        runId)));
        Mono<ServerSentEvent<Map<String, Object>>> agentTiming =
                Mono.fromSupplier(
                        () -> {
                            Instant started = agentStartedAt.get();
                            Instant finished = Instant.now();
                            long executionDurationMs =
                                    started == null
                                            ? 0
                                            : Duration.between(started, finished).toMillis();
                            Instant firstOutput = firstOutputAt.get();
                            Long firstOutputMs =
                                    firstOutput == null || started == null
                                            ? null
                                            : Duration.between(started, firstOutput).toMillis();
                            return sse(
                                    "activity",
                                    map(
                                            "type",
                                            "activity",
                                            "id",
                                            "agent_execution",
                                            "step",
                                            "agent_execution",
                                            "title",
                                            "Agent 执行与生成",
                                            "status",
                                            "success",
                                            "summary",
                                            firstOutputMs == null
                                                    ? "本轮未产生文本输出"
                                                    : "首字响应 "
                                                            + firstOutputMs
                                                            + "ms，输出 "
                                                            + answer.get().length()
                                                            + " 字符",
                                            "duration_ms",
                                            executionDurationMs,
                                            "started_at",
                                            started == null ? null : started.toString(),
                                            "finished_at",
                                            finished.toString(),
                                            "detail",
                                            map(
                                                    "first_output_ms",
                                                    firstOutputMs,
                                                    "output_chars",
                                                    answer.get().length()),
                                            "run_id",
                                            runId));
                        });
        Mono<ServerSentEvent<Map<String, Object>>> totalTiming =
                Mono.fromSupplier(
                        () -> {
                            Instant finished = Instant.now();
                            return sse(
                                    "activity",
                                    map(
                                            "type",
                                            "activity",
                                            "id",
                                            "turn_total",
                                            "step",
                                            "turn_total",
                                            "title",
                                            "本轮总耗时",
                                            "status",
                                            "success",
                                            "summary",
                                            "从接收问题到生成完成",
                                            "duration_ms",
                                            Duration.between(requestStartedAt, finished).toMillis(),
                                            "started_at",
                                            requestStartedAt.toString(),
                                            "finished_at",
                                            finished.toString(),
                                            "run_id",
                                            runId));
                        });
        Mono<ServerSentEvent<Map<String, Object>>> done =
                Mono.fromSupplier(
                        () -> {
                            String text = answer.get().toString();
                            Map<String, Object> finished = state.finishRun(runId, text);
                            state.appendSessionMessage(
                                    agentId, sessionId, userId, "assistant", text);
                            return sse(
                                    "done",
                                    map(
                                            "type",
                                            "done",
                                            "run_id",
                                            finished.get("run_id"),
                                            "status",
                                            "succeeded",
                                            "trace_id",
                                            finished.get("trace_id"),
                                            "output_ref",
                                            finished.get("output_ref"),
                                            "result",
                                            map(
                                                    "answer",
                                                    text,
                                                    "text",
                                                    text,
                                                    "citations",
                                                    retrieval.citations())));
                        });
        return Flux.concat(
                        received, retrieved, agentStarted, events, agentTiming, totalTiming, done)
                .onErrorResume(
                        error -> {
                            Map<String, Object> failed = state.failRun(runId, error);
                            String message = string(error.getMessage(), "chat stream error");
                            return Flux.just(
                                    sse(
                                            "error",
                                            map(
                                                    "type",
                                                    "error",
                                                    "run_id",
                                                    failed.get("run_id"),
                                                    "status",
                                                    "failed",
                                                    "message",
                                                    message,
                                                    "error",
                                                    message)));
                        });
    }

    @GetMapping("/live-context/turns/{traceId}")
    public Map<String, Object> turnContext(@PathVariable("traceId") String traceId) {
        return map(
                "trace_id",
                traceId,
                "session_id",
                "compat",
                "domain",
                "platform",
                "activity",
                List.of(),
                "resources",
                emptyResources(),
                "memory_usage",
                map(
                        "session",
                        Map.of(),
                        "long_term",
                        List.of(),
                        "episodic",
                        List.of(),
                        "written",
                        map(
                                "session_messages",
                                List.of(),
                                "summary",
                                null,
                                "long_term_memory_ids",
                                List.of(),
                                "episodic_ids",
                                List.of())));
    }

    @GetMapping("/live-context/sessions/{sessionId}/resources")
    public Map<String, Object> sessionResources(@PathVariable("sessionId") String sessionId) {
        return emptyResources();
    }

    @PostMapping("/live-context/memory/{id}/feedback")
    public Map<String, Object> memoryFeedback(
            @PathVariable("id") long id, @RequestBody Map<String, Object> payload) {
        return map("ok", true, "id", id, "action", payload.get("action"));
    }

    @GetMapping("/knowledge/docs")
    public Map<String, Object> docs(
            @RequestParam(name = "domain", required = false) String domain,
            ServerHttpRequest request) {
        List<Map<String, Object>> documents =
                documentKnowledgeService.documents(domain, requirePrincipal(request));
        return map("items", documents, "documents", documents);
    }

    @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadDoc(
            @RequestPart("file") FilePart file,
            @RequestParam(name = "domain", required = false) String queryDomain,
            @RequestParam(value = "collection_id", required = false) String queryCollectionId,
            @RequestPart(value = "domain", required = false) String formDomain,
            @RequestPart(value = "collection_id", required = false) String formCollectionId,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        String orgId = current.orgId();
        if (!documentKnowledgeService.supports(file.filename())) {
            return Mono.error(
                    new IllegalArgumentException(
                        "仅支持 PDF、Office、Markdown、TXT、CSV（pdf/doc/docx/xls/xlsx/ppt/pptx/md/txt/csv）。"));
        }
        String domain = firstText(formDomain, queryDomain, "platform");
        String collectionId = firstText(formCollectionId, queryCollectionId);
        String docId = documentKnowledgeService.newDocumentId();
        Path target = documentKnowledgeService.uploadTarget(docId, file.filename());
        return file.transferTo(target)
                .then(
                        Mono.fromCallable(
                                () -> {
                                    Map<String, Object> document =
                                            documentKnowledgeService.findReusable(target, current, "");
                                    boolean deduplicated = !document.isEmpty();
                                    if (document.isEmpty()) {
                                        document =
                                                documentKnowledgeService.ingest(
                                                        docId, target, file.filename(), domain, current);
                                    } else {
                                        documentKnowledgeService.discardUpload(docId);
                                    }
                                    if (collectionId != null && !collectionId.isBlank()) {
                                        knowledgeCollectionService.addDocument(
                                                collectionId,
                                                String.valueOf(document.get("doc_id")),
                                                String.valueOf(
                                                        document.getOrDefault("version_id", "v1")),
                                                orgId);
                                    }
                                    document = new LinkedHashMap<>(document);
                                    document.put("deduplicated", deduplicated);
                                    return document;
                                }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/knowledge/collections")
    public Map<String, Object> collections(
            @RequestParam(name = "domain", required = false) String domain,
            ServerHttpRequest request) {
        return map(
                "items",
                knowledgeCollectionService.list(domain, requirePrincipal(request).orgId()));
    }

    @PostMapping("/knowledge/collections")
    public Map<String, Object> createCollection(
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        String orgId = requirePrincipal(request).orgId();
        Map<String, Object> collection = knowledgeCollectionService.create(payload, orgId);
        return map("item", collection, "collection_id", collection.get("collection_id"));
    }

    @DeleteMapping("/knowledge/collections/{id}")
    public Map<String, Object> deleteCollection(
            @PathVariable("id") String id,
            ServerHttpRequest request) {
        String orgId = requirePrincipal(request).orgId();
        knowledgeCollectionService.delete(id, orgId);
        return map("ok", true, "collection_id", id);
    }

    @PostMapping("/knowledge/collections/{id}/items")
    public Map<String, Object> addCollectionItem(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        String orgId = requirePrincipal(request).orgId();
        documentKnowledgeService.document(
                string(payload.get("item_id"), string(payload.get("doc_id"), "")),
                requirePrincipal(request));
        Map<String, Object> collection =
                knowledgeCollectionService.addDocument(
                        id,
                        string(payload.get("item_id"), string(payload.get("doc_id"), "")),
                        string(payload.get("item_version_id"), "v1"),
                        orgId);
        return map("ok", true, "collection_id", id, "item", collection);
    }

    @PostMapping("/knowledge/docs/{docId}/{versionId}/move")
    public Map<String, Object> moveDocument(
            @PathVariable("docId") String docId,
            @PathVariable("versionId") String versionId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        String orgId = requirePrincipal(request).orgId();
        documentKnowledgeService.document(docId, requirePrincipal(request));
        String targetCollectionId = string(payload.get("collection_id"), "");
        Map<String, Object> result =
                knowledgeCollectionService.moveDocument(
                        docId, versionId, targetCollectionId, orgId);
        return map(
                "ok",
                true,
                "doc_id",
                docId,
                "version_id",
                versionId,
                "collection_id",
                targetCollectionId,
                "item",
                result);
    }

    @DeleteMapping("/knowledge/collections/{id}/items/document/{docId}")
    public Map<String, Object> removeCollectionItem(
            @PathVariable("id") String id,
            @PathVariable("docId") String docId,
            @RequestParam(value = "item_version_id", defaultValue = "") String versionId,
            ServerHttpRequest request) {
        String orgId = requirePrincipal(request).orgId();
        documentKnowledgeService.document(docId, requirePrincipal(request));
        Map<String, Object> collection =
                knowledgeCollectionService.removeDocument(id, docId, versionId, orgId);
        return map("ok", true, "collection_id", id, "doc_id", docId, "item", collection);
    }

    @PostMapping("/knowledge/docs/{docId}/{versionId}/reindex")
    public Map<String, Object> reindexDoc(
            @PathVariable("docId") String docId,
            @PathVariable("versionId") String versionId,
            ServerHttpRequest request) {
        Map<String, Object> result =
                documentKnowledgeService.reindex(docId, requirePrincipal(request));
        return map(
                "ok",
                true,
                "bm25",
                map("indexed", result.get("indexed")),
                "doc_id",
                docId,
                "version_id",
                versionId);
    }

    @PostMapping(
            value = "/knowledge/docs/{docId}/{versionId}/replace",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> replaceDoc(
            @PathVariable("docId") String docId,
            @PathVariable("versionId") String versionId,
            @RequestPart("file") FilePart file) {
        return Mono.error(new UnsupportedOperationException("首版暂不覆盖历史版本；请上传为新文档以保留可追溯引用。"));
    }

    @PostMapping("/knowledge/docs/{docId}/{versionId}/preview/ensure")
    public Mono<Map<String, Object>> ensurePreview(
            @PathVariable("docId") String docId,
            @PathVariable("versionId") String versionId,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        return Mono.fromCallable(() -> documentKnowledgeService.preparePreview(docId, current))
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        preview ->
                                map(
                                        "preview_ready",
                                        preview.ready(),
                                        "preview_message",
                                        preview.message(),
                                        "preview_mime_type",
                                        preview.mimeType(),
                                        "preview_url",
                                        "/platform/frontend/knowledge/docs/"
                                                + docId
                                                + "/"
                                                + versionId
                                                + "/preview",
                                        "doc_id",
                                        docId,
                                        "version_id",
                                        versionId));
    }

    @GetMapping(value = "/knowledge/docs/{docId}/{versionId}/preview")
    public Mono<ResponseEntity<Resource>> previewDocument(
            @PathVariable("docId") String docId,
            @PathVariable("versionId") String versionId,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        return Mono.fromCallable(
                        () -> {
                            DocumentKnowledgeService.PreviewFile preview =
                                    documentKnowledgeService.previewFile(docId, current);
                            Resource resource = new FileSystemResource(preview.path());
                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType(preview.mimeType()))
                                    .header(
                                            HttpHeaders.CONTENT_DISPOSITION,
                                            "inline; filename=\""
                                                    + preview.path().getFileName()
                                                    + "\"")
                                    .body(resource);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/knowledge/docs/{docId}/{versionId}")
    public Map<String, Object> deleteDoc(
            @PathVariable("docId") String docId,
            @PathVariable("versionId") String versionId,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        documentKnowledgeService.delete(docId, current);
        knowledgeCollectionService.removeDocumentEverywhere(docId);
        return map("ok", true, "doc_id", docId, "version_id", versionId);
    }

    @GetMapping("/memory/long-term")
    public Map<String, Object> longTermMemory(
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "status", required = false) String status,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        List<Map<String, Object>> rows =
                state.memories(domain, status).stream()
                        .filter(row -> memoryReadable(row, current))
                        .toList();
        return map("items", rows, "count", rows.size());
    }

    @GetMapping("/memory/daily")
    public Map<String, Object> dailyMemory(
            @RequestParam(name = "agent_id", required = false) String agent_id,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        String userId = current.userId();
        String orgId = current.orgId();
        String userKey = orgId + "_" + userId;
        Map<String, Object> overview = state.agentMemoryOverview(agent_id, userKey);
        Object daily = overview.get("daily");
        List<?> rows = daily instanceof List<?> list ? list : List.of();
        return map("item", overview, "items", rows, "count", rows.size());
    }

    @PostMapping("/memory/long-term")
    public Map<String, Object> createMemory(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        Map<String, Object> owned = new LinkedHashMap<>(payload);
        owned.put("user_id", current.userId());
        owned.put("org_id", current.orgId());
        Map<String, Object> item = state.memory(owned);
        return map("id", item.get("id"), "item", item);
    }

    @GetMapping("/memory/long-term/{id}")
    public Map<String, Object> getMemory(
            @PathVariable("id") String id, ServerHttpRequest request) {
        Map<String, Object> item = state.memoryById(id);
        requireMemoryReadable(item, requirePrincipal(request));
        return map("item", item);
    }

    @PatchMapping("/memory/long-term/{id}")
    public Map<String, Object> patchMemory(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        Map<String, Object> existing = state.memoryById(id);
        requireMemoryWritable(existing, current);
        Map<String, Object> owned = new LinkedHashMap<>(payload);
        owned.put("user_id", current.userId());
        owned.put("org_id", current.orgId());
        return map("item", state.updateMemory(id, owned));
    }

    @DeleteMapping("/memory/long-term/{id}")
    public Map<String, Object> deleteMemory(
            @PathVariable("id") String id,
            ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        requireMemoryWritable(state.memoryById(id), current);
        String userId = current.userId();
        String orgId = current.orgId();
        state.deleteMemory(id, orgId + "_" + userId);
        return map("ok", true, "id", id);
    }

    @DeleteMapping("/memory/agent/{agentId}/main-entry")
    public Map<String, Object> deleteAgentMemoryEntry(
            @PathVariable("agentId") String agentId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "platform_admin") String userId,
            @RequestHeader(value = "x-org-id", defaultValue = "platform") String orgId) {
        boolean removed =
                state.deleteAgentWorkspaceMemoryEntry(
                        agentId, orgId + "_" + userId, string(payload.get("content"), ""));
        return map("ok", true, "agent_id", agentId, "removed", removed);
    }

    @PostMapping("/memory/long-term/{id}/confirm")
    public Map<String, Object> confirmMemory(
            @PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> payload) {
        return map("item", state.confirmMemory(id, payload == null ? Map.of() : payload));
    }

    @PostMapping("/memory/long-term/{id}/reject")
    public Map<String, Object> rejectMemory(
            @PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> payload) {
        return map("item", state.rejectMemory(id, payload == null ? Map.of() : payload));
    }

    @PostMapping("/memory/long-term/{id}/merge")
    public Map<String, Object> mergeMemory(
            @PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> payload) {
        return map("item", state.mergeMemory(id, payload == null ? Map.of() : payload));
    }

    @GetMapping("/memory/audit")
    public Map<String, Object> memoryAudit() {
        return map("items", state.audit());
    }

    @GetMapping("/memory/episodic/status")
    public Map<String, Object> episodicStatus() {
        return map("enabled", true, "index_enabled", true, "active_count", 0, "total_count", 0);
    }

    @GetMapping("/memory/maintenance/status")
    public Map<String, Object> maintenanceStatus() {
        return map(
                "long_term_memory_configured",
                true,
                "maintenance",
                map("mode", "compat", "apply_from_live", false));
    }

    @PostMapping({
        "/memory/maintenance/dry-run",
        "/memory/episodic/maintenance",
        "/memory/episodic/rebuild",
        "/memory/episodic/clear"
    })
    public Map<String, Object> memoryOps() {
        return map(
                "scanned",
                0,
                "user_count",
                0,
                "planned_actions",
                0,
                "applied_actions",
                0,
                "indexed",
                0,
                "deleted",
                0,
                "reason_counts",
                Map.of());
    }

    @GetMapping("/runtime-sandbox/runs")
    public Map<String, Object> sandboxRuns() {
        return map("items", List.of());
    }

    @GetMapping("/runtime-sandbox/runs/{id}")
    public Map<String, Object> sandboxRun(@PathVariable("id") String id) {
        return map("sandbox_run_id", id, "status", "unknown");
    }

    @GetMapping("/kg/graph-spaces")
    public Map<String, Object> graphSpaces(@RequestParam(name = "domain", defaultValue = "platform") String domain) {
        List<Map<String, Object>> rows =
                List.of(
                        map(
                                "org_id",
                                "platform",
                                "domain",
                                domain,
                                "graph_key",
                                "default",
                                "display_name",
                                "默认图谱"));
        return map("items", rows, "graph_spaces", rows);
    }

    @PostMapping({"/kg/graph-spaces", "/kg/graph-spaces/update", "/kg/graph-spaces/archive"})
    public Map<String, Object> graphSpaceMutation(
            @RequestBody(required = false) Map<String, Object> payload) {
        return map("ok", true, "item", payload == null ? Map.of() : payload);
    }

    @GetMapping({"/kg/entities", "/kg/facts", "/kg/versions", "/kg/entities/{id}/facts"})
    public Map<String, Object> kgEmpty() {
        return map("items", List.of());
    }

    private static ServerSentEvent<Map<String, Object>> sse(
            String event, Map<String, Object> data) {
        return ServerSentEvent.builder(data).event(event).build();
    }

    private static ServerSentEvent<Map<String, Object>> frontendStreamEvent(
            AgentEventEnvelope event, StringBuilder answer, String runId) {
        String delta = event.delta();
        if (delta != null && !delta.isEmpty()) {
            answer.append(delta);
            return sse(
                    "token",
                    map("type", "token", "delta", delta, "event_id", event.id(), "run_id", runId));
        }
        return sse(
                "activity",
                map(
                        "type",
                        "activity",
                        "id",
                        event.id(),
                        "step",
                        string(event.type(), "agent_event").toLowerCase(),
                        "title",
                        activityTitle(event),
                        "status",
                        activityStatus(event),
                        "summary",
                        activitySummary(event),
                        "run_id",
                        runId,
                        "source",
                        string(event.source(), ""),
                        "detail",
                        event.payload() == null ? Map.of() : event.payload(),
                        "refs",
                        eventRefs(event)));
    }

    private static boolean visibleStreamEvent(AgentEventEnvelope event) {
        if (event == null) {
            return false;
        }
        String delta = event.delta();
        if (delta != null && !delta.isEmpty()) {
            return true;
        }
        String type = string(event.type(), "").toLowerCase().replace('.', '_');
        if (type.equals("capability_loaded")
                || type.startsWith("workflow_")
                || type.equals("router_decision")
                || type.equals("supervisor_start")
                || type.equals("single_agent_start")
                || type.startsWith("tool_")
                || type.startsWith("skill_")) {
            return true;
        }
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        if (payload.containsKey("summary")
                || payload.containsKey("tool_call_name")
                || payload.containsKey("tool_result_text")
                || payload.containsKey("tool_result_data")
                || payload.containsKey("skill_call_name")) {
            return true;
        }
        return false;
    }

    private static String activityTitle(AgentEventEnvelope event) {
        String type = string(event.type(), "agent_event").toLowerCase().replace('.', '_');
        return switch (type) {
            case "workflow_start" -> "Workflow 开始";
            case "workflow_step_start" -> "Workflow 步骤开始";
            case "workflow_step_end" -> "Workflow 步骤完成";
            case "workflow_final_step" -> "Workflow 最终步骤";
            case "capability_loaded" -> "能力挂载";
            case "router_decision" -> "Router 路由决策";
            case "supervisor_start" -> "Supervisor 启动";
            case "single_agent_start" -> "Agent 启动";
            case "agent_start" -> "Agent 开始";
            case "model_call_start" -> "模型调用开始";
            case "text_block_start" -> "文本生成开始";
            case "text_block_end" -> "文本生成完成";
            case "model_call_end" -> "模型调用完成";
            case "agent_result" -> "Agent 输出结果";
            case "agent_end" -> "Agent 完成";
            case "tool_call_start" -> "工具调用开始";
            case "tool_call_delta" -> "工具调用参数";
            case "tool_call_end" -> "工具调用完成";
            case "tool_result_start" -> "工具结果开始";
            case "tool_result_text_delta" -> "工具结果输出";
            case "tool_result_data_delta" -> "工具结果数据";
            case "tool_result_end" -> "工具结果完成";
            case "skill_call_start" -> "Skill 调用开始";
            case "skill_call_end" -> "Skill 调用完成";
            default -> string(event.type(), "AgentScope 事件");
        };
    }

    private static String activityStatus(AgentEventEnvelope event) {
        String type = string(event.type(), "").toLowerCase().replace('.', '_');
        if (type.startsWith("workflow_")
                || type.equals("router_decision")
                || type.equals("supervisor_start")
                || type.equals("single_agent_start")
                || type.equals("capability_loaded")
                || type.equals("agent_result")) {
            return "success";
        }
        if (type.endsWith("_end") || type.endsWith("_done") || type.endsWith("_complete")) {
            return "success";
        }
        if (type.contains("error") || type.contains("fail")) {
            return "failed";
        }
        return "running";
    }

    private static String activitySummary(AgentEventEnvelope event) {
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        if ("capability_loaded".equals(string(event.type(), "").toLowerCase())) {
            String tools = string(payload.get("tool_refs"), "[]");
            String mcps = string(payload.get("mcp_refs"), "[]");
            String skills = string(payload.get("skill_refs"), "[]");
            String memory = string(payload.get("memory_count"), "0");
            String compaction =
                    Boolean.TRUE.equals(payload.get("compaction_enabled"))
                            ? "；上下文压缩启用，触发："
                                    + string(payload.get("compaction_trigger_messages"), "50")
                                    + " 条消息或动态 token"
                            : "；上下文压缩未启用";
            return "工具 "
                    + tools
                    + "；MCP "
                    + mcps
                    + "；Skill "
                    + skills
                    + "；Memory 已加载 "
                    + memory
                    + " 条"
                    + compaction;
        }
        Object summary = event.payload() == null ? null : event.payload().get("summary");
        String text = string(summary, "");
        if (!text.isBlank()) {
            return text;
        }
        String toolCallName = string(payload.get("tool_call_name"), "");
        String toolCallId = string(payload.get("tool_call_id"), "");
        String toolCallState = string(payload.get("tool_call_state"), "");
        String toolResultState = string(payload.get("tool_result_state"), "");
        String toolName = string(payload.get("tool_name"), "");
        String toolId = string(payload.get("tool_id"), "");
        String skillName = string(payload.get("skill_name"), "");
        String skillId = string(payload.get("skill_id"), "");
        String skillCallName = string(payload.get("skill_call_name"), "");
        String toolResultText = string(payload.get("tool_result_text"), "");
        String toolResultData = string(payload.get("tool_result_data"), "");
        String toolResultDataType = string(payload.get("tool_result_data_type"), "text");
        if (!toolCallName.isBlank() || !toolCallId.isBlank()) {
            String callId = toolCallId.isBlank() ? "" : " (" + toolCallId + ")";
            String callState =
                    !toolCallState.isBlank()
                            ? " [" + toolCallState + "]"
                            : !toolResultState.isBlank() ? " [" + toolResultState + "]" : "";
            return "工具调用：" + firstText(toolCallName, toolName, toolId) + callId + callState;
        }
        if (!toolResultText.isBlank()) {
            return "工具结果："
                    + (toolResultText.length() > 120
                            ? toolResultText.substring(0, 120) + "…"
                            : toolResultText);
        }
        if (!toolResultData.isBlank()) {
            String preview =
                    toolResultData.length() > 120
                            ? toolResultData.substring(0, 120) + "…"
                            : toolResultData;
            return "工具结果(" + toolResultDataType + ")：" + preview;
        }
        if (!skillCallName.isBlank()) {
            return "Skill 调用：" + skillCallName;
        }
        if (!toolName.isBlank()) {
            return "工具：" + toolName;
        }
        if (!toolId.isBlank()) {
            return "工具：" + toolId;
        }
        if (!skillName.isBlank()) {
            return "Skill：" + skillName;
        }
        if (!skillId.isBlank()) {
            return "Skill：" + skillId;
        }
        return string(event.source(), "");
    }

    private static Map<String, Object> eventRefs(AgentEventEnvelope event) {
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        Map<String, Object> refs = new LinkedHashMap<>();
        String toolName = string(payload.get("tool_name"), "");
        String toolId = string(payload.get("tool_id"), "");
        String skillName = string(payload.get("skill_name"), "");
        String skillId = string(payload.get("skill_id"), "");
        String replyId = string(payload.get("reply_id"), "");
        String toolCallId = string(payload.get("tool_call_id"), "");
        String toolCallName = string(payload.get("tool_call_name"), "");
        String toolCallState = string(payload.get("tool_call_state"), "");
        String toolResultState = string(payload.get("tool_result_state"), "");
        if (!toolName.isBlank()) refs.put("tool_name", toolName);
        if (!toolId.isBlank()) refs.put("tool_id", toolId);
        if (!skillName.isBlank()) refs.put("skill_name", skillName);
        if (!skillId.isBlank()) refs.put("skill_id", skillId);
        if (!toolCallId.isBlank()) refs.put("tool_call_id", toolCallId);
        if (!toolCallName.isBlank()) refs.put("tool_call_name", toolCallName);
        if (!toolCallState.isBlank()) refs.put("tool_call_state", toolCallState);
        if (!toolResultState.isBlank()) refs.put("tool_result_state", toolResultState);
        if (!replyId.isBlank()) refs.put("reply_id", replyId);
        return refs;
    }

    private static Mono<Void> writeSkillFolderUpload(
            List<FilePart> files, String manifest, Path tempDir) {
        return Mono.fromCallable(() -> skillFolderPaths(manifest, files))
                .flatMapMany(
                        paths ->
                                Flux.range(0, files.size())
                                        .concatMap(
                                                index -> {
                                                    Path target =
                                                            resolveUploadPath(
                                                                    tempDir, paths.get(index));
                                                    try {
                                                        Files.createDirectories(target.getParent());
                                                    } catch (Exception e) {
                                                        return Mono.error(e);
                                                    }
                                                    return files.get(index).transferTo(target);
                                                }))
                .then();
    }

    private static Map<String, String> packageMetadata(
            String skillId, String name, String version, String description, String sourceNote) {
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "skill_id", skillId);
        putIfPresent(metadata, "name", name);
        putIfPresent(metadata, "version", version);
        putIfPresent(metadata, "description", description);
        putIfPresent(metadata, "source_note", sourceNote);
        return metadata;
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private static List<String> skillFolderPaths(String manifest, List<FilePart> files) {
        int fileCount = files.size();
        List<String> parsed = List.of();
        try {
            parsed = JSON.readValue(manifest, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            parsed = looseManifestPaths(manifest);
        }
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < fileCount; index++) {
            String path = index < parsed.size() ? parsed.get(index) : "";
            if (path == null || path.isBlank()) {
                path = files.get(index).filename();
            }
            paths.add(path);
        }
        return stripCommonUploadRoot(paths);
    }

    private static List<String> stripCommonUploadRoot(List<String> paths) {
        if (paths.isEmpty()) {
            return paths;
        }
        String root = "";
        for (String path : paths) {
            String normalized = path == null ? "" : path.replace('\\', '/').trim();
            int slash = normalized.indexOf('/');
            if (slash <= 0) {
                return paths;
            }
            String current = normalized.substring(0, slash);
            if (root.isBlank()) {
                root = current;
            } else if (!root.equals(current)) {
                return paths;
            }
        }
        if (root.isBlank()) {
            return paths;
        }
        List<String> stripped = new ArrayList<>();
        for (String path : paths) {
            String normalized = path == null ? "" : path.replace('\\', '/').trim();
            stripped.add(normalized.substring(root.length() + 1));
        }
        return stripped;
    }

    private static List<String> looseManifestPaths(String manifest) {
        String text = manifest == null ? "" : manifest.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split("[,\\r\\n]+"))
                .map(String::trim)
                .map(value -> value.replaceAll("^['\\\"]|['\\\"]$", ""))
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static Path resolveUploadPath(Path root, String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/').trim();
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("../")) {
            throw new IllegalArgumentException("Invalid folder upload path: " + relativePath);
        }
        Path target = root.resolve(normalized).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Folder upload path escapes target: " + relativePath);
        }
        return target;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {
                                    // best effort cleanup
                                }
                            });
        } catch (Exception ignored) {
            // best effort cleanup
        }
    }

    private Map<String, Object> domainsMap() {
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Map<String, Object> domain : state.domains()) {
            Map<String, Object> value = new LinkedHashMap<>(domain);
            value.putIfAbsent("live_available", true);
            rows.put(String.valueOf(domain.get("domain")), value);
        }
        return rows;
    }

    private static Map<String, Object> emptyResources() {
        return map(
                "attachments",
                List.of(),
                "documents",
                List.of(),
                "citations",
                List.of(),
                "kg_hits",
                List.of(),
                "tool_calls",
                List.of(),
                "artifacts",
                List.of());
    }

    private static String string(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String text = string(value, "");
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String safePathName(String value) {
        String text = value == null ? "tool" : value.trim();
        text = text.replaceAll("[^A-Za-z0-9_.-]", "_");
        return text.isBlank() ? "tool" : text;
    }

    private String pythonCommand() {
        String value = System.getenv("AGENT_PLATFORM_PYTHON");
        return value == null || value.isBlank() ? "python" : value;
    }

    private Path resolveWorkspacePath(String value) {
        return storage.resolveRelativeToWorkspace(value == null ? "" : value);
    }

    private Map<String, Object> pythonCompile(Path scriptPath) throws Exception {
        Process process =
                new ProcessBuilder(pythonCommand(), "-m", "py_compile", scriptPath.toString())
                        .directory(scriptPath.getParent().toFile())
                        .start();
        boolean finished = process.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return map("ok", false, "timed_out", true, "error", "py_compile timed out.");
        }
        String stdout =
                process.inputReader()
                        .lines()
                        .reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right);
        String stderr =
                process.errorReader()
                        .lines()
                        .reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right);
        return map(
                "ok",
                process.exitValue() == 0,
                "exit_code",
                process.exitValue(),
                "stdout",
                stdout,
                "stderr",
                stderr);
    }

    private Map<String, Object> pythonExecutionResponse(
            String toolId,
            long latencyMs,
            PythonScriptTool.ExecutionResult execution,
            Map<String, Object> syntax) {
        boolean parsedOk =
                execution.parsed() instanceof Map<?, ?> parsed
                        && !Boolean.FALSE.equals(parsed.get("ok"));
        boolean ok = execution.ok() && !execution.timedOut() && parsedOk;
        Map<String, Object> response =
                new LinkedHashMap<>(
                        map(
                                "ok",
                                ok,
                                "tool_id",
                                toolId,
                                "latency_ms",
                                latencyMs,
                                "timed_out",
                                execution.timedOut(),
                                "exit_code",
                                execution.exitCode(),
                                "stdout",
                                execution.stdout(),
                                "stderr",
                                execution.stderr(),
                                "result",
                                execution.parsed(),
                                "result_preview",
                                previewResult(execution)));
        if (syntax != null) {
            response.put("syntax", syntax);
        }
        if (!ok) {
            response.put(
                    "stage",
                    execution.timedOut() ? "timeout" : execution.ok() ? "result" : "runtime");
            response.putIfAbsent("error", pythonExecutionError(execution));
        }
        return response;
    }

    private String pythonExecutionError(PythonScriptTool.ExecutionResult execution) {
        if (execution.timedOut()) {
            return "Python tool timed out.";
        }
        if (execution.exitCode() != 0) {
            return execution.stderr();
        }
        if (!(execution.parsed() instanceof Map<?, ?>)) {
            return "stdout is not valid JSON object.";
        }
        Object error = ((Map<?, ?>) execution.parsed()).get("error");
        return error == null ? "Python tool returned ok=false." : String.valueOf(error);
    }

    private String previewResult(PythonScriptTool.ExecutionResult execution) {
        Object result = execution.parsed();
        if (result != null) {
            String text = String.valueOf(result);
            return text.length() > 500 ? text.substring(0, 500) : text;
        }
        String stdout = execution.stdout();
        return stdout == null || stdout.length() <= 500 ? stdout : stdout.substring(0, 500);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String resolveSecret(String secretRef) {
        String value = secretRef == null ? "" : secretRef.trim();
        if (value.startsWith("env:")) {
            return string(System.getenv(value.substring(4)), "");
        }
        String byEnv = System.getenv(value);
        return byEnv == null || byEnv.isBlank() ? value : byEnv;
    }

    private static String resolveRequestUrl(String baseUrl, String path, String providerType) {
        String base = trimTrailingSlash(baseUrl);
        if (hasEndpoint(base) || path == null || path.isBlank()) {
            return base;
        }
        String suffix = path.startsWith("/") ? path : "/" + path;
        if (isOpenAiCompatibleProvider(providerType) && !base.matches(".*/v1(/.*)?$")) {
            return base + "/v1" + suffix;
        }
        return base + suffix;
    }

    private static String defaultEndpointPath(String providerType, String callType) {
        if ("embed".equals(callType)) {
            if ("ollama".equals(providerType)) {
                return "/api/embeddings";
            }
            if (isOpenAiCompatibleProvider(providerType)) {
                return "/embeddings";
            }
        }
        if ("ollama".equals(providerType)) {
            return "/api/chat";
        }
        if ("http_chat".equals(providerType)) {
            return "";
        }
        if ("dashscope".equals(providerType)) {
            return "/compatible-mode/v1/chat/completions";
        }
        if ("echo".equals(providerType)) {
            return "";
        }
        if (isOpenAiCompatibleProvider(providerType)) {
            return "/chat/completions";
        }
        return "";
    }

    private static boolean isOpenAiCompatibleProvider(String providerType) {
        return List.of("openai-compatible", "openai_compatible", "openai").contains(providerType);
    }

    private static boolean hasEndpoint(String baseUrl) {
        return baseUrl.matches(".*/(chat/completions|embeddings|api/chat|api/embeddings)$");
    }

    private static String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return Map.of();
    }

    private static List<DocumentKnowledgeService.RequestedDocument> requestedDocumentScope(
            Map<String, Object> payload) {
        List<DocumentKnowledgeService.RequestedDocument> documents = new ArrayList<>();
        addRequestedDocument(documents, payload.get("doc_id"), payload.get("version_id"));
        addRequestedDocument(
                documents, payload.get("document_id"), payload.get("version_id"));
        addRequestedDocumentList(documents, payload.get("document_ids"));
        addRequestedAttachments(documents, payload.get("attachments"));
        Map<String, Object> nested = objectMap(payload.get("payload"));
        if (!nested.isEmpty()) {
            addRequestedDocument(documents, nested.get("doc_id"), nested.get("version_id"));
            addRequestedDocument(
                    documents, nested.get("document_id"), nested.get("version_id"));
            addRequestedDocumentList(documents, nested.get("document_ids"));
            addRequestedAttachments(documents, nested.get("attachments"));
        }
        Map<String, Object> scope = objectMap(payload.get("retrieve_scope"));
        addRequestedScope(documents, scope);
        addRequestedScope(documents, objectMap(nested.get("retrieve_scope")));
        return documents.stream().distinct().toList();
    }

    private static void addRequestedDocumentList(
            List<DocumentKnowledgeService.RequestedDocument> documents, Object value) {
        for (Object item : list(value)) {
            if (item instanceof Map<?, ?> row) {
                addRequestedDocument(documents, row.get("doc_id"), row.get("version_id"));
            } else {
                addRequestedDocument(documents, item, "");
            }
        }
    }

    private static void addRequestedAttachments(
            List<DocumentKnowledgeService.RequestedDocument> documents, Object value) {
        for (Object attachment : list(value)) {
            if (attachment instanceof Map<?, ?> row) {
                addRequestedDocument(documents, row.get("doc_id"), row.get("version_id"));
            }
        }
    }

    private static void addRequestedScope(
            List<DocumentKnowledgeService.RequestedDocument> documents,
            Map<String, Object> scope) {
        for (Object item : list(scope.get("allowed_doc_versions"))) {
            if (item instanceof Map<?, ?> row) {
                addRequestedDocument(documents, row.get("doc_id"), row.get("version_id"));
            }
        }
    }

    private static void addRequestedDocument(
            List<DocumentKnowledgeService.RequestedDocument> documents,
            Object value,
            Object version) {
        String id = string(value, "");
        if (!id.isBlank()) {
            documents.add(
                    new DocumentKnowledgeService.RequestedDocument(id, string(version, "")));
        }
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> rows ? rows : List.of();
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractOpenAiText(Map<String, Object> response) {
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            return "";
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return "";
        }
        Object message = choice.get("message");
        if (message instanceof Map<?, ?> messageMap) {
            Object content = messageMap.get("content");
            if (content instanceof String text) {
                return text;
            }
            if (content instanceof List<?> parts) {
                List<String> texts = new ArrayList<>();
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> partMap && partMap.get("text") != null) {
                        texts.add(String.valueOf(partMap.get("text")));
                    }
                }
                return String.join("", texts);
            }
        }
        return string(choice.get("text"), "");
    }

    private static List<Double> sampleVector(double[] vector) {
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        List<Double> sample = new ArrayList<>();
        for (int i = 0; i < Math.min(vector.length, 8); i++) {
            sample.add(vector[i]);
        }
        return sample;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
