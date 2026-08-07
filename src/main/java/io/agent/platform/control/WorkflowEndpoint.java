/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

/** Identifies one port on one workflow node. */
public record WorkflowEndpoint(String nodeId, String portId) {

    public WorkflowEndpoint {
        nodeId = nodeId == null ? "" : nodeId.trim();
        portId = portId == null ? "" : portId.trim();
    }
}
