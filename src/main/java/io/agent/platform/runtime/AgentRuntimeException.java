/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

public class AgentRuntimeException extends RuntimeException {
    public AgentRuntimeException(String message) {
        super(message);
    }

    public AgentRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
