/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunTimingAnalyzerTest {

    @Test
    void explainsOverlappingParallelCallsAndSurfacesRealGaps() {
        Map<String, Object> timing =
                RunTimingAnalyzer.analyze(
                        Map.of(
                                "run_id", "run-1",
                                "status", "succeeded",
                                "created_at", at(0),
                                "started_at", at(1),
                                "finished_at", at(11)),
                        List.of(
                                event("router_decision_start", 1, Map.of()),
                                event("router_decision", 2, Map.of("duration_ms", 900)),
                                event(
                                        "pipeline_parallel_start",
                                        2,
                                        Map.of("parallel_group", "gather")),
                                event("model_call_start", 2, Map.of("agent_id", "facts")),
                                event("model_call_start", 2.5, Map.of("agent_id", "risks")),
                                event("model_call_end", 6, Map.of("agent_id", "facts")),
                                event("model_call_end", 7, Map.of("agent_id", "risks")),
                                event(
                                        "pipeline_parallel_end",
                                        7,
                                        Map.of(
                                                "parallel_group", "gather",
                                                "duration_ms", 5000)),
                                event(
                                        "pipeline_final_step",
                                        7.2,
                                        Map.of("step_id", "join", "agent_id", "joiner")),
                                event("model_call_start", 7.5, Map.of("agent_id", "joiner")),
                                event("model_call_end", 9.5, Map.of("agent_id", "joiner")),
                                event(
                                        "pipeline_result",
                                        9.7,
                                        Map.of("step_id", "join", "agent_id", "joiner"))),
                        List.of(
                                llm("facts", 100, 20, 0.001D),
                                llm("risks", 110, 25, 0.002D),
                                llm("joiner", 120, 30, 0.003D)));

        assertEquals("agent.run.timing.v1", timing.get("contract_version"));
        assertEquals(11_000L, timing.get("total_ms"));
        assertEquals(10_000L, timing.get("execution_ms"));
        assertEquals(1_000L, timing.get("queue_ms"));
        assertEquals(8_500L, timing.get("explained_ms"));
        assertEquals(1_500L, timing.get("unexplained_ms"));
        assertEquals(1_300L, timing.get("finalization_ms"));
        Map<?, ?> summary = (Map<?, ?>) timing.get("summary");
        assertEquals(900L, summary.get("decision_ms"));
        assertEquals(10_500L, summary.get("model_sum_ms"));
        assertEquals(7_000L, summary.get("model_critical_path_ms"));
        assertEquals(3_500L, summary.get("parallel_savings_ms"));
        assertEquals(3, summary.get("model_calls"));
        assertEquals(330L, summary.get("input_tokens"));
        assertEquals(75L, summary.get("output_tokens"));
        assertEquals(0.006D, (Double) summary.get("estimated_cost"), 0.000001D);
        List<?> gaps = (List<?>) timing.get("gaps");
        assertEquals(2, gaps.size());
        assertTrue(((Number) timing.get("coverage_ratio")).doubleValue() > 0.84D);
    }

    @Test
    void reportsUnfinishedPhaseWithoutPretendingItIsExplained() {
        Map<String, Object> timing =
                RunTimingAnalyzer.analyze(
                        Map.of(
                                "run_id", "run-running",
                                "status", "running",
                                "created_at", at(0),
                                "started_at", at(0)),
                        List.of(event("model_call_start", 1, Map.of("agent_id", "slow"))),
                        List.of());

        List<?> phases = (List<?>) timing.get("phases");
        assertEquals(1, phases.size());
        assertEquals(true, ((Map<?, ?>) phases.get(0)).get("running"));
        assertEquals(0L, timing.get("explained_ms"));
    }

    @Test
    void countsSupervisorParallelGroupSavings() {
        Map<String, Object> timing =
                RunTimingAnalyzer.analyze(
                        Map.of(
                                "run_id", "supervisor-parallel",
                                "status", "succeeded",
                                "created_at", at(0),
                                "started_at", at(0),
                                "finished_at", at(8)),
                        List.of(
                                event(
                                        "supervisor_parallel_start",
                                        1,
                                        Map.of("parallel_group", "g1")),
                                event("model_call_start", 1, Map.of("agent_id", "analysis")),
                                event("model_call_start", 1.2, Map.of("agent_id", "format")),
                                event("model_call_end", 4, Map.of("agent_id", "analysis")),
                                event("model_call_end", 5, Map.of("agent_id", "format")),
                                event(
                                        "supervisor_parallel_end",
                                        5,
                                        Map.of("parallel_group", "g1", "duration_ms", 4000))),
                        List.of());

        Map<?, ?> summary = (Map<?, ?>) timing.get("summary");
        assertEquals(2_800L, summary.get("parallel_savings_ms"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> phases = (List<Map<String, Object>>) timing.get("phases");
        assertTrue(
                phases.stream()
                        .anyMatch(
                                phase ->
                                        "parallel_group".equals(phase.get("kind"))
                                                && "g1".equals(phase.get("parallel_group"))));
    }

    @Test
    void pairsLegacyEventsByTypeWhenTheirDiscriminatorsAreMissing() {
        Instant started = Instant.parse("2026-01-01T00:00:00Z");
        Map<String, Object> run =
                Map.of(
                        "run_id", "legacy-run",
                        "status", "succeeded",
                        "created_at", started.toString(),
                        "started_at", started.toString(),
                        "finished_at", started.plusSeconds(2).toString());
        List<Map<String, Object>> events =
                List.of(
                        event(
                                "pipeline_step_start",
                                0.1,
                                Map.of("summary", "legacy start")),
                        event(
                                "pipeline_step_end",
                                1.1,
                                Map.of("step_id", "facts", "agent_id", "facts-agent")));

        Map<String, Object> result = RunTimingAnalyzer.analyze(run, events, List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> phases = (List<Map<String, Object>>) result.get("phases");
        assertEquals(1, phases.size());
        assertEquals("facts", phases.get(0).get("step_id"));
        assertEquals(1_000L, phases.get(0).get("duration_ms"));
        assertEquals(false, phases.get(0).get("running"));
    }

    private static Map<String, Object> event(
            String type, double second, Map<String, Object> payload) {
        return Map.of(
                "event_type", type,
                "created_at", at(second),
                "payload", payload);
    }

    private static Map<String, Object> llm(
            String agentId, long input, long output, double cost) {
        return Map.of(
                "agent_id", agentId,
                "input_tokens", input,
                "output_tokens", output,
                "total_tokens", input + output,
                "estimated_cost", cost,
                "currency", "USD");
    }

    private static String at(double second) {
        long millis = Math.round(second * 1000D);
        return java.time.Instant.parse("2026-01-01T00:00:00Z")
                .plusMillis(millis)
                .toString();
    }
}
