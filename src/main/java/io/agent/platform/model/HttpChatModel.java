/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * Generic HTTP chat adapter for user-defined endpoints.
 */
public final class HttpChatModel implements Model {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String modelName;
    private final String url;
    private final String apiKey;
    private final Map<String, String> headers;
    private final Map<String, Object> bodyParams;
    private final Duration timeout;
    private final HttpClient client;

    public HttpChatModel(
            String modelName,
            String url,
            String apiKey,
            Map<String, String> headers,
            Map<String, Object> bodyParams,
            Duration timeout) {
        this.modelName = blank(modelName) ? "http-chat" : modelName.strip();
        this.url = blank(url) ? "" : url.strip();
        this.apiKey = blank(apiKey) ? "" : apiKey.strip();
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.bodyParams = bodyParams == null ? Map.of() : Map.copyOf(bodyParams);
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        if (url.isBlank()) {
            return Flux.error(new IllegalStateException("http_chat baseUrl is required"));
        }
        return Flux.<ChatResponse>create(
                        sink ->
                                Schedulers.boundedElastic()
                                        .schedule(() -> streamCall(messages, options, sink)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    private ChatResponse call(List<Msg> messages, GenerateOptions options) throws Exception {
        Map<String, Object> body = requestBody(messages, options, false);
        long started = System.nanoTime();
        HttpResponse<String> response =
                client.send(request(body), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "http_chat request failed: HTTP "
                            + response.statusCode()
                            + " "
                            + response.body());
        }
        Map<String, Object> raw =
                MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        String text = extractText(raw);
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .metadata(Map.of("raw", raw, "duration_ms", elapsedMs(started)))
                .build();
    }

    private void streamCall(
            List<Msg> messages, GenerateOptions options, FluxSink<ChatResponse> sink) {
        long started = System.nanoTime();
        AtomicBoolean emitted = new AtomicBoolean(false);
        try {
            Map<String, Object> body = requestBody(messages, options, true);
            HttpResponse<InputStream> response =
                    client.send(request(body), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String text = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException(
                        "http_chat request failed: HTTP " + response.statusCode() + " " + text);
            }
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                    for (String payload : ssePayloads(line)) {
                        if ("[DONE]".equals(payload)) {
                            if (!sink.isCancelled()) {
                                sink.complete();
                            }
                            return;
                        }
                        String delta = streamDelta(payload);
                        if (!delta.isBlank()) {
                            emitted.set(true);
                            sink.next(chunk(delta, Map.of("duration_ms", elapsedMs(started))));
                        }
                    }
                }
            }
            if (!emitted.get() && !sink.isCancelled()) {
                sink.next(call(messages, options));
            }
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (Exception error) {
            if (!sink.isCancelled()) {
                sink.error(error);
            }
        }
    }

    private Map<String, Object> requestBody(
            List<Msg> messages, GenerateOptions options, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "model",
                options != null && options.getModelName() != null
                        ? options.getModelName()
                        : modelName);
        body.put("messages", toMessages(messages));
        body.put("prompt", latestText(messages));
        body.put("query", latestText(messages));
        body.put("input", latestText(messages));
        body.putAll(bodyParams);
        body.put("stream", stream);
        return body;
    }

    private HttpRequest request(Map<String, Object> body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        headers.forEach(
                (key, value) -> {
                    if (!blank(key) && value != null) {
                        request.header(key, value);
                    }
                });
        return request.build();
    }

    private static ChatResponse chunk(String text, Map<String, Object> metadata) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .metadata(metadata)
                .build();
    }

    private static List<String> ssePayloads(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        String text = line.strip();
        if (text.startsWith("data:")) {
            return List.of(text.substring(5).strip());
        }
        if (text.startsWith("{") || "[DONE]".equals(text)) {
            return List.of(text);
        }
        return List.of();
    }

    private static String streamDelta(String payload) {
        if (payload == null || payload.isBlank() || "[DONE]".equals(payload)) {
            return "";
        }
        try {
            Map<String, Object> raw =
                    MAPPER.readValue(payload, new TypeReference<Map<String, Object>>() {});
            String direct = firstText(raw.get("delta"), raw.get("text_delta"), raw.get("token"));
            if (!direct.isBlank()) {
                return direct;
            }
            Object choices = raw.get("choices");
            if (choices instanceof List<?> list
                    && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> choice) {
                Object delta = choice.get("delta");
                if (delta instanceof Map<?, ?> deltaMap) {
                    String text = firstText(deltaMap.get("content"), deltaMap.get("text"));
                    if (!text.isBlank()) {
                        return text;
                    }
                }
                String text = firstText(choice.get("text"));
                if (!text.isBlank()) {
                    return text;
                }
            }
            return extractText(raw);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<Map<String, Object>> toMessages(List<Msg> messages) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (messages == null) {
            return rows;
        }
        for (Msg msg : messages) {
            if (msg == null) {
                continue;
            }
            rows.add(
                    Map.of(
                            "role",
                            msg.getRole().name().toLowerCase(),
                            "content",
                            msg.getTextContent()));
        }
        return rows;
    }

    private static String latestText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && !blank(msg.getTextContent())) {
                return msg.getTextContent();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> response) {
        String direct =
                firstText(
                        response.get("text"),
                        response.get("answer"),
                        response.get("content"),
                        response.get("output"));
        if (!direct.isBlank()) {
            return direct;
        }
        Object result = response.get("result");
        if (result instanceof Map<?, ?> map) {
            String text =
                    firstText(
                            map.get("text"),
                            map.get("answer"),
                            map.get("content"),
                            map.get("output"));
            if (!text.isBlank()) {
                return text;
            }
        }
        Object choices = response.get("choices");
        if (choices instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> choice) {
            Object message = choice.get("message");
            if (message instanceof Map<?, ?> messageMap) {
                String text = firstText(messageMap.get("content"), messageMap.get("text"));
                if (!text.isBlank()) {
                    return text;
                }
            }
            String text = firstText(choice.get("text"));
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
