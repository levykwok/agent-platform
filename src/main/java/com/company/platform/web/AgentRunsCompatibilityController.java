/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.runtime.AgentEventEnvelope;
import com.company.platform.runtime.AgentRuntime;
import com.company.platform.runtime.ChatImage;
import com.company.platform.runtime.ChatRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/agent-runs")
public class AgentRunsCompatibilityController {

    private final PlatformCompatibilityState state;
    private final AgentRuntime runtime;
    private final DocumentKnowledgeService documentKnowledgeService;

    public AgentRunsCompatibilityController(
            PlatformCompatibilityState state,
            AgentRuntime runtime,
            DocumentKnowledgeService documentKnowledgeService) {
        this.state = state;
        this.runtime = runtime;
        this.documentKnowledgeService = documentKnowledgeService;
    }

    @PostMapping(value = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> streamRun(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "platform_admin") String userId,
            @RequestHeader(value = "x-org-id", defaultValue = "platform") String orgId) {
        String agentId = string(payload.get("agent_id"), "platform_knowledge_agent");
        Map<String, Object> body =
                payload.get("payload") instanceof Map<?, ?> nested ? copy(nested) : Map.of();
        String query = string(body.get("query"), string(payload.get("query"), ""));
        List<ChatImage> images = chatImages(body.get("images"));
        DocumentKnowledgeService.Retrieval retrieval =
                documentKnowledgeService.retrieve(query, documentIds(body), 4);
        String runtimeQuery = DocumentKnowledgeService.withContext(query, retrieval);
        String sessionId = string(payload.get("session_id"), "default");
        Map<String, Object> run = state.createRun(agentId, query, userId);
        String runId = string(run.get("run_id"), "");
        state.appendSessionMessage(agentId, sessionId, userId, "user", query);
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
                                                "接收请求",
                                                "status",
                                                "success",
                                                "summary",
                                                agentId,
                                                "run_id",
                                                runId))),
                        retrieval.citations().isEmpty()
                                ? Flux.empty()
                                : Flux.just(
                                        sse(
                                                "activity",
                                                map(
                                                        "type",
                                                        "activity",
                                                        "step",
                                                        "knowledge_retrieval",
                                                        "title",
                                                        "检索上传文档",
                                                        "status",
                                                        "success",
                                                        "summary",
                                                        "命中 "
                                                                + retrieval.citations().size()
                                                                + " 个文档片段",
                                                        "citations",
                                                        retrieval.citations(),
                                                        "run_id",
                                                        runId))),
                        runtime.stream(
                                        agentId,
                                        new ChatRequest(
                                                orgId,
                                                userId,
                                                sessionId,
                                                runtimeQuery,
                                                null,
                                                images))
                                .map(
                                        event -> {
                                            state.appendRunEventFromEnvelope(runId, event);
                                            return toSseEvent(event, answer.get(), runId);
                                        }),
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
                                }))
                .onErrorResume(
                        error -> {
                            Map<String, Object> failed = state.failRun(runId, error);
                            String message = string(error.getMessage(), "执行失败");
                            return Flux.just(
                                    sse(
                                            "activity",
                                            map(
                                                    "type",
                                                    "activity",
                                                    "step",
                                                    "respond",
                                                    "title",
                                                    "AgentScope 调用失败",
                                                    "status",
                                                    "failed",
                                                    "summary",
                                                    message)),
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

    private static ServerSentEvent<Map<String, Object>> toSseEvent(
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
                        "refs",
                        activityRefs(event),
                        "detail",
                        event.payload() == null ? Map.of() : event.payload()));
    }

    private static String activityTitle(AgentEventEnvelope event) {
        String type = string(event.type(), "agent_event").toLowerCase();
        type = type.replace('.', '_');
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
            return "工具 " + tools + "；MCP " + mcps + "；Skill " + skills;
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
        if (payload.containsKey("skill_call_name")) {
            return "Skill 调用：" + string(payload.get("skill_call_name"), "");
        }
        if (!skillName.isBlank()) {
            return "Skill：" + skillName;
        }
        if (!skillId.isBlank()) {
            return "Skill：" + skillId;
        }
        if (!toolName.isBlank()) {
            return "工具：" + toolName;
        }
        if (!toolId.isBlank()) {
            return "工具：" + toolId;
        }
        return string(event.source(), "");
    }

    private static Map<String, Object> activityRefs(AgentEventEnvelope event) {
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        Map<String, Object> refs = new LinkedHashMap<>();
        String toolCallId = string(payload.get("tool_call_id"), "");
        String toolCallName = string(payload.get("tool_call_name"), "");
        String toolCallState = string(payload.get("tool_call_state"), "");
        String toolResultState = string(payload.get("tool_result_state"), "");
        String toolName = string(payload.get("tool_name"), "");
        String toolId = string(payload.get("tool_id"), "");
        String skillName = string(payload.get("skill_name"), "");
        String skillId = string(payload.get("skill_id"), "");
        String replyId = string(payload.get("reply_id"), "");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static ServerSentEvent<Map<String, Object>> sse(
            String event, Map<String, Object> data) {
        return ServerSentEvent.builder(data).event(event).build();
    }

    private static List<ChatImage> chatImages(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<ChatImage> images = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            images.add(
                    new ChatImage(
                            string(row.get("url"), ""),
                            string(row.get("data"), ""),
                            firstText(row.get("media_type"), row.get("mediaType"), "image/jpeg")));
        }
        return List.copyOf(images);
    }

    private static List<String> documentIds(Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        Object values = body.get("document_ids");
        if (values instanceof List<?> rows) {
            for (Object value : rows) {
                String id = string(value, "");
                if (!id.isBlank()) ids.add(id);
            }
        }
        Object attachments = body.get("attachments");
        if (attachments instanceof List<?> rows) {
            for (Object value : rows) {
                if (value instanceof Map<?, ?> row) {
                    String id = string(row.get("doc_id"), "");
                    if (!id.isBlank()) ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private static String string(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
