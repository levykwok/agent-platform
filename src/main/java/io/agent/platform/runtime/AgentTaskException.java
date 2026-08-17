/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import io.agent.platform.runtime.protocol.AgentTaskEnvelope;

/** Runtime failure that preserves the v1 task envelope for callers and observability adapters. */
public final class AgentTaskException extends AgentRuntimeException {

    private final AgentTaskEnvelope task;

    public AgentTaskException(String message, AgentTaskEnvelope task, Throwable cause) {
        super(message, cause);
        this.task = task;
    }

    public AgentTaskEnvelope task() {
        return task;
    }
}
