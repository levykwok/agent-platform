/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class PythonScriptTool extends ToolBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String toolId;
    private final Path scriptPath;
    private final Duration timeout;
    private final String pythonCommand;

    public PythonScriptTool(
            String toolId,
            String description,
            Map<String, Object> parameterSchema,
            Path scriptPath,
            Duration timeout,
            String pythonCommand) {
        super(
                ToolBase.builder()
                        .name(toolId)
                        .description(
                                description == null || description.isBlank() ? toolId : description)
                        .inputSchema(normalizeSchema(parameterSchema))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.toolId = toolId;
        this.scriptPath = scriptPath.toAbsolutePath().normalize();
        this.timeout =
                timeout == null || timeout.isNegative() || timeout.isZero()
                        ? Duration.ofSeconds(5)
                        : timeout;
        this.pythonCommand =
                pythonCommand == null || pythonCommand.isBlank() ? "python" : pythonCommand;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> invoke(param == null ? Map.of() : param.getInput()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock invoke(Map<String, Object> args) throws Exception {
        ExecutionResult execution = execute(pythonCommand, scriptPath, toolId, args, timeout);
        if (execution.timedOut()) {
            return ToolResultBlock.error(
                    "Python tool timed out after " + timeout.toMillis() + "ms");
        }
        if (execution.exitCode() != 0) {
            return ToolResultBlock.error(
                    "Python tool exited with code "
                            + execution.exitCode()
                            + ": "
                            + truncate(execution.stderr()));
        }
        if (execution.stdout() == null || execution.stdout().isBlank()) {
            return ToolResultBlock.text("");
        }
        try {
            Map<String, Object> result =
                    MAPPER.readValue(
                            execution.stdout(), new TypeReference<Map<String, Object>>() {});
            if (Boolean.FALSE.equals(result.get("ok"))) {
                return ToolResultBlock.error(
                        String.valueOf(result.getOrDefault("error", execution.stdout())));
            }
            Object value =
                    result.containsKey("result")
                            ? result.get("result")
                            : result.containsKey("text") ? result.get("text") : result;
            return ToolResultBlock.text(
                    value instanceof String ? (String) value : MAPPER.writeValueAsString(value));
        } catch (Exception ignored) {
            return ToolResultBlock.text(execution.stdout().strip());
        }
    }

    public static ExecutionResult execute(
            String pythonCommand,
            Path scriptPath,
            String toolId,
            Map<String, Object> args,
            Duration timeout)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(pythonCommand, scriptPath.toString());
        builder.directory(scriptPath.getParent().toFile());
        Process process = builder.start();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("args", args == null ? Map.of() : args);
        request.put("context", Map.of("tool_id", toolId));
        process.outputWriter().write(MAPPER.writeValueAsString(request));
        process.outputWriter().write(System.lineSeparator());
        process.outputWriter().close();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ExecutionResult(false, true, -1, "", "", null);
        }
        String stdout = process.inputReader().lines().reduce("", PythonScriptTool::joinLines);
        String stderr = process.errorReader().lines().reduce("", PythonScriptTool::joinLines);
        Object parsed = null;
        try {
            if (stdout != null && !stdout.isBlank()) {
                parsed = MAPPER.readValue(stdout, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception ignored) {
            parsed = null;
        }
        return new ExecutionResult(
                process.exitValue() == 0, false, process.exitValue(), stdout, stderr, parsed);
    }

    private static Map<String, Object> normalizeSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        return schema;
    }

    private static String joinLines(String left, String right) {
        if (left == null || left.isEmpty()) {
            return right == null ? "" : right;
        }
        return left + "\n" + (right == null ? "" : right);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    public record ExecutionResult(
            boolean ok,
            boolean timedOut,
            int exitCode,
            String stdout,
            String stderr,
            Object parsed) {}
}
