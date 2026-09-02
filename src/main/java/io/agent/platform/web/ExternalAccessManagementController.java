/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.ArrayList;
import java.util.List;
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
        PlatformAuthService.Principal principal = requirePrincipal(request);
        var items = externalAccess.listKeys(principal);
        return Map.of(
                "items",
                items,
                "count",
                items.size(),
                "can_admin_audit",
                "PLATFORM_ADMIN".equals(principal.role()));
    }

    @PostMapping("/keys")
    public Map<String, Object> createKey(
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        List<String> capabilities =
                body.containsKey("capabilities")
                        ? stringList(body.get("capabilities"))
                        : List.of("chat", "stream");
        return Map.of(
                "item",
                externalAccess.createKey(
                        requirePrincipal(request),
                        string(body.get("name")),
                        string(body.get("expires_at")),
                        stringList(body.get("allowed_agent_ids")),
                        capabilities,
                        integer(body.get("rate_limit_per_minute"))));
    }

    @PostMapping("/keys/{keyId}/revoke")
    public Map<String, Object> revokeKey(
            @PathVariable("keyId") String keyId, ServerHttpRequest request) {
        externalAccess.revokeKey(keyId, requirePrincipal(request));
        return Map.of("ok", true, "key_id", keyId);
    }

    @PostMapping("/keys/{keyId}/rotate")
    public Map<String, Object> rotateKey(
            @PathVariable("keyId") String keyId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        return Map.of(
                "item",
                externalAccess.rotateKey(
                        keyId,
                        requirePrincipal(request),
                        integer(body.get("grace_minutes"))));
    }

    @GetMapping("/invocations")
    public Map<String, Object> invocations(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            ServerHttpRequest request) {
        var items = externalAccess.invocationHistory(requirePrincipal(request), limit);
        return Map.of("items", items, "count", items.size());
    }

    @GetMapping("/admin/overview")
    public Map<String, Object> adminOverview(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            ServerHttpRequest request) {
        PlatformAuthService.Principal principal = requirePrincipal(request);
        auth.requireAdmin(principal);
        return externalAccess.adminOverview(principal, limit);
    }

    @GetMapping("/admin/keys")
    public Map<String, Object> adminKeys(
            @RequestParam(name = "query", defaultValue = "") String query,
            @RequestParam(name = "status", defaultValue = "ALL") String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            ServerHttpRequest request) {
        PlatformAuthService.Principal principal = requirePrincipal(request);
        auth.requireAdmin(principal);
        var items = externalAccess.adminListKeys(principal, query, status, limit);
        return Map.of("items", items, "count", items.size());
    }

    @PostMapping("/admin/keys/{keyId}/revoke")
    public Map<String, Object> adminRevokeKey(
            @PathVariable("keyId") String keyId, ServerHttpRequest request) {
        PlatformAuthService.Principal principal = requirePrincipal(request);
        auth.requireAdmin(principal);
        externalAccess.adminRevokeKey(keyId, principal);
        return Map.of("ok", true, "key_id", keyId);
    }

    @PostMapping("/admin/keys/{keyId}/suspend")
    public Map<String, Object> adminSuspendKey(
            @PathVariable("keyId") String keyId, ServerHttpRequest request) {
        PlatformAuthService.Principal principal = requirePrincipal(request);
        auth.requireAdmin(principal);
        externalAccess.adminSetKeySuspended(keyId, principal, true);
        return Map.of("ok", true, "key_id", keyId, "status", "SUSPENDED");
    }

    @PostMapping("/admin/keys/{keyId}/resume")
    public Map<String, Object> adminResumeKey(
            @PathVariable("keyId") String keyId, ServerHttpRequest request) {
        PlatformAuthService.Principal principal = requirePrincipal(request);
        auth.requireAdmin(principal);
        externalAccess.adminSetKeySuspended(keyId, principal, false);
        return Map.of("ok", true, "key_id", keyId, "status", "ACTIVE");
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

    private static List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : iterable) {
            String normalized = string(item);
            if (!normalized.isBlank()) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static Integer integer(Object value) {
        if (value == null || string(value).isBlank()) return null;
        try {
            return Integer.valueOf(string(value));
        } catch (NumberFormatException error) {
            throw new PlatformAuthService.AuthException(400, "每分钟调用上限必须是整数");
        }
    }
}
