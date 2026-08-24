/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

public class RootTaskBudgetExceededException extends AgentRuntimeException {
    public RootTaskBudgetExceededException(String message) {
        super(message);
    }
}
