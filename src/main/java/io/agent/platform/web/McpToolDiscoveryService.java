/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class McpToolDiscoveryService {

    private static final String PROTOCOL_VERSION = "2025-11-25";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Path processDirectory;

    public McpToolDiscoveryService(io.agent.platform.control.PlatformStorageLayer storage) {
        this.processDirectory = storage.resolveWorkspace().toAbsolutePath().normalize();
    }

    public List<Map<String, Object>> discover(Map<String, Object> server) {
        Map<String, Object> result = request(server, "tools/list", Map.of());
        Object tools = result.get("tools");
        if (!(tools instanceof List<?> list)) {
            return List.of();
        }
        List<String> toolFilter = toolFilter(server);
        List<Map<String, Object>> rows = new ArrayList<>();
        String serverId = string(server.get("id"), "mcp");
        String runtimeName = string(server.get("name"), "mcp");
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                if (!toolAllowed(raw, toolFilter)) {
                    continue;
                }
                rows.add(toToolRow(serverId, runtimeName, raw));
            }
        }
        return rows;
    }

    public Map<String, Object> probe(Map<String, Object> server) {
        try {
            Map<String, Object> result = request(server, "tools/list", Map.of());
            if (result.get("error") != null) {
                return row(
                        "ok",
                        false,
                        "stage",
                        "tools/list",
                        "error",
                        string(result.get("error"), "MCP tools/list failed."));
            }
            List<Map<String, Object>> tools = toolsFromResult(server, result);
            return row(
                    "ok",
                    true,
                    "stage",
                    "tools/list",
                    "server_name",
                    string(server.get("name"), string(server.get("id"), "mcp")),
                    "server_version",
                    PROTOCOL_VERSION,
                    "tool_count",
                    tools.size(),
                    "tools",
                    tools.stream().map(row -> row.get("tool_name")).toList());
        } catch (Exception e) {
            return row(
                    "ok",
                    false,
                    "stage",
                    "tools/list",
                    "error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public Map<String, Object> callTool(
            Map<String, Object> server, String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = row("name", toolName, "arguments", arguments);
        Map<String, Object> result = request(server, "tools/call", params);
        if (result.get("error") != null) {
            return row(
                    "ok",
                    false,
                    "result",
                    result,
                    "result_preview",
                    string(result.get("error"), "MCP tools/call failed."));
        }
        Object content = result.get("content");
        return row(
                "ok",
                !Boolean.TRUE.equals(result.get("isError")),
                "result",
                result,
                "result_preview",
                preview(content == null ? result : content));
    }

    private Map<String, Object> request(
            Map<String, Object> server, String method, Map<String, Object> params) {
        String transport = string(server.get("transport"), "streamable-http");
        return switch (transport) {
            case "stdio" -> stdioRequest(server, method, params);
            case "streamable-http", "http" -> httpRequest(server, method, params);
            case "sse" -> sseRequest(server, method, params);
            default -> row("tools", List.of(), "error", "Unsupported MCP transport: " + transport);
        };
    }

    private Map<String, Object> stdioRequest(
            Map<String, Object> server, String method, Map<String, Object> params) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(string(server.get("command"), ""));
            command.addAll(stringList(server.get("args")));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(processDirectory.toFile());
            process = builder.start();
            try (BufferedWriter writer =
                            new BufferedWriter(
                                    new OutputStreamWriter(
                                            process.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            process.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(mapper.writeValueAsString(rpc(1, "initialize", Map.of())) + "\n");
                writer.flush();
                reader.readLine();
                writer.write(mapper.writeValueAsString(rpc(2, method, params)) + "\n");
                writer.flush();
                return result(reader.readLine());
            }
        } catch (Exception e) {
            return row("tools", List.of(), "error", e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private Map<String, Object> httpRequest(
            Map<String, Object> server, String method, Map<String, Object> params) {
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(URI.create(string(server.get("endpoint"), "")))
                            .timeout(Duration.ofMillis(number(server.get("timeout_ms"), 5000)))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(rpc(1, method, params))));
            String auth = string(server.get("auth_header"), "");
            if (!auth.isBlank()) {
                builder.header("Authorization", auth);
            }
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return result(response.body());
        } catch (Exception e) {
            return row("tools", List.of(), "error", e.getMessage());
        }
    }

    private Map<String, Object> sseRequest(
            Map<String, Object> server, String method, Map<String, Object> params) {
        try {
            URI sseUri = URI.create(string(server.get("endpoint"), ""));
            HttpRequest.Builder sseBuilder =
                    HttpRequest.newBuilder(sseUri)
                            .timeout(Duration.ofMillis(number(server.get("timeout_ms"), 5000)))
                            .header("Accept", "text/event-stream")
                            .GET();
            String auth = string(server.get("auth_header"), "");
            if (!auth.isBlank()) {
                sseBuilder.header("Authorization", auth);
            }
            HttpResponse<InputStream> sseResponse =
                    httpClient.send(sseBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(sseResponse.body(), StandardCharsets.UTF_8))) {
                Map<String, Object> endpointEvent = readSseEvent(reader);
                String endpoint = string(endpointEvent.get("endpoint"), "");
                if (endpoint.isBlank()) {
                    return row("tools", List.of(), "error", "SSE endpoint event missing.");
                }
                URI messageUri = sseUri.resolve(endpoint);
                postJson(server, messageUri, rpc(1, "initialize", Map.of()));
                readSseEvent(reader);
                postJson(server, messageUri, rpc(2, method, params));
                return result(mapper.writeValueAsString(readSseEvent(reader)));
            }
        } catch (Exception e) {
            return row("tools", List.of(), "error", e.getMessage());
        }
    }

    private void postJson(Map<String, Object> server, URI uri, Map<String, Object> payload)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(number(server.get("timeout_ms"), 5000)))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        mapper.writeValueAsString(payload)));
        String auth = string(server.get("auth_header"), "");
        if (!auth.isBlank()) {
            builder.header("Authorization", auth);
        }
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    }

    private Map<String, Object> readSseEvent(BufferedReader reader) throws Exception {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    String payload = data.toString().trim();
                    if (payload.startsWith("{")) {
                        return mapper.readValue(
                                payload, new TypeReference<Map<String, Object>>() {});
                    }
                    return row("endpoint", payload);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).trim());
            }
        }
        return Map.of();
    }

    private Map<String, Object> result(String json) throws Exception {
        Map<String, Object> envelope =
                mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        Object error = envelope.get("error");
        if (error instanceof Map<?, ?> map) {
            return row(
                    "tools", List.of(), "error", string(map.get("message"), String.valueOf(map)));
        }
        Object result = envelope.get("result");
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Map.of("tools", List.of());
    }

    private Map<String, Object> rpc(int id, String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        if ("initialize".equals(method)) {
            request.put(
                    "params",
                    Map.of(
                            "protocolVersion",
                            PROTOCOL_VERSION,
                            "capabilities",
                            Map.of(),
                            "clientInfo",
                            Map.of("name", "agent-platform", "version", "0.1.0")));
        }
        return request;
    }

    private Map<String, Object> toToolRow(String serverId, String runtimeName, Map<?, ?> raw) {
        String name = string(raw.get("name"), "tool");
        Object inputSchema = raw.containsKey("inputSchema") ? raw.get("inputSchema") : Map.of();
        return row(
                "tool_id",
                "mcp:" + serverId + ":" + name,
                "tool_name",
                name,
                "name",
                name,
                "display_name",
                runtimeName + " / " + name,
                "description",
                string(raw.get("description"), ""),
                "runtime_name",
                runtimeName,
                "source_type",
                "mcp",
                "category",
                "mcp",
                "domain",
                "platform",
                "binding_status",
                "enabled",
                "binding_visibility",
                "discoverable",
                "parameter_schema",
                inputSchema);
    }

    private List<Map<String, Object>> toolsFromResult(
            Map<String, Object> server, Map<String, Object> result) {
        Object tools = result.get("tools");
        if (!(tools instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> toolFilter = toolFilter(server);
        String serverId = string(server.get("id"), "mcp");
        String runtimeName = string(server.get("name"), "mcp");
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                if (!toolAllowed(raw, toolFilter)) {
                    continue;
                }
                rows.add(toToolRow(serverId, runtimeName, raw));
            }
        }
        return rows;
    }

    private List<String> toolFilter(Map<String, Object> server) {
        List<String> filter = stringList(server.get("tool_filter"));
        if (filter == null || filter.isEmpty()) {
            return List.of();
        }
        return filter.stream().filter(v -> v != null && !v.isBlank()).map(v -> v.strip()).toList();
    }

    private boolean toolAllowed(Map<?, ?> raw, List<String> toolFilter) {
        if (toolFilter == null || toolFilter.isEmpty()) {
            return true;
        }
        String toolName = string(raw.get("name"), "");
        return toolFilter.contains(toolName);
    }

    private static String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    private String preview(Object value) {
        if (value instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first
                && first.get("text") != null) {
            return String.valueOf(first.get("text"));
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
