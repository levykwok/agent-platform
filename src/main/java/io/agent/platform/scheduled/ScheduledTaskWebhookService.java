/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional outbound callback for scheduled Agent runs. Delivery never changes Agent run status. */
@Component
public class ScheduledTaskWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskWebhookService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean enabled;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final long timeoutMs;
    private final HttpClient client;
    private final List<String> allowedHosts;

    public ScheduledTaskWebhookService(
            @Value("${agent.platform.scheduled-tasks.webhook.enabled:true}") boolean enabled,
            @Value("${agent.platform.scheduled-tasks.webhook.max-attempts:3}") int maxAttempts,
            @Value("${agent.platform.scheduled-tasks.webhook.retry-delay-ms:1000}") long retryDelayMs,
            @Value("${agent.platform.scheduled-tasks.webhook.timeout-ms:10000}") long timeoutMs,
            @Value("${agent.platform.scheduled-tasks.webhook.allowed-hosts:}") String allowedHosts) {
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, Math.min(10, maxAttempts));
        this.retryDelayMs = Math.max(0L, Math.min(60_000L, retryDelayMs));
        this.timeoutMs = Math.max(1000L, Math.min(120_000L, timeoutMs));
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(this.timeoutMs))
                        .build();
        this.allowedHosts = parseHosts(allowedHosts);
    }

    public void validateUrl(String value) {
        if (value == null || value.isBlank()) return;
        URI uri = parseUrl(value);
        if (!allowedHosts.isEmpty() && !hostAllowed(uri.getHost())) {
            throw new IllegalArgumentException("Webhook host 不在允许列表中: " + uri.getHost());
        }
    }

    public DeliveryResult deliver(
            Map<String, Object> task,
            Map<String, Object> run,
            String status,
            String resultText,
            String errorMessage) {
        String url = string(task.get("webhook_url"));
        if (!enabled || url.isBlank() || !webhookEnabled(task)) {
            return DeliveryResult.skipped("webhook 未配置或未启用");
        }

        URI uri;
        try {
            uri = parseUrl(url);
            if (!allowedHosts.isEmpty() && !hostAllowed(uri.getHost())) {
                return DeliveryResult.failed(0, "Webhook host 不在允许列表中");
            }
        } catch (IllegalArgumentException error) {
            return DeliveryResult.failed(0, error.getMessage());
        }

        String runId = string(run.get("run_id"));
        String event =
                "SUCCEEDED".equalsIgnoreCase(status)
                        ? "scheduled_agent_run.succeeded"
                        : "scheduled_agent_run.failed";
        String deliveryId = runId + ":" + event;
        Map<String, Object> payload =
                map(
                        "event", event,
                        "delivery_id", deliveryId,
                        "task_id", task.get("task_id"),
                        "run_id", runId,
                        "agent_id", task.get("agent_id"),
                        "session_id", task.get("session_id"),
                        "task_name", task.get("name"),
                        "status", status,
                        "result_text", resultText == null ? "" : resultText,
                        "result_summary", summary(resultText, errorMessage),
                        "error", errorMessage == null ? "" : errorMessage,
                        "result_path",
                        "/platform/live/qa?session_id="
                                + URLEncoder.encode(string(task.get("session_id")), StandardCharsets.UTF_8),
                        "created_at", Instant.now().toString());
        String body;
        try {
            body = JSON.writeValueAsString(payload);
        } catch (Exception error) {
            return DeliveryResult.failed(0, "Webhook payload 序列化失败: " + error.getMessage());
        }

        String timestamp = Instant.now().toString();
        String secret = string(task.get("webhook_secret"));
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                        HttpRequest.Builder request =
                        HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofMillis(timeoutMs))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .header("User-Agent", "AgentPlatform-ScheduledTask/1")
                                .header("X-Agent-Platform-Event", event)
                                .header("X-Agent-Platform-Delivery-Id", deliveryId)
                                .header("X-Agent-Platform-Timestamp", timestamp)
                                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (!secret.isBlank()) {
                    request.header("X-Agent-Platform-Signature", "sha256=" + hmacSha256(secret, body));
                }
                HttpResponse<String> response =
                        client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    return DeliveryResult.delivered(attempt, code);
                }
                String message = "HTTP " + code;
                if (!retryable(code) || attempt == maxAttempts) {
                    return DeliveryResult.failed(attempt, message);
                }
                sleepBeforeRetry(attempt);
            } catch (Exception error) {
                if (attempt == maxAttempts) {
                    return DeliveryResult.failed(attempt, error.getMessage());
                }
                sleepBeforeRetry(attempt);
            }
        }
        return DeliveryResult.failed(maxAttempts, "Webhook 未投递");
    }

    private void sleepBeforeRetry(int attempt) {
        if (retryDelayMs <= 0) return;
        try {
            Thread.sleep(retryDelayMs * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.debug("Webhook retry interrupted");
        }
    }

    private URI parseUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("Webhook URL 必须是合法的 http/https 地址");
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Webhook URL 无效: " + value, error);
        }
    }

    private boolean hostAllowed(String host) {
        String normalized = host == null ? "" : host.toLowerCase();
        return allowedHosts.stream()
                .anyMatch(
                        allowed ->
                                normalized.equals(allowed)
                                        || (allowed.startsWith("*.")
                                                && normalized.endsWith(allowed.substring(1))));
    }

    private static boolean webhookEnabled(Map<String, Object> task) {
        if (!task.containsKey("webhook_enabled")) return true;
        Object value = task.get("webhook_enabled");
        return value instanceof Boolean
                ? (Boolean) value
                : Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static String hmacSha256(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法生成 Webhook 签名", error);
        }
    }

    private static String summary(String result, String error) {
        String value = string(result);
        if (value.isBlank()) value = string(error);
        if (value.length() > 1000) return value.substring(0, 1000) + "…";
        return value;
    }

    private static List<String> parseHosts(String value) {
        List<String> hosts = new ArrayList<>();
        if (value == null) return hosts;
        for (String item : value.split(",")) {
            String host = item.trim().toLowerCase();
            if (!host.isBlank()) hosts.add(host);
        }
        return List.copyOf(hosts);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    public record DeliveryResult(String status, int attempts, int httpStatus, String message) {
        static DeliveryResult skipped(String message) {
            return new DeliveryResult("SKIPPED", 0, 0, message);
        }

        static DeliveryResult delivered(int attempts, int httpStatus) {
            return new DeliveryResult("DELIVERED", attempts, httpStatus, "");
        }

        static DeliveryResult failed(int attempts, String message) {
            return new DeliveryResult("FAILED", attempts, 0, message == null ? "" : message);
        }
    }
}
