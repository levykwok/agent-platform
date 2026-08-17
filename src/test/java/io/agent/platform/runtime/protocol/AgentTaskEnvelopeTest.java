/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentTaskEnvelopeTest {

    @Test
    void completedEnvelopeCarriesStableContractAndTiming() {
        Instant started = Instant.parse("2026-01-01T00:00:00Z");
        Instant finished = Instant.parse("2026-01-01T00:00:00.125Z");
        TaskRequest request =
                new TaskRequest(TaskContext.root("caller", "worker"), Map.of("text", "hello"));
        TaskResult result =
                new TaskResult(
                        request.context().taskId(),
                        TaskStatus.COMPLETED,
                        "done",
                        Map.of(),
                        null,
                        Map.of());

        AgentTaskEnvelope envelope =
                AgentTaskEnvelope.completed(
                        request, result, started, finished, Map.of("agent_id", "worker"));

        assertEquals("agent.task.v1", envelope.contractVersion());
        assertEquals(request.context().taskId(), envelope.taskId());
        assertEquals(125L, envelope.durationMs());
        assertEquals("worker", envelope.metadata().get("agent_id"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> envelope.metadata().put("mutated", true));
    }

    @Test
    void failedEnvelopePreservesStatusAndErrorCode() {
        TaskRequest request =
                new TaskRequest(TaskContext.root("caller", "worker"), Map.of("text", "hello"));
        Instant started = Instant.parse("2026-01-01T00:00:00Z");
        AgentTaskEnvelope envelope =
                AgentTaskEnvelope.failed(
                        request,
                        TaskStatus.TIMEOUT,
                        new RuntimeException("deadline exceeded"),
                        started,
                        started.plusSeconds(1),
                        Map.of());

        assertEquals(TaskStatus.TIMEOUT, envelope.result().status());
        assertEquals("TASK_TIMEOUT", envelope.result().error().code());
        assertEquals("deadline exceeded", envelope.result().error().message());
    }
}
