/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentEventEnvelope(
        String id,
        String type,
        String createdAt,
        String source,
        String delta,
        Map<String, Object> payload) {}
