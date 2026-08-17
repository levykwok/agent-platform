/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.scheduled.ScheduledTaskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskService service;
    private final PlatformAuthService auth;

    public ScheduledTaskController(ScheduledTaskService service, PlatformAuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    public Map<String, Object> list(ServerHttpRequest request) {
        var principal = principal(request);
        return map("items", service.list(principal.userId(), principal.orgId()));
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        var principal = principal(request);
        return map("item", service.create(payload, principal.userId(), principal.orgId()));
    }

    @GetMapping("/{taskId}")
    public Map<String, Object> get(
            @PathVariable String taskId, ServerHttpRequest request) {
        var principal = principal(request);
        return map("item", service.get(taskId, principal.userId(), principal.orgId()));
    }

    @PutMapping("/{taskId}")
    public Map<String, Object> update(
            @PathVariable String taskId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        var principal = principal(request);
        return map(
                "item",
                service.update(taskId, payload, principal.userId(), principal.orgId()));
    }

    @DeleteMapping("/{taskId}")
    public Map<String, Object> delete(
            @PathVariable String taskId, ServerHttpRequest request) {
        var principal = principal(request);
        service.delete(taskId, principal.userId(), principal.orgId());
        return map("ok", true, "task_id", taskId);
    }

    @PostMapping("/{taskId}/enable")
    public Map<String, Object> enable(
            @PathVariable String taskId, ServerHttpRequest request) {
        var principal = principal(request);
        return map("item", service.enable(taskId, principal.userId(), principal.orgId()));
    }

    @PostMapping("/{taskId}/disable")
    public Map<String, Object> disable(
            @PathVariable String taskId, ServerHttpRequest request) {
        var principal = principal(request);
        return map("item", service.disable(taskId, principal.userId(), principal.orgId()));
    }

    @PostMapping("/{taskId}/run-now")
    public Mono<Map<String, Object>> runNow(
            @PathVariable String taskId, ServerHttpRequest request) {
        var principal = principal(request);
        return service.runNow(taskId, principal.userId(), principal.orgId())
                .map(run -> map("item", run));
    }

    @GetMapping("/{taskId}/runs")
    public Map<String, Object> runs(
            @PathVariable String taskId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            ServerHttpRequest request) {
        var principal = principal(request);
        return map(
                "items",
                service.runs(taskId, principal.userId(), principal.orgId(), limit));
    }

    private PlatformAuthService.Principal principal(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst("platform_session");
        PlatformAuthService.Principal current =
                auth.current(cookie == null ? "" : cookie.getValue());
        if (current == null) throw new PlatformAuthService.AuthException(401, "请先登录");
        return current;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
