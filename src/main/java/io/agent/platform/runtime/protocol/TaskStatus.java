/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

public enum TaskStatus {
    COMPLETED,
    FAILED,
    CANCELLED,
    WAITING,
    TIMEOUT,
    REJECTED
}
