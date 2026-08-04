/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TaskContextTest {

    @Test
    void childKeepsRootAndReferencesParent() {
        TaskContext root = TaskContext.root("entry", "workflow");
        TaskContext child = root.child("workflow", "researcher", "research");

        assertNotEquals(root.taskId(), child.taskId());
        assertEquals(root.taskId(), child.parentTaskId());
        assertEquals(root.rootTaskId(), child.rootTaskId());
        assertEquals("researcher", child.targetAgentId());
        assertEquals("research", child.stepId());
    }

    @Test
    void metadataIsImmutableAndCanBeExtended() {
        TaskContext root = TaskContext.root("entry", "agent");
        TaskContext next = root.withMetadata("attempt", 2);

        assertEquals(2, next.metadata().get("attempt"));
        assertEquals(false, root.metadata().containsKey("attempt"));
    }
}
