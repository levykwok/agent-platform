/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a stable, run-level timing breakdown from persisted runtime events and LLM usage. */
final class RunTimingAnalyzer {

    private static final long MIN_GAP_MS = 50L;

    private RunTimingAnalyzer() {}

    static Map<String, Object> analyze(
            Map<String, Object> run,
            List<Map<String, Object>> events,
            List<Map<String, Object>> llmCalls) {
        Map<String, Object> safeRun = run == null ? Map.of() : run;
        List<EventPoint> points = eventPoints(events);
        Instant created = instant(safeRun.get("created_at"));
        Instant started = instant(safeRun.get("started_at"));
        Instant finished = instant(safeRun.get("finished_at"));
        if (created == null) created = started;
        if (started == null) started = created;
        if (started == null && !points.isEmpty()) started = points.get(0).at();
        if (created == null) created = started;
        if (finished == null && terminal(safeRun) && !points.isEmpty()) {
            finished = points.get(points.size() - 1).at();
        }
        Instant observedEnd = finished == null ? Instant.now() : finished;
        if (started == null) started = observedEnd;
        if (created == null) created = started;
        Instant runStarted = started;
        Instant runEnd = observedEnd;

        List<Phase> phases = phases(points, observedEnd);
        phases.sort(Comparator.comparing(Phase::startedAt).thenComparing(Phase::id));
        List<Interval> coverage =
                mergeIntervals(
                        phases.stream()
                                .filter(phase -> phase.explainsRuntime() && !phase.running())
                                .map(phase -> clamp(phase.interval(), runStarted, runEnd))
                                .filter(interval -> interval != null && interval.durationMs() > 0)
                                .toList());
        long executionMs = duration(started, observedEnd);
        long queueMs = duration(created, started);
        long explainedMs = coverage.stream().mapToLong(Interval::durationMs).sum();
        long unexplainedMs = Math.max(0L, executionMs - explainedMs);
        List<Map<String, Object>> gaps = gaps(started, observedEnd, coverage, phases);
        Instant lastPhaseEnd =
                phases.stream()
                        .filter(phase -> !phase.running())
                        .map(Phase::finishedAt)
                        .max(Comparator.naturalOrder())
                        .orElse(started);
        long finalizationMs = Math.max(0L, duration(lastPhaseEnd, observedEnd));
        Map<String, Object> usage = usage(llmCalls);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("decision_ms", categoryDuration(phases, "decision", true));
        summary.put("model_sum_ms", categoryDuration(phases, "model", false));
        summary.put("model_critical_path_ms", categoryCriticalPath(phases, "model"));
        summary.put("tool_sum_ms", categoryDuration(phases, "tool", false));
        summary.put("agent_sum_ms", categoryDuration(phases, "agent", false));
        summary.put("pipeline_step_sum_ms", categoryDuration(phases, "pipeline_step", false));
        summary.put("parallel_group_sum_ms", categoryDuration(phases, "parallel_group", true));
        summary.put("parallel_savings_ms", parallelSavings(phases));
        summary.putAll(usage);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contract_version", "agent.run.timing.v1");
        result.put("run_id", text(safeRun.get("run_id")));
        result.put("status", text(safeRun.get("status")));
        result.put("created_at", created.toString());
        result.put("started_at", started.toString());
        result.put("finished_at", finished == null ? "" : finished.toString());
        result.put("observed_at", observedEnd.toString());
        result.put("total_ms", duration(created, observedEnd));
        result.put("execution_ms", executionMs);
        result.put("queue_ms", queueMs);
        result.put("explained_ms", explainedMs);
        result.put("unexplained_ms", unexplainedMs);
        result.put(
                "coverage_ratio",
                executionMs == 0L ? 1D : Math.min(1D, (double) explainedMs / executionMs));
        result.put("finalization_ms", finalizationMs);
        result.put("summary", Map.copyOf(summary));
        result.put("phases", phases.stream().map(Phase::contract).toList());
        result.put("gaps", gaps);
        result.put("llm_calls", llmCalls == null ? List.of() : List.copyOf(llmCalls));
        return Map.copyOf(result);
    }

    private static List<Phase> phases(List<EventPoint> points, Instant observedEnd) {
        Map<String, Deque<EventPoint>> starts = new LinkedHashMap<>();
        List<Phase> phases = new ArrayList<>();
        int sequence = 0;
        for (EventPoint point : points) {
            PhaseType startType = startType(point.type());
            if (startType != null) {
                starts.computeIfAbsent(key(startType, point), ignored -> new ArrayDeque<>())
                        .addLast(point);
                continue;
            }
            PhaseType endType = endType(point.type());
            if (endType == null) continue;
            String key = key(endType, point);
            EventPoint start = pollStart(starts, key, endType);
            long reported = number(point.payload().get("duration_ms"), -1L);
            Instant startAt = start == null ? null : start.at();
            if (startAt == null && reported >= 0L) startAt = point.at().minusMillis(reported);
            if (startAt == null) continue;
            phases.add(
                    phase(
                            ++sequence,
                            endType,
                            start,
                            point,
                            startAt,
                            point.at(),
                            reported,
                            false));
        }
        for (Map.Entry<String, Deque<EventPoint>> entry : starts.entrySet()) {
            while (!entry.getValue().isEmpty()) {
                EventPoint start = entry.getValue().pollFirst();
                PhaseType type = startType(start.type());
                if (type == null) continue;
                phases.add(
                        phase(
                                ++sequence,
                                type,
                                start,
                                null,
                                start.at(),
                                observedEnd,
                                -1L,
                                true));
            }
        }
        return phases;
    }

    private static EventPoint pollStart(
            Map<String, Deque<EventPoint>> starts, String exactKey, PhaseType type) {
        Deque<EventPoint> exact = starts.get(exactKey);
        if (exact != null && !exact.isEmpty()) return exact.pollFirst();
        return starts.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(type.kind() + "|"))
                .filter(entry -> !entry.getValue().isEmpty())
                .min(Comparator.comparing(entry -> entry.getValue().peekFirst().at()))
                .map(entry -> entry.getValue().pollFirst())
                .orElse(null);
    }

    private static Phase phase(
            int sequence,
            PhaseType type,
            EventPoint start,
            EventPoint end,
            Instant startedAt,
            Instant finishedAt,
            long reportedDurationMs,
            boolean running) {
        Map<String, Object> payload =
                end == null
                        ? start == null ? Map.of() : start.payload()
                        : merge(start == null ? Map.of() : start.payload(), end.payload());
        String agentId = first(payload, "agent_id", "target_agent_id", "source_agent_id");
        if (agentId.isBlank()) {
            agentId = end != null ? end.source() : start == null ? "" : start.source();
        }
        String stepId = first(payload, "step_id", "binding_id", "step");
        String parallelGroup = first(payload, "parallel_group");
        String label = label(type, agentId, stepId, parallelGroup);
        return new Phase(
                type.kind() + "_" + sequence,
                type.kind(),
                label,
                agentId,
                stepId,
                parallelGroup,
                startedAt,
                finishedAt,
                duration(startedAt, finishedAt),
                reportedDurationMs,
                running,
                type.explainsRuntime());
    }

    private static String label(
            PhaseType type, String agentId, String stepId, String parallelGroup) {
        String target = !stepId.isBlank() ? stepId : agentId;
        return switch (type) {
            case ROUTER_DECISION -> "Router LLM 决策";
            case SUPERVISOR_PLAN -> "Supervisor PLAN";
            case SUPERVISOR_REVISE -> "Supervisor REVISE";
            case MODEL -> "模型调用" + suffix(target);
            case AGENT -> "Agent 执行" + suffix(agentId);
            case PIPELINE_STEP -> "Pipeline 步骤" + suffix(target);
            case PARALLEL_GROUP -> "并行组" + suffix(parallelGroup);
            case SUPERVISOR_STEP -> "Supervisor 子 Agent" + suffix(target);
            case TOOL -> "工具调用" + suffix(target);
        };
    }

    private static String suffix(String value) {
        return value == null || value.isBlank() ? "" : " · " + value;
    }

    private static PhaseType startType(String type) {
        return switch (type) {
            case "router_decision_start" -> PhaseType.ROUTER_DECISION;
            case "supervisor_plan_start" -> PhaseType.SUPERVISOR_PLAN;
            case "supervisor_revise_start" -> PhaseType.SUPERVISOR_REVISE;
            case "model_call_start" -> PhaseType.MODEL;
            case "agent_start" -> PhaseType.AGENT;
            case "pipeline_step_start", "pipeline_final_step" -> PhaseType.PIPELINE_STEP;
            case "pipeline_parallel_start" -> PhaseType.PARALLEL_GROUP;
            case "supervisor_step_start" -> PhaseType.SUPERVISOR_STEP;
            case "tool_call_start" -> PhaseType.TOOL;
            default -> null;
        };
    }

    private static PhaseType endType(String type) {
        return switch (type) {
            case "router_decision" -> PhaseType.ROUTER_DECISION;
            case "supervisor_plan" -> PhaseType.SUPERVISOR_PLAN;
            case "supervisor_revise" -> PhaseType.SUPERVISOR_REVISE;
            case "model_call_end" -> PhaseType.MODEL;
            case "agent_end" -> PhaseType.AGENT;
            case "pipeline_step_end", "pipeline_result" -> PhaseType.PIPELINE_STEP;
            case "pipeline_parallel_end" -> PhaseType.PARALLEL_GROUP;
            case "supervisor_subagent_result" -> PhaseType.SUPERVISOR_STEP;
            case "tool_result_end", "tool_call_end" -> PhaseType.TOOL;
            default -> null;
        };
    }

    private static String key(PhaseType type, EventPoint point) {
        Map<String, Object> payload = point.payload();
        String discriminator =
                switch (type) {
                    case ROUTER_DECISION, SUPERVISOR_PLAN, SUPERVISOR_REVISE -> type.kind();
                    case MODEL, AGENT ->
                            first(payload, "agent_id", "source_agent_id", "target_agent_id");
                    case PIPELINE_STEP -> first(payload, "step_id", "agent_id", "source_agent_id");
                    case PARALLEL_GROUP -> first(payload, "parallel_group");
                    case SUPERVISOR_STEP -> first(payload, "step", "binding_id", "target_agent_id");
                    case TOOL -> first(payload, "tool_call_id", "tool_id", "tool_name");
                };
        if (discriminator.isBlank()) discriminator = point.source();
        return type.kind() + "|" + discriminator;
    }

    private static List<EventPoint> eventPoints(List<Map<String, Object>> events) {
        if (events == null) return List.of();
        List<EventPoint> points = new ArrayList<>();
        for (Map<String, Object> event : events) {
            if (event == null) continue;
            Instant at = instant(event.get("created_at"));
            if (at == null) continue;
            String type = text(event.getOrDefault("event_type", event.get("type"))).toLowerCase(Locale.ROOT);
            points.add(
                    new EventPoint(
                            type,
                            at,
                            objectMap(event.get("payload")),
                            text(event.get("source"))));
        }
        points.sort(Comparator.comparing(EventPoint::at));
        return List.copyOf(points);
    }

    private static List<Map<String, Object>> gaps(
            Instant started,
            Instant finished,
            List<Interval> coverage,
            List<Phase> phases) {
        List<Map<String, Object>> result = new ArrayList<>();
        Instant cursor = started;
        int index = 0;
        for (Interval interval : coverage) {
            if (interval.startedAt().isAfter(cursor)) {
                addGap(result, ++index, cursor, interval.startedAt(), phases);
            }
            if (interval.finishedAt().isAfter(cursor)) cursor = interval.finishedAt();
        }
        if (finished.isAfter(cursor)) addGap(result, ++index, cursor, finished, phases);
        return List.copyOf(result);
    }

    private static void addGap(
            List<Map<String, Object>> result,
            int index,
            Instant started,
            Instant finished,
            List<Phase> phases) {
        long duration = duration(started, finished);
        if (duration < MIN_GAP_MS) return;
        String after =
                phases.stream()
                        .filter(phase -> !phase.finishedAt().isAfter(started))
                        .max(Comparator.comparing(Phase::finishedAt))
                        .map(Phase::label)
                        .orElse("运行开始");
        String before =
                phases.stream()
                        .filter(phase -> !phase.startedAt().isBefore(finished))
                        .min(Comparator.comparing(Phase::startedAt))
                        .map(Phase::label)
                        .orElse("运行结束");
        result.add(
                Map.of(
                        "id", "gap_" + index,
                        "started_at", started.toString(),
                        "finished_at", finished.toString(),
                        "duration_ms", duration,
                        "after", after,
                        "before", before));
    }

    private static long categoryDuration(
            List<Phase> phases, String category, boolean preferReported) {
        return phases.stream()
                .filter(phase -> category.equals(phase.kind()) && !phase.running())
                .mapToLong(
                        phase ->
                                preferReported && phase.reportedDurationMs() >= 0L
                                        ? phase.reportedDurationMs()
                                        : phase.durationMs())
                .sum();
    }

    private static long categoryCriticalPath(List<Phase> phases, String category) {
        return mergeIntervals(
                        phases.stream()
                                .filter(phase -> category.equals(phase.kind()) && !phase.running())
                                .map(Phase::interval)
                                .toList())
                .stream()
                .mapToLong(Interval::durationMs)
                .sum();
    }

    private static long parallelSavings(List<Phase> phases) {
        long savings = 0L;
        for (Phase group : phases) {
            if (!"parallel_group".equals(group.kind()) || group.running()) continue;
            long childModelTime =
                    phases.stream()
                            .filter(phase -> "model".equals(phase.kind()) && !phase.running())
                            .filter(phase -> overlaps(group.interval(), phase.interval()))
                            .mapToLong(Phase::durationMs)
                            .sum();
            long groupTime =
                    group.reportedDurationMs() >= 0L
                            ? group.reportedDurationMs()
                            : group.durationMs();
            savings += Math.max(0L, childModelTime - groupTime);
        }
        return savings;
    }

    private static Map<String, Object> usage(List<Map<String, Object>> llmCalls) {
        long inputTokens = 0L;
        long outputTokens = 0L;
        long totalTokens = 0L;
        double cost = 0D;
        String currency = "USD";
        if (llmCalls != null) {
            for (Map<String, Object> call : llmCalls) {
                inputTokens += number(call.get("input_tokens"), 0L);
                outputTokens += number(call.get("output_tokens"), 0L);
                totalTokens += number(call.get("total_tokens"), 0L);
                cost += decimal(call.get("estimated_cost"));
                if (!text(call.get("currency")).isBlank()) currency = text(call.get("currency"));
            }
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("model_calls", llmCalls == null ? 0 : llmCalls.size());
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("total_tokens", totalTokens == 0L ? inputTokens + outputTokens : totalTokens);
        usage.put("estimated_cost", cost);
        usage.put("currency", currency);
        return Map.copyOf(usage);
    }

    private static List<Interval> mergeIntervals(List<Interval> intervals) {
        if (intervals == null || intervals.isEmpty()) return List.of();
        List<Interval> sorted =
                intervals.stream()
                        .filter(interval -> interval != null && interval.durationMs() >= 0L)
                        .sorted(Comparator.comparing(Interval::startedAt))
                        .toList();
        if (sorted.isEmpty()) return List.of();
        List<Interval> result = new ArrayList<>();
        Instant start = sorted.get(0).startedAt();
        Instant end = sorted.get(0).finishedAt();
        for (int index = 1; index < sorted.size(); index++) {
            Interval current = sorted.get(index);
            if (!current.startedAt().isAfter(end)) {
                if (current.finishedAt().isAfter(end)) end = current.finishedAt();
            } else {
                result.add(new Interval(start, end));
                start = current.startedAt();
                end = current.finishedAt();
            }
        }
        result.add(new Interval(start, end));
        return List.copyOf(result);
    }

    private static Interval clamp(Interval interval, Instant start, Instant end) {
        Instant clampedStart = interval.startedAt().isBefore(start) ? start : interval.startedAt();
        Instant clampedEnd = interval.finishedAt().isAfter(end) ? end : interval.finishedAt();
        return clampedEnd.isBefore(clampedStart) ? null : new Interval(clampedStart, clampedEnd);
    }

    private static boolean overlaps(Interval left, Interval right) {
        return left.startedAt().isBefore(right.finishedAt())
                && right.startedAt().isBefore(left.finishedAt());
    }

    private static boolean terminal(Map<String, Object> run) {
        String status = text(run.get("status")).toLowerCase(Locale.ROOT);
        return List.of("succeeded", "failed", "cancelled", "canceled").contains(status);
    }

    private static Map<String, Object> merge(
            Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (first != null) result.putAll(first);
        if (second != null) result.putAll(second);
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    private static String first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = text(values.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static Instant instant(Object value) {
        try {
            String text = text(value);
            return text.isBlank() ? null : Instant.parse(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long duration(Instant start, Instant end) {
        if (start == null || end == null || end.isBefore(start)) return 0L;
        return Duration.between(start, end).toMillis();
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double decimal(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? 0D : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0D;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private enum PhaseType {
        ROUTER_DECISION("decision", true),
        SUPERVISOR_PLAN("decision", true),
        SUPERVISOR_REVISE("decision", true),
        MODEL("model", true),
        AGENT("agent", false),
        PIPELINE_STEP("pipeline_step", true),
        PARALLEL_GROUP("parallel_group", true),
        SUPERVISOR_STEP("supervisor_step", true),
        TOOL("tool", true);

        private final String kind;
        private final boolean explainsRuntime;

        PhaseType(String kind, boolean explainsRuntime) {
            this.kind = kind;
            this.explainsRuntime = explainsRuntime;
        }

        String kind() {
            return kind;
        }

        boolean explainsRuntime() {
            return explainsRuntime;
        }
    }

    private record EventPoint(
            String type, Instant at, Map<String, Object> payload, String source) {}

    private record Interval(Instant startedAt, Instant finishedAt) {
        long durationMs() {
            return duration(startedAt, finishedAt);
        }
    }

    private record Phase(
            String id,
            String kind,
            String label,
            String agentId,
            String stepId,
            String parallelGroup,
            Instant startedAt,
            Instant finishedAt,
            long durationMs,
            long reportedDurationMs,
            boolean running,
            boolean explainsRuntime) {

        Interval interval() {
            return new Interval(startedAt, finishedAt);
        }

        Map<String, Object> contract() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("kind", kind);
            result.put("label", label);
            result.put("agent_id", agentId);
            result.put("step_id", stepId);
            result.put("parallel_group", parallelGroup);
            result.put("started_at", startedAt.toString());
            result.put("finished_at", finishedAt.toString());
            result.put("duration_ms", durationMs);
            if (reportedDurationMs >= 0L) {
                result.put("reported_duration_ms", reportedDurationMs);
            }
            result.put("running", running);
            result.put("explains_runtime", explainsRuntime);
            return Map.copyOf(result);
        }
    }
}
