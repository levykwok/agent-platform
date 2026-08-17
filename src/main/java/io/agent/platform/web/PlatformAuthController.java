/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public account application and session endpoints. */
@RestController
@RequestMapping("/platform/auth")
public class PlatformAuthController {

    private static final String COOKIE = "platform_session";
    private final PlatformAuthService auth;

    public PlatformAuthController(PlatformAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody Map<String, Object> payload) {
        return execute(() -> auth.apply(payload));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, Object> payload, ServerHttpResponse response) {
        ResponseEntity<Map<String, Object>> result =
                execute(() -> auth.login(value(payload, "email"), value(payload, "password")));
        if (result.getStatusCode().is2xxSuccessful()) {
            String token = String.valueOf(result.getBody().get("session_token"));
            addSessionCookie(response, token);
            Map<String, Object> body = new LinkedHashMap<>(result.getBody());
            body.remove("session_token");
            return ResponseEntity.ok(body);
        }
        return result;
    }

    @PostMapping("/setup-password")
    public ResponseEntity<Map<String, Object>> setupPassword(
            @RequestBody Map<String, Object> payload, ServerHttpResponse response) {
        ResponseEntity<Map<String, Object>> result =
                execute(
                        () ->
                                auth.setupPassword(
                                        value(payload, "token"),
                                        value(payload, "password"),
                                        value(payload, "display_name")));
        if (result.getStatusCode().is2xxSuccessful()) {
            String token = String.valueOf(result.getBody().get("session_token"));
            addSessionCookie(response, token);
            Map<String, Object> body = new LinkedHashMap<>(result.getBody());
            body.remove("session_token");
            return ResponseEntity.ok(body);
        }
        return result;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(ServerHttpRequest request) {
        PlatformAuthService.Principal principal = auth.current(cookie(request));
        if (principal == null) return response(401, "未登录");
        return ResponseEntity.ok(
                map(
                        "ok", true,
                        "user_id", principal.userId(),
                        "email", principal.email(),
                        "display_name", principal.displayName(),
                        "org_id", principal.orgId(),
                        "role", principal.role()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(ServerHttpRequest request, ServerHttpResponse response) {
        auth.logout(cookie(request));
        response.addCookie(ResponseCookie.from(COOKIE, "").path("/").maxAge(0).httpOnly(true).build());
        return ResponseEntity.ok(map("ok", true));
    }

    private void addSessionCookie(ServerHttpResponse response, String token) {
        response.addCookie(
                ResponseCookie.from(COOKIE, token)
                        .httpOnly(true)
                        .secure(auth.secureCookie())
                        .sameSite("Lax")
                        .path("/")
                        .maxAge(java.time.Duration.ofDays(7))
                        .build());
    }

    static String cookie(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst(COOKIE);
        return cookie == null ? "" : cookie.getValue();
    }

    private ResponseEntity<Map<String, Object>> execute(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (PlatformAuthService.AuthException error) {
            return response(error.status(), error.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> response(int status, String detail) {
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(map("ok", false, "detail", detail));
    }

    private static String value(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
