/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

public record ChatResponse(String agentId, String userId, String sessionId, String text) {}
