/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.scheduled;

import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.runtime.ChatRequest;
import io.agent.platform.runtime.ChatResponse;
import io.agent.platform.runtime.protocol.TaskContext;
import io.agent.platform.web.PlatformCompatibilityState;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Owns the scheduling policy and routes scheduled prompts through the normal Agent runtime. */
@Service
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

    private final ScheduledTaskStore store;
    private final AgentRuntime runtime;
    private final AgentDefinitionRegistry agents;
    private final PlatformCompatibilityState state;
    private final PlatformStorageLayer storage;
    private final ScheduledTaskWebhookService webhook;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseMs;
    private final ZoneId defaultZone;

    public ScheduledTaskService(
            ScheduledTaskStore store,
            AgentRuntime runtime,
            AgentDefinitionRegistry agents,
            PlatformCompatibilityState state,
            PlatformStorageLayer storage,
            ScheduledTaskWebhookService webhook,
            @Value("${agent.platform.scheduled-tasks.enabled:true}") boolean enabled,
            @Value("${agent.platform.scheduled-tasks.batch-size:20}") int batchSize,
            @Value("${agent.platform.scheduled-tasks.lease-ms:120000}") long leaseMs,
            @Value("${agent.platform.scheduled-tasks.default-timezone:}") String defaultTimezone) {
        this.store = store;
        this.runtime = runtime;
        this.agents = agents;
        this.state = state;
        this.storage = storage;
        this.webhook = webhook;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(200, batchSize));
        this.leaseMs = Math.max(10_000L, leaseMs);
        this.defaultZone = defaultTimezone == null || defaultTimezone.isBlank()
                ? ZoneId.systemDefault()
                : resolveZone(defaultTimezone);
    }

    public List<Map<String, Object>> list(String userId, String orgId) {
        return store.listTasks(userId, orgId).stream().map(this::publicTask).toList();
    }

    public Map<String, Object> get(String taskId, String userId, String orgId) {
        return publicTask(requireTask(taskId, userId, orgId));
    }

    public Map<String, Object> create(Map<String, Object> payload, String userId, String orgId) {
        String agentId = first(payload, "agent_id", "agentId");
        if (agentId.isBlank()) throw badRequest("agent_id 不能为空");
        if (agents.findPublished(agentId).isEmpty()) {
            throw badRequest("Agent 不存在或未发布: " + agentId);
        }
        String prompt = first(payload, "prompt", "message", "instruction");
        if (prompt.isBlank()) throw badRequest("prompt 不能为空");
        String cron = normalizeCron(first(payload, "cron", "cron_expression", "schedule"));
        validateCron(cron);
        ZoneId zone = resolveZone(first(payload, "timezone", "time_zone"));
        String webhookUrl = first(payload, "webhook_url", "callback_url");
        validateWebhookUrl(webhookUrl);
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "");
        String sessionId = first(payload, "session_id", "sessionId");
        if (sessionId.isBlank()) {
            Map<String, Object> session =
                    state.newSession(
                            map(
                                    "agent_id", agentId,
                                    "title", nonBlank(first(payload, "name", "title"), "定时任务")),
                            orgId,
                            userId);
            sessionId = string(session.get("session_id"));
        } else if (storage.isSqliteEnabled() && !state.sessionOwnedBy(sessionId, orgId, userId)) {
            throw notFound("会话不存在或当前账号无权访问");
        }
        String now = Instant.now().toString();
        Map<String, Object> task =
                map(
                        "task_id", taskId,
                        "org_id", orgId,
                        "user_id", userId,
                        "agent_id", agentId,
                        "session_id", sessionId,
                        "name", nonBlank(first(payload, "name", "title"), "定时任务"),
                        "prompt", prompt,
                        "cron_expression", cron,
                        "timezone", zone.getId(),
                        "status", requestedStatus(payload, "ACTIVE"),
                        "next_run_at", nextRun(cron, zone, Instant.now()),
                        "last_run_at", "",
                        "lease_until", "",
                        "webhook_url", webhookUrl,
                        "webhook_secret", first(payload, "webhook_secret", "callback_secret"),
                        "webhook_enabled", webhookEnabledValue(payload, true),
                        "created_at", now,
                        "updated_at", now);
        store.createTask(task);
        return publicTask(task);
    }

    public Map<String, Object> update(
            String taskId, Map<String, Object> payload, String userId, String orgId) {
        Map<String, Object> task = requireTask(taskId, userId, orgId);
        String agentId = first(payload, "agent_id", "agentId");
        if (!agentId.isBlank()) {
            if (agents.findPublished(agentId).isEmpty()) {
                throw badRequest("Agent 不存在或未发布: " + agentId);
            }
            task.put("agent_id", agentId);
        }
        String prompt = first(payload, "prompt", "message", "instruction");
        if (!prompt.isBlank()) task.put("prompt", prompt);
        String sessionId = first(payload, "session_id", "sessionId");
        if (!sessionId.isBlank()) {
            if (storage.isSqliteEnabled() && !state.sessionOwnedBy(sessionId, orgId, userId)) {
                throw notFound("会话不存在或当前账号无权访问");
            }
            task.put("session_id", sessionId);
        }
        String name = first(payload, "name", "title");
        if (!name.isBlank()) task.put("name", name);
        String cron = first(payload, "cron", "cron_expression", "schedule");
        String timezone = first(payload, "timezone", "time_zone");
        if (!cron.isBlank()) task.put("cron_expression", normalizeCron(cron));
        if (!timezone.isBlank()) task.put("timezone", resolveZone(timezone).getId());
        if (payload.containsKey("webhook_url") || payload.containsKey("callback_url")) {
            String webhookUrl = first(payload, "webhook_url", "callback_url");
            validateWebhookUrl(webhookUrl);
            task.put("webhook_url", webhookUrl);
        }
        if (payload.containsKey("webhook_secret") || payload.containsKey("callback_secret")) {
            task.put("webhook_secret", first(payload, "webhook_secret", "callback_secret"));
        }
        if (payload.containsKey("webhook_enabled") || payload.containsKey("callback_enabled")) {
            task.put("webhook_enabled", webhookEnabledValue(payload, true));
        }
        validateCron(string(task.get("cron_expression")));
        String status = requestedStatus(payload, "");
        if (!status.isBlank()) task.put("status", status);
        if (!cron.isBlank() || !timezone.isBlank() || "ACTIVE".equals(task.get("status"))) {
            task.put(
                    "next_run_at",
                    nextRun(
                            string(task.get("cron_expression")),
                            resolveZone(string(task.get("timezone"))),
                            Instant.now()));
        }
        task.put("lease_until", "");
        task.put("updated_at", Instant.now().toString());
        store.updateTask(task);
        return publicTask(task);
    }

    public Map<String, Object> enable(String taskId, String userId, String orgId) {
        return setStatus(taskId, "ACTIVE", userId, orgId);
    }

    public Map<String, Object> disable(String taskId, String userId, String orgId) {
        return setStatus(taskId, "PAUSED", userId, orgId);
    }

    public void delete(String taskId, String userId, String orgId) {
        requireTask(taskId, userId, orgId);
        store.deleteTask(taskId, userId, orgId);
    }

    public List<Map<String, Object>> runs(String taskId, String userId, String orgId, int limit) {
        requireTask(taskId, userId, orgId);
        return store.listRuns(taskId, userId, orgId, limit);
    }

    public Mono<Map<String, Object>> runNow(String taskId, String userId, String orgId) {
        Map<String, Object> task = requireTask(taskId, userId, orgId);
        return Mono.fromCallable(() -> execute(task, Instant.now(), false))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Map<String, Object> notificationPage(
            String userId, String orgId, boolean unreadOnly, int limit) {
        return map(
                "items", store.listNotifications(userId, orgId, unreadOnly, limit),
                "unreadCount", store.unreadCount(userId, orgId));
    }

    public void markNotificationRead(String id, String userId, String orgId) {
        store.markNotificationRead(id, userId, orgId);
    }

    public void markAllNotificationsRead(String userId, String orgId) {
        store.markAllNotificationsRead(userId, orgId);
    }

    @Scheduled(
            fixedDelayString = "${agent.platform.scheduled-tasks.poll-ms:30000}",
            initialDelayString = "${agent.platform.scheduled-tasks.initial-delay-ms:15000}")
    public void poll() {
        if (!enabled) return;
        List<Map<String, Object>> tasks = store.claimDueTasks(Instant.now(), batchSize, leaseMs);
        for (Map<String, Object> task : tasks) {
            Instant scheduledAt = parseInstant(task.get("next_run_at"));
            advanceSchedule(task);
            Mono.fromRunnable(() -> execute(task, scheduledAt, true))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnError(
                            error ->
                                    log.warn(
                                            "Scheduled task {} failed outside execution guard: {}",
                                            task.get("task_id"),
                                            error.getMessage()))
                    .subscribe();
        }
    }

    private Map<String, Object> execute(
            Map<String, Object> task, Instant scheduledAt, boolean claimed) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> run =
                map(
                        "run_id", runId,
                        "task_id", task.get("task_id"),
                        "agent_id", task.get("agent_id"),
                        "session_id", task.get("session_id"),
                        "org_id", task.get("org_id"),
                        "user_id", task.get("user_id"),
                        "scheduled_at", scheduledAt == null ? "" : scheduledAt.toString(),
                        "started_at", Instant.now().toString(),
                        "finished_at", "",
                        "status", "RUNNING",
                        "response_text", "",
                        "error_message", "");
        store.createRun(run);
        try {
            String agentId = string(task.get("agent_id"));
            String sessionId = string(task.get("session_id"));
            String userId = string(task.get("user_id"));
            String orgId = string(task.get("org_id"));
            Map<String, Object> metadata =
                    map(
                            "source", "scheduled_task",
                            "scheduled_task_id", task.get("task_id"),
                            "scheduled_task_run_id", runId);
            String agentPrompt = scheduledPrompt(task, runId, scheduledAt);
            state.appendSessionMessage(
                    agentId, sessionId, userId, "user", agentPrompt, metadata);
            TaskContext context =
                    TaskContext.root("scheduler", agentId)
                            .withMetadata("source", "scheduled_task")
                            .withMetadata("scheduled_task_id", task.get("task_id"))
                            .withMetadata("scheduled_task_run_id", runId);
            ChatResponse response =
                    runtime.chat(
                                    agentId,
                                    new ChatRequest(
                                            orgId,
                                            userId,
                                            sessionId,
                                            agentPrompt,
                                            context))
                            .block();
            String text = response == null ? "" : nonBlank(response.text(), "");
            state.appendSessionMessage(agentId, sessionId, userId, "assistant", text, metadata);
            finishRun(run, "SUCCEEDED", text, "");
            notify(
                    task,
                    runId,
                    "scheduled_task.succeeded",
                    "定时任务已完成",
                    string(task.get("name")) + " 执行成功");
            deliverWebhook(task, run, "SUCCEEDED", text, "");
        } catch (Throwable error) {
            String message = nonBlank(error.getMessage(), "定时任务执行失败");
            finishRun(run, "FAILED", "", message);
            notify(
                    task,
                    runId,
                    "scheduled_task.failed",
                    "定时任务执行失败",
                    string(task.get("name")) + "：" + message);
            deliverWebhook(task, run, "FAILED", "", message);
        } finally {
            task.put("lease_until", "");
            task.put("last_run_at", Instant.now().toString());
            task.put("updated_at", Instant.now().toString());
            // A manual run keeps the next cron occurrence unchanged; a claimed run already
            // advanced it before execution.
            store.updateTask(task);
        }
        return run;
    }

    private String scheduledPrompt(
            Map<String, Object> task, String runId, Instant scheduledAt) {
        return """
                [平台运行上下文]
                当前请求是由平台的定时任务自动触发的，不是用户此刻手动发送的消息。
                请直接执行任务内容；不要询问用户是否现在执行，也不要因为没有实时用户对话而跳过任务。
                如果任务需要外部信息，请按 Agent 已绑定的工具和权限完成；输出最终结果即可。

                定时任务名称：%s
                定时任务 ID：%s
                本次执行 ID：%s
                计划执行时间：%s

                [任务内容]
                %s
                """
                .formatted(
                        string(task.get("name")),
                        string(task.get("task_id")),
                        runId,
                        scheduledAt == null ? "立即执行" : scheduledAt,
                        string(task.get("prompt")));
    }

    private void advanceSchedule(Map<String, Object> task) {
        String cron = string(task.get("cron_expression"));
        ZoneId zone = resolveZone(string(task.get("timezone")));
        Instant previous = parseInstant(task.get("next_run_at"));
        task.put("next_run_at", nextRun(cron, zone, previous == null ? Instant.now() : previous));
        task.put("updated_at", Instant.now().toString());
        store.updateTask(task);
    }

    private void finishRun(Map<String, Object> run, String status, String response, String error) {
        run.put("status", status);
        run.put("response_text", response);
        run.put("error_message", error);
        run.put("finished_at", Instant.now().toString());
        store.updateRun(run);
    }

    private void notify(
            Map<String, Object> task, String runId, String type, String title, String body) {
        store.createNotification(
                map(
                        "notification_id", "notification_" + UUID.randomUUID().toString().replace("-", ""),
                        "org_id", task.get("org_id"),
                        "user_id", task.get("user_id"),
                        "type", type,
                        "title", title,
                        "body", body,
                        "task_id", task.get("task_id"),
                        "run_id", runId,
                        "read_at", "",
                        "created_at", Instant.now().toString()));
    }

    private void deliverWebhook(
            Map<String, Object> task,
            Map<String, Object> run,
            String status,
            String resultText,
            String errorMessage) {
        try {
            ScheduledTaskWebhookService.DeliveryResult delivery =
                    webhook.deliver(task, run, status, resultText, errorMessage);
            run.put("webhook_status", delivery.status());
            run.put("webhook_attempts", delivery.attempts());
            run.put("webhook_http_status", delivery.httpStatus());
            run.put("webhook_message", delivery.message());
            store.updateRun(run);
            if ("FAILED".equals(delivery.status())) {
                log.warn(
                        "Webhook delivery failed for scheduled task {} run {}: {}",
                        task.get("task_id"),
                        run.get("run_id"),
                        delivery.message());
            }
        } catch (Throwable error) {
            // A callback outage must never turn a successful Agent run into a failed run.
            log.warn(
                    "Webhook delivery isolated for scheduled task {} run {}: {}",
                    task.get("task_id"),
                    run.get("run_id"),
                    error.getMessage());
        }
    }

    private Map<String, Object> setStatus(
            String taskId, String status, String userId, String orgId) {
        Map<String, Object> task = requireTask(taskId, userId, orgId);
        task.put("status", status);
        task.put("lease_until", "");
        if ("ACTIVE".equals(status)) {
            task.put(
                    "next_run_at",
                    nextRun(
                            string(task.get("cron_expression")),
                            resolveZone(string(task.get("timezone"))),
                            Instant.now()));
        }
        task.put("updated_at", Instant.now().toString());
        store.updateTask(task);
        return publicTask(task);
    }

    private Map<String, Object> requireTask(String taskId, String userId, String orgId) {
        Map<String, Object> task = store.findTask(taskId, userId, orgId);
        if (task == null) throw notFound("定时任务不存在或当前账号无权访问");
        return task;
    }

    private Map<String, Object> publicTask(Map<String, Object> task) {
        Map<String, Object> row = new LinkedHashMap<>(task);
        row.put("enabled", "ACTIVE".equals(row.get("status")));
        row.put("webhook_configured", !string(row.get("webhook_url")).isBlank());
        row.put(
                "webhook_enabled",
                task.containsKey("webhook_enabled")
                        ? webhookEnabledValue(task, true)
                        : !string(task.get("webhook_url")).isBlank());
        row.remove("webhook_secret");
        row.remove("lease_until");
        return row;
    }

    private String nextRun(String expression, ZoneId zone, Instant after) {
        CronExpression cron = validateCron(expression);
        ZonedDateTime next = cron.next((after == null ? Instant.now() : after).atZone(zone));
        if (next == null) throw badRequest("Cron 表达式没有下一次执行时间");
        return next.toInstant().toString();
    }

    private CronExpression validateCron(String value) {
        try {
            return CronExpression.parse(normalizeCron(value));
        } catch (IllegalArgumentException error) {
            throw badRequest("无效的 Cron 表达式（支持 5 位或 Spring 6 位格式）: " + value);
        }
    }

    private String normalizeCron(String value) {
        String cron = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (cron.isBlank()) throw badRequest("cron 不能为空");
        if (cron.split(" ").length == 5) cron = "0 " + cron;
        if (cron.split(" ").length != 6) throw badRequest("Cron 必须是 5 位或 6 位格式");
        return cron;
    }

    private ZoneId resolveZone(String value) {
        String zone = value == null ? "" : value.trim();
        if (zone.isBlank()) return defaultZone;
        try {
            return ZoneId.of(zone);
        } catch (Exception error) {
            throw badRequest("无效的时区: " + zone);
        }
    }

    private void validateWebhookUrl(String value) {
        try {
            webhook.validateUrl(value);
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
    }

    private static String requestedStatus(Map<String, Object> payload, String fallback) {
        String status = first(payload, "status");
        if (status.isBlank() && payload != null && payload.containsKey("enabled")) {
            status = Boolean.parseBoolean(String.valueOf(payload.get("enabled")))
                    ? "ACTIVE"
                    : "PAUSED";
        }
        if (status.isBlank()) return fallback;
        return switch (status.trim().toUpperCase()) {
            case "ACTIVE", "ENABLED", "1", "RUNNING" -> "ACTIVE";
            case "PAUSED", "DISABLED", "0", "INACTIVE" -> "PAUSED";
            default -> throw badRequest("status 只能是 ACTIVE 或 PAUSED");
        };
    }

    private static boolean webhookEnabledValue(Map<String, Object> payload, boolean fallback) {
        if (payload == null) return fallback;
        Object value = payload.containsKey("webhook_enabled")
                ? payload.get("webhook_enabled")
                : payload.get("callback_enabled");
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Instant parseInstant(Object value) {
        String text = string(value);
        if (text.isBlank()) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String first(Map<String, Object> payload, String... keys) {
        if (payload == null) return "";
        for (String key : keys) {
            String value = string(payload.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
