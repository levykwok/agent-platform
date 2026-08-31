/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.Map;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Logged-in management endpoints for owner-scoped external API keys and invocations. */
@RestController
@RequestMapping("/platform/frontend/external-api")
public class ExternalAccessManagementController {

    private final PlatformAuthService auth;
    private final ExternalAccessService externalAccess;

    public ExternalAccessManagementController(
            PlatformAuthService auth, ExternalAccessService externalAccess) {
        this.auth = auth;
        this.externalAccess = externalAccess;
    }

    @GetMapping("/keys")
    public Map<String, Object> keys(ServerHttpRequest request) {
        var items = externalAccess.listKeys(requirePrincipal(request));
        return Map.of("items", items, "count", items.size());
    }

    @PostMapping("/keys")
    public Map<String, Object> createKey(
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        return Map.of(
                "item",
                externalAccess.createKey(
                        requirePrincipal(request),
                        string(body.get("name")),
                        string(body.get("expires_at"))));
    }

    @PostMapping("/keys/{keyId}/revoke")
    public Map<String, Object> revokeKey(
            @PathVariable("keyId") String keyId, ServerHttpRequest request) {
        externalAccess.revokeKey(keyId, requirePrincipal(request));
        return Map.of("ok", true, "key_id", keyId);
    }

    @GetMapping("/invocations")
    public Map<String, Object> invocations(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            ServerHttpRequest request) {
        var items = externalAccess.invocationHistory(requirePrincipal(request), limit);
        return Map.of("items", items, "count", items.size());
    }

    private PlatformAuthService.Principal requirePrincipal(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst("platform_session");
        var principal = auth.current(cookie == null ? "" : cookie.getValue());
        if (principal == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录");
        }
        return principal;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
