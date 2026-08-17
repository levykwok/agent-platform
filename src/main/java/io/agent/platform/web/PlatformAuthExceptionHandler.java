/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts authentication and authorization failures into stable API responses. */
@RestControllerAdvice
public class PlatformAuthExceptionHandler {

    @ExceptionHandler(PlatformAuthService.AuthException.class)
    public ResponseEntity<Map<String, Object>> authFailure(
            PlatformAuthService.AuthException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("detail", error.getMessage());
        return ResponseEntity.status(error.status()).body(body);
    }
}
