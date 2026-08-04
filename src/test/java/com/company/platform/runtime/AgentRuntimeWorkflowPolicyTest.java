/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.platform.control.WorkflowStep;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentRuntimeWorkflowPolicyTest {

    @Test
    void retriesUntilTheStepSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowStep step = step(2, WorkflowStep.FailurePolicy.FAIL_FAST);

        String result =
                AgentRuntimeService.withStepPolicy(
                                step,
                                "input",
                                Mono.defer(
                                        () ->
                                                attempts.incrementAndGet() < 3
                                                        ? Mono.error(
                                                                new IllegalStateException(
                                                                        "try again"))
                                                        : Mono.just("output")))
                        .block();

        assertEquals("output", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void failFastPropagatesAfterRetryBudgetIsExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowStep step = step(1, WorkflowStep.FailurePolicy.FAIL_FAST);

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                AgentRuntimeService.withStepPolicy(
                                                step,
                                                "input",
                                                Mono.defer(
                                                        () -> {
                                                            attempts.incrementAndGet();
                                                            return Mono.error(
                                                                    new IllegalStateException(
                                                                            "broken"));
                                                        }))
                                        .block());

        assertEquals("Retries exhausted: 1/1", error.getMessage());
        assertEquals(2, attempts.get());
    }

    @Test
    void timeoutIsRetriedAndThenUsesPreviousInputWhenConfigured() {
        WorkflowStep step =
                new WorkflowStep(
                        "step", "agent", null, 20L, 1, WorkflowStep.FailurePolicy.USE_INPUT);

        String result = AgentRuntimeService.withStepPolicy(step, "previous", Mono.never()).block();

        assertEquals("previous", result);
    }

    @Test
    void skipUsesPreviousInputWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowStep step = step(3, WorkflowStep.FailurePolicy.SKIP);

        String result =
                AgentRuntimeService.withStepPolicy(
                                step,
                                "previous",
                                Mono.defer(
                                        () -> {
                                            attempts.incrementAndGet();
                                            return Mono.error(
                                                    new IllegalArgumentException("bad input"));
                                        }))
                        .block();

        assertEquals("previous", result);
        assertEquals(4, attempts.get());
    }

    @Test
    void streamedFallbackEmitsPreviousInputAndFailureMetadata() {
        WorkflowStep step = step(0, WorkflowStep.FailurePolicy.USE_INPUT);

        AgentEventEnvelope event =
                AgentRuntimeService.withFluxStepPolicy(
                                step,
                                "previous",
                                Mono.<AgentEventEnvelope>error(new IllegalStateException("broken"))
                                        .flux())
                        .single()
                        .block();

        assertEquals("previous", event.delta());
        assertEquals("workflow_step_fallback", event.type());
        assertEquals(true, event.payload().get("fallback"));
    }

    @Test
    void timeoutExceptionCanBeObservedWhenFailFastIsSelected() {
        WorkflowStep step =
                new WorkflowStep(
                        "step", "agent", null, 20L, 0, WorkflowStep.FailurePolicy.FAIL_FAST);

        Throwable error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                AgentRuntimeService.withStepPolicy(step, "input", Mono.never())
                                        .block());

        assertInstanceOf(TimeoutException.class, error.getCause());
    }

    private static WorkflowStep step(int retries, WorkflowStep.FailurePolicy policy) {
        return new WorkflowStep("step", "agent", null, null, retries, policy);
    }
}
