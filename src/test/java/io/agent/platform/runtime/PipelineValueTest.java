/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agent.platform.runtime.protocol.AgentBusinessResult;
import io.agent.platform.runtime.protocol.AgentTaskEnvelope;
import io.agent.platform.runtime.protocol.TaskContext;
import io.agent.platform.runtime.protocol.TaskRequest;
import io.agent.platform.runtime.protocol.TaskResult;
import io.agent.platform.runtime.protocol.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineValueTest {

    @Test
    void usesTypedDataForTransitionsAndPreservesArtifacts() {
        TaskContext context = TaskContext.root("pipeline", "producer");
        TaskRequest request = new TaskRequest(context, Map.of("text", "input"));
        TaskResult result =
                new TaskResult(
                        context.taskId(),
                        TaskStatus.COMPLETED,
                        "raw",
                        Map.of("answer", 42, "pipeline_status", "needs_review"),
                        "typed summary",
                        List.of(Map.of("name", "report.md")),
                        null,
                        Map.of());
        AgentTaskEnvelope task =
                AgentTaskEnvelope.completed(
                        request, result, Instant.now(), Instant.now(), Map.of());

        PipelineValue value =
                PipelineValue.fromResponse(
                        new ChatResponse("producer", "user", "session", "typed summary", task));

        assertEquals("needs_review", value.transitionStatus());
        assertEquals(42, ((Map<?, ?>) value.result().data()).get("answer"));
        assertEquals("report.md", value.result().artifacts().get(0).get("name"));
        assertEquals(PipelineValue.VERSION, value.contract().get("contract_version"));
        assertEquals(
                AgentBusinessResult.VERSION,
                ((Map<?, ?>) value.contract().get("result")).get("contract_version"));
    }
}
