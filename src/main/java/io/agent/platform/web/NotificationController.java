/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.scheduled.ScheduledTaskService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final ScheduledTaskService service;
    private final PlatformAuthService auth;

    public NotificationController(ScheduledTaskService service, PlatformAuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            ServerHttpRequest request) {
        var principal = principal(request);
        return service.notificationPage(
                principal.userId(), principal.orgId(), unreadOnly, limit);
    }

    @PostMapping("/{notificationId}/read")
    public Map<String, Object> read(
            @PathVariable String notificationId, ServerHttpRequest request) {
        var principal = principal(request);
        service.markNotificationRead(notificationId, principal.userId(), principal.orgId());
        return map("ok", true, "notification_id", notificationId);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(ServerHttpRequest request) {
        var principal = principal(request);
        service.markAllNotificationsRead(principal.userId(), principal.orgId());
        return map("ok", true);
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
