/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public ResponseEntity<Map<String, Object>> applications(ServerHttpRequest request) {
        try {
            var principal = auth.current(PlatformAuthController.cookie(request));
            return ResponseEntity.ok(map("items", auth.applications(principal)));
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

    private ResponseEntity<Map<String, Object>> error(PlatformAuthService.AuthException error) {
        return ResponseEntity.status(HttpStatus.valueOf(error.status())).body(map("ok", false, "detail", error.getMessage()));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
