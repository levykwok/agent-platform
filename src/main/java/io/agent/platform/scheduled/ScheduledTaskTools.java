/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.scheduled;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Agent-callable schedule management tools. They are safe because identity comes from the runtime. */
@Component
public class ScheduledTaskTools {

    private final ScheduledTaskService service;

    public ScheduledTaskTools(ScheduledTaskService service) {
        this.service = service;
    }

    @Tool(
            name = "schedule_create",
            description =
                    "Create a recurring scheduled task for the current user. The task result is written to the session. Optionally configure a Webhook URL for an external notification.")
    public Map<String, Object> create(
            @ToolParam(name = "name", description = "Human-readable task name") String name,
            @ToolParam(name = "prompt", description = "Prompt sent to the Agent") String prompt,
            @ToolParam(name = "cron", description = "5-field or 6-field Spring cron expression") String cron,
            @ToolParam(name = "agent_id", description = "Published Agent id") String agentId,
            @ToolParam(name = "session_id", description = "Optional existing session id", required = false)
                    String sessionId,
            @ToolParam(name = "timezone", description = "Optional IANA timezone", required = false)
                    String timezone,
            @ToolParam(name = "webhook_url", description = "Optional HTTPS callback URL for task completion notifications", required = false)
                    String webhookUrl,
            @ToolParam(name = "webhook_secret", description = "Optional HMAC signing secret for the Webhook", required = false)
                    String webhookSecret,
            @ToolParam(name = "webhook_enabled", description = "Whether the optional Webhook is enabled", required = false)
                    Boolean webhookEnabled) {
        return service.create(
                map(
                        "name", name,
                        "prompt", prompt,
                        "cron", cron,
                        "agent_id", agentId,
                        "session_id", sessionId,
                        "timezone", timezone,
                        "webhook_url", webhookUrl,
                        "webhook_secret", webhookSecret,
                        "webhook_enabled", webhookEnabled),
                userId(),
                orgId());
    }

    @Tool(name = "schedule_list", description = "List scheduled tasks owned by the current user.")
    public List<Map<String, Object>> list() {
        return service.list(userId(), orgId());
    }

    @Tool(name = "schedule_get", description = "Get one scheduled task owned by the current user.")
    public Map<String, Object> get(
            @ToolParam(name = "task_id", description = "Scheduled task id") String taskId) {
        return service.get(taskId, userId(), orgId());
    }

    @Tool(name = "schedule_get_runs", description = "List execution records for a scheduled task.")
    public List<Map<String, Object>> runs(
            @ToolParam(name = "task_id", description = "Scheduled task id") String taskId,
            @ToolParam(name = "limit", description = "Maximum number of records", required = false)
                    Integer limit) {
        return service.runs(taskId, userId(), orgId(), limit == null ? 20 : limit);
    }

    @Tool(name = "schedule_pause", description = "Pause a scheduled task.")
    public Map<String, Object> pause(
            @ToolParam(name = "task_id", description = "Scheduled task id") String taskId) {
        return service.disable(taskId, userId(), orgId());
    }

    @Tool(name = "schedule_resume", description = "Resume a paused scheduled task.")
    public Map<String, Object> resume(
            @ToolParam(name = "task_id", description = "Scheduled task id") String taskId) {
        return service.enable(taskId, userId(), orgId());
    }

    @Tool(name = "schedule_delete", description = "Delete a scheduled task.")
    public Map<String, Object> delete(
            @ToolParam(name = "task_id", description = "Scheduled task id") String taskId) {
        service.delete(taskId, userId(), orgId());
        return map("ok", true, "task_id", taskId);
    }

    @Tool(name = "schedule_run_now", description = "Run a scheduled task immediately.")
    public Map<String, Object> runNow(
            @ToolParam(name = "task_id", description = "Scheduled task id") String taskId) {
        return service.runNow(taskId, userId(), orgId()).block();
    }

    private String userId() {
        ScheduledTaskCallContext.Identity identity = ScheduledTaskCallContext.current();
        if (identity == null || identity.userId().isBlank()) {
            throw new IllegalStateException("schedule tools require an authenticated Agent context");
        }
        return identity.userId();
    }

    private String orgId() {
        ScheduledTaskCallContext.Identity identity = ScheduledTaskCallContext.current();
        return identity == null ? "" : identity.orgId();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
