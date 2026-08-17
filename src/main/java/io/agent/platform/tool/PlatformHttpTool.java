/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Declarative, user-owned HTTP tool. It never starts a local process. */
public final class PlatformHttpTool extends ToolBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String endpoint;
    private final String method;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public PlatformHttpTool(
            String toolId,
            String description,
            Map<String, Object> parameterSchema,
            String endpoint,
            String method,
            Map<String, String> headers,
            Duration timeout) {
        super(
                ToolBase.builder()
                        .name(toolId)
                        .description(description == null || description.isBlank() ? toolId : description)
                        .inputSchema(parameterSchema == null || parameterSchema.isEmpty()
                                ? Map.of("type", "object", "properties", Map.of())
                                : parameterSchema)
                        .readOnly(false)
                        .concurrencySafe(true));
        this.endpoint = endpoint;
        this.method = method == null || method.isBlank() ? "POST" : method.toUpperCase();
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(5) : timeout;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null || param.getInput() == null ? Map.of() : param.getInput();
        return Mono.fromCallable(() -> invoke(input)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock invoke(Map<String, Object> input) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout);
        headers.forEach(builder::header);
        String body = JSON.writeValueAsString(input);
        switch (method) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.method("DELETE", HttpRequest.BodyPublishers.ofString(body));
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body));
            case "PATCH" -> builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
            default -> builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ToolResultBlock.error("HTTP tool returned " + response.statusCode() + ": " + truncate(response.body()));
        }
        return ToolResultBlock.text(truncate(response.body()));
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 20_000 ? text.substring(0, 20_000) : text;
    }
}
