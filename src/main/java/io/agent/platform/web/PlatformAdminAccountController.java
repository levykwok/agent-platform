/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin-only account application review endpoints. */
@RestController
@RequestMapping("/platform/admin/accounts")
public class PlatformAdminAccountController {

    private final PlatformAuthService auth;

    public PlatformAdminAccountController(PlatformAuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/applications")
    public ResponseEntity<Map<String, Object>> applications(
            @RequestParam(name = "query", defaultValue = "") String query,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            ServerHttpRequest request) {
        try {
            var principal = auth.current(PlatformAuthController.cookie(request));
            return ResponseEntity.ok(
                    auth.applicationPage(principal, query, status, limit, offset));
        } catch (PlatformAuthService.AuthException error) {
            return error(error);
        }
    }

    @PostMapping("/applications/{applicationId}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable("applicationId") String applicationId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        try {
            var principal = auth.current(PlatformAuthController.cookie(request));
            return ResponseEntity.ok(auth.approve(principal, applicationId, payload == null ? Map.of() : payload));
        } catch (PlatformAuthService.AuthException error) {
            return error(error);
        }
    }

    @PostMapping("/applications/{applicationId}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable("applicationId") String applicationId,
            @RequestBody(required = false) Map<String, Object> payload,
            ServerHttpRequest request) {
        try {
            var principal = auth.current(PlatformAuthController.cookie(request));
            return ResponseEntity.ok(auth.reject(principal, applicationId, payload == null ? Map.of() : payload));
        } catch (PlatformAuthService.AuthException error) {
            return error(error);
        }
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> users(
            @RequestParam(name = "query", defaultValue = "") String query,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            ServerHttpRequest request) {
        return execute(
                request,
                principal -> auth.users(principal, query, status, limit, offset));
    }

    @PatchMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable("userId") String userId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        return execute(request, principal -> auth.updateUser(principal, userId, payload));
    }

    @PutMapping("/users/{userId}/membership")
    public ResponseEntity<Map<String, Object>> setMembership(
            @PathVariable("userId") String userId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        return execute(request, principal -> auth.setMembership(principal, userId, payload));
    }

    @PostMapping("/users/{userId}/revoke-sessions")
    public ResponseEntity<Map<String, Object>> revokeSessions(
            @PathVariable("userId") String userId, ServerHttpRequest request) {
        return execute(request, principal -> auth.revokeUserSessions(principal, userId));
    }

    @GetMapping("/users/{userId}/sessions")
    public ResponseEntity<Map<String, Object>> sessions(
            @PathVariable("userId") String userId, ServerHttpRequest request) {
        return execute(request, principal -> auth.userSessions(principal, userId));
    }

    @DeleteMapping("/users/{userId}/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> revokeSession(
            @PathVariable("userId") String userId,
            @PathVariable("sessionId") String sessionId,
            ServerHttpRequest request) {
        return execute(
                request, principal -> auth.revokeUserSession(principal, userId, sessionId));
    }

    @PostMapping("/users/{userId}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @PathVariable("userId") String userId, ServerHttpRequest request) {
        return execute(request, principal -> auth.resetUserPassword(principal, userId));
    }

    @GetMapping("/organizations")
    public ResponseEntity<Map<String, Object>> organizations(ServerHttpRequest request) {
        return execute(
                request,
                principal -> {
                    var rows = auth.organizations(principal);
                    return map("items", rows, "organizations", rows, "total", rows.size());
                });
    }

    @PostMapping("/organizations")
    public ResponseEntity<Map<String, Object>> createOrganization(
            @RequestBody Map<String, Object> payload, ServerHttpRequest request) {
        return execute(request, principal -> auth.createOrganization(principal, payload));
    }

    @PatchMapping("/organizations/{orgId}")
    public ResponseEntity<Map<String, Object>> updateOrganization(
            @PathVariable("orgId") String orgId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        return execute(request, principal -> auth.updateOrganization(principal, orgId, payload));
    }

    @GetMapping("/email-outbox")
    public ResponseEntity<Map<String, Object>> emailOutbox(
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            ServerHttpRequest request) {
        return execute(
                request,
                principal -> auth.emailOutbox(principal, status, limit, offset));
    }

    @PostMapping("/email-outbox/{emailId}/retry")
    public ResponseEntity<Map<String, Object>> retryEmail(
            @PathVariable("emailId") String emailId, ServerHttpRequest request) {
        return execute(request, principal -> auth.retryEmail(principal, emailId));
    }

    private ResponseEntity<Map<String, Object>> execute(
            ServerHttpRequest request,
            java.util.function.Function<PlatformAuthService.Principal, Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(
                    action.apply(auth.current(PlatformAuthController.cookie(request))));
        } catch (PlatformAuthService.AuthException error) {
            return error(error);
        }
    }

    private ResponseEntity<Map<String, Object>> error(PlatformAuthService.AuthException error) {
        return ResponseEntity.status(HttpStatus.valueOf(error.status())).body(map("ok", false, "detail", error.getMessage()));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
