/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.runtime.AgentEventEnvelope;
import io.agent.platform.runtime.AgentRuntime;
import io.agent.platform.runtime.ChatRequest;
import io.agent.platform.runtime.protocol.TaskContext;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Runs and persists deterministic assertions against real orchestration event traces. */
@Service
public class OrchestrationEvaluationService {

    static final String TABLE = "platform_orchestration_evaluations";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private final PlatformStorageLayer storage;
    private final AgentRuntime runtime;

    public OrchestrationEvaluationService(PlatformStorageLayer storage, AgentRuntime runtime) {
        this.storage = storage;
        this.runtime = runtime;
    }

    @PostConstruct
    void initialize() {
        storage.initializeSqliteSchema(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE
                        + " (evaluation_id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, org_id TEXT NOT NULL,"
                        + " user_id TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_platform_orchestration_eval_agent ON "
                        + TABLE
                        + " (agent_id, created_at)");
    }

    public Mono<Map<String, Object>> run(
            String agentId,
            PlatformAuthService.Principal principal,
            Map<String, Object> request) {
        List<Map<String, Object>> cases = cases(request.get("cases"));
        if (cases.isEmpty()) {
            return Mono.error(
                    new IllegalArgumentException("评测 cases 不能为空，至少提供一个 input/expected 用例"));
        }
        if (cases.size() > 50) {
            return Mono.error(new IllegalArgumentException("单次评测最多支持 50 个用例"));
        }
        String evaluationId = "oeval_" + UUID.randomUUID().toString().replace("-", "");
        Instant startedAt = Instant.now();
        return Flux.fromIterable(cases)
                .index()
                .concatMap(
                        indexed ->
                                evaluateCase(
                                                evaluationId,
                                                indexed.getT1().intValue() + 1,
                                                agentId,
                                                principal,
                                                indexed.getT2())
                                        .onErrorResume(
                                                error ->
                                                        Mono.just(
                                                                failedCase(
                                                                        indexed.getT1().intValue()
                                                                                + 1,
                                                                        indexed.getT2(),
                                                                        error))))
                .collectList()
                .map(
                        results -> {
                            long passed =
                                    results.stream()
                                            .filter(
                                                    result ->
                                                            Boolean.TRUE.equals(
                                                                    result.get("passed")))
                                            .count();
                            Instant finishedAt = Instant.now();
                            Map<String, Object> evaluation = new LinkedHashMap<>();
                            evaluation.put("contract_version", "agent.orchestration.eval.v1");
                            evaluation.put("evaluation_id", evaluationId);
                            evaluation.put("agent_id", agentId);
                            evaluation.put("org_id", principal.orgId());
                            evaluation.put("user_id", principal.userId());
                            evaluation.put("name", string(request.get("name"), "Orchestration evaluation"));
                            evaluation.put("status", passed == results.size() ? "passed" : "failed");
                            evaluation.put("case_count", results.size());
                            evaluation.put("passed_count", passed);
                            evaluation.put("failed_count", results.size() - passed);
                            evaluation.put(
                                    "pass_rate",
                                    results.isEmpty()
                                            ? 0D
                                            : (double) passed / results.size());
                            evaluation.put("started_at", startedAt.toString());
                            evaluation.put("finished_at", finishedAt.toString());
                            evaluation.put(
                                    "duration_ms",
                                    Duration.between(startedAt, finishedAt).toMillis());
                            evaluation.put("results", List.copyOf(results));
                            Map<String, Object> immutable = Map.copyOf(evaluation);
                            persist(immutable);
                            return immutable;
                        });
    }

    public List<Map<String, Object>> list(
            String agentId, PlatformAuthService.Principal principal, int limit) {
        if (!storage.isSqliteEnabled()) return List.of();
        boolean admin = "PLATFORM_ADMIN".equals(principal.role());
        String sql =
                "SELECT payload FROM "
                        + TABLE
                        + " WHERE agent_id=?"
                        + (admin ? "" : " AND org_id=? AND user_id=?")
                        + " ORDER BY created_at DESC LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, agentId);
            if (!admin) {
                statement.setString(index++, principal.orgId());
                statement.setString(index++, principal.userId());
            }
            statement.setInt(index, Math.max(1, Math.min(100, limit)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(parse(result.getString("payload")));
            }
            return List.copyOf(rows);
        } catch (Exception error) {
            throw new IllegalStateException("Load orchestration evaluations failed", error);
        }
    }

    Mono<Map<String, Object>> evaluateCase(
            String evaluationId,
            int index,
            String agentId,
            PlatformAuthService.Principal principal,
            Map<String, Object> testCase) {
        String input = string(testCase.get("input"), "");
        if (input.isBlank()) {
            return Mono.error(new IllegalArgumentException("case " + index + " input 不能为空"));
        }
        Map<String, Object> expected = object(testCase.get("expected"));
        long timeoutMs =
                Math.max(
                        1_000L,
                        Math.min(
                                600_000L,
                                number(testCase.get("timeout_ms"), DEFAULT_TIMEOUT_MS)));
        String rootTaskId = evaluationId + "_case_" + index;
        ChatRequest chat =
                new ChatRequest(
                        principal.orgId(),
                        principal.userId(),
                        rootTaskId,
                        input,
                        TaskContext.root(rootTaskId, agentId, agentId, null));
        Instant startedAt = Instant.now();
        return Flux.defer(() -> runtime.stream(agentId, chat))
                .timeout(Duration.ofMillis(timeoutMs))
                .collectList()
                .map(
                        events ->
                                judgeCase(
                                        index,
                                        testCase,
                                        expected,
                                        events,
                                        Duration.between(startedAt, Instant.now()).toMillis()));
    }

    static Map<String, Object> judgeCase(
            int index,
            Map<String, Object> testCase,
            Map<String, Object> expected,
            List<AgentEventEnvelope> events,
            long durationMs) {
        List<String> failures = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        List<String> eventTypes = new ArrayList<>();
        Map<String, Object> router = Map.of();
        List<Map<String, Object>> supervisorCalls = new ArrayList<>();
        List<String> pipelineSteps = new ArrayList<>();
        for (AgentEventEnvelope event : events) {
            if (event.delta() != null) answer.append(event.delta());
            String type = safe(event.type()).toLowerCase().replace('.', '_');
            eventTypes.add(type);
            Map<String, Object> payload =
                    event.payload() == null ? Map.of() : event.payload();
            if ("router_decision".equals(type)) router = payload;
            if ("supervisor_subagent_result".equals(type)) supervisorCalls.add(payload);
            if ("pipeline_step_end".equals(type) || "pipeline_result".equals(type)) {
                String stepId = string(payload.get("step_id"), "");
                if (!stepId.isBlank()) pipelineSteps.add(stepId);
            }
        }
        expectText(expected, "route_id", router.get("route_id"), failures);
        expectText(expected, "target_agent_id", router.get("target_agent_id"), failures);
        List<String> expectedBindings = strings(expected.get("binding_ids"));
        List<String> actualBindings =
                supervisorCalls.stream()
                        .map(call -> string(call.get("binding_id"), ""))
                        .filter(value -> !value.isBlank())
                        .toList();
        if (!expectedBindings.isEmpty() && !expectedBindings.equals(actualBindings)) {
            failures.add(
                    "binding_ids expected " + expectedBindings + " but was " + actualBindings);
        }
        List<String> expectedPipelineSteps = strings(expected.get("pipeline_step_ids"));
        if (!expectedPipelineSteps.isEmpty()
                && !expectedPipelineSteps.equals(pipelineSteps)) {
            failures.add(
                    "pipeline_step_ids expected "
                            + expectedPipelineSteps
                            + " but was "
                            + pipelineSteps);
        }
        if (expected.containsKey("child_call_count")) {
            int expectedCount = (int) number(expected.get("child_call_count"), -1);
            if (supervisorCalls.size() != expectedCount) {
                failures.add(
                        "child_call_count expected "
                                + expectedCount
                                + " but was "
                                + supervisorCalls.size());
            }
        }
        if (expected.containsKey("max_child_calls")) {
            int maximum = (int) number(expected.get("max_child_calls"), -1);
            if (supervisorCalls.size() > maximum) {
                failures.add(
                        "child calls exceeded " + maximum + ": " + supervisorCalls.size());
            }
        }
        if (Boolean.FALSE.equals(expected.get("allow_fallback"))
                && supervisorCalls.stream()
                        .anyMatch(call -> Boolean.TRUE.equals(call.get("fallback_used")))) {
            failures.add("unexpected supervisor fallback");
        }
        for (String fragment : strings(expected.get("output_contains"))) {
            if (!answer.toString().contains(fragment)) {
                failures.add("output does not contain: " + fragment);
            }
        }
        if (expected.containsKey("max_duration_ms")) {
            long maximum = number(expected.get("max_duration_ms"), -1L);
            if (durationMs > maximum) {
                failures.add("duration exceeded " + maximum + " ms: " + durationMs + " ms");
            }
        }
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("route_id", router.getOrDefault("route_id", ""));
        observed.put("target_agent_id", router.getOrDefault("target_agent_id", ""));
        observed.put("binding_ids", actualBindings);
        observed.put("child_call_count", supervisorCalls.size());
        observed.put("pipeline_step_ids", pipelineSteps);
        observed.put("event_types", List.copyOf(new LinkedHashSet<>(eventTypes)));
        observed.put("output", answer.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_index", index);
        result.put("name", string(testCase.get("name"), "case-" + index));
        result.put("input", string(testCase.get("input"), ""));
        result.put("passed", failures.isEmpty());
        result.put("failures", List.copyOf(failures));
        result.put("duration_ms", durationMs);
        result.put("expected", expected);
        result.put("observed", Map.copyOf(observed));
        return Map.copyOf(result);
    }

    private static Map<String, Object> failedCase(
            int index, Map<String, Object> testCase, Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return Map.of(
                "case_index", index,
                "name", string(testCase.get("name"), "case-" + index),
                "input", string(testCase.get("input"), ""),
                "passed", false,
                "failures", List.of(safe(current.getMessage())),
                "duration_ms", 0,
                "expected", object(testCase.get("expected")),
                "observed", Map.of());
    }

    private void persist(Map<String, Object> evaluation) {
        if (!storage.isSqliteEnabled()) return;
        String sql =
                "INSERT INTO "
                        + TABLE
                        + " (evaluation_id, agent_id, org_id, user_id, payload, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, string(evaluation.get("evaluation_id"), ""));
            statement.setString(2, string(evaluation.get("agent_id"), ""));
            statement.setString(3, string(evaluation.get("org_id"), ""));
            statement.setString(4, string(evaluation.get("user_id"), ""));
            statement.setString(5, JSON.writeValueAsString(evaluation));
            statement.setString(6, string(evaluation.get("started_at"), Instant.now().toString()));
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Persist orchestration evaluation failed", error);
        }
    }

    private static void expectText(
            Map<String, Object> expected,
            String key,
            Object actual,
            List<String> failures) {
        if (!expected.containsKey(key)) return;
        String wanted = string(expected.get(key), "");
        String observed = string(actual, "");
        if (!wanted.equals(observed)) {
            failures.add(key + " expected " + wanted + " but was " + observed);
        }
    }

    private static List<Map<String, Object>> cases(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            Map<String, Object> item = object(row);
            if (!item.isEmpty()) result.add(item);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        String text = string(value, "");
        return text.isBlank() ? List.of() : List.of(text);
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Map<String, Object> parse(String value) {
        try {
            return value == null || value.isBlank()
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(
                            new LinkedHashMap<>(JSON.readValue(value, MAP_TYPE)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Parse orchestration evaluation failed", error);
        }
    }

    private static String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank()
                ? fallback
                : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
