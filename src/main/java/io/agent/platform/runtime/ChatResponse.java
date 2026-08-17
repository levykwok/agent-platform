/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.runtime.protocol.AgentTaskEnvelope;

public record ChatResponse(
        String agentId,
        String userId,
        String sessionId,
        String text,
        AgentTaskEnvelope task) {

    public ChatResponse(String agentId, String userId, String sessionId, String text) {
        this(agentId, userId, sessionId, text, null);
    }
}
