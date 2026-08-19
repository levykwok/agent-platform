/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduledTaskWebhookServiceTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsSignedSuccessEventToConfiguredReceiver() {
        AtomicReference<String> body = new AtomicReference<>("");
        AtomicReference<String> event = new AtomicReference<>("");
        AtomicReference<String> deliveryId = new AtomicReference<>("");
        AtomicReference<String> signature = new AtomicReference<>("");
        server.createContext(
                "/hook",
                exchange -> {
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    event.set(exchange.getRequestHeaders().getFirst("X-Agent-Platform-Event"));
                    deliveryId.set(exchange.getRequestHeaders().getFirst("X-Agent-Platform-Delivery-Id"));
                    signature.set(exchange.getRequestHeaders().getFirst("X-Agent-Platform-Signature"));
                    exchange.sendResponseHeaders(202, 0);
                    exchange.getResponseBody().close();
                });
        server.start();

        ScheduledTaskWebhookService service =
                new ScheduledTaskWebhookService(true, 3, 0, 1000, "127.0.0.1");
        Map<String, Object> task =
                map(
                        "task_id", "task_123",
                        "agent_id", "researcher",
                        "session_id", "sess_123",
                        "name", "日报",
                        "webhook_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                        "webhook_secret", "secret",
                        "webhook_enabled", true);
        Map<String, Object> run = map("run_id", "run_456");

        ScheduledTaskWebhookService.DeliveryResult result =
                service.deliver(task, run, "SUCCEEDED", "今日完成", "");

        assertEquals("DELIVERED", result.status());
        assertEquals("scheduled_agent_run.succeeded", event.get());
        assertEquals("run_456:scheduled_agent_run.succeeded", deliveryId.get());
        assertTrue(body.get().contains("\"task_id\":\"task_123\""));
        assertTrue(body.get().contains("\"session_id\":\"sess_123\""));
        assertTrue(signature.get().startsWith("sha256="));
    }

    @Test
    void skipsWithoutUrlAndDoesNotCallExternalSystem() {
        server.createContext("/hook", exchange -> { throw new AssertionError("must not call"); });
        server.start();
        ScheduledTaskWebhookService service =
                new ScheduledTaskWebhookService(true, 3, 0, 1000, "127.0.0.1");

        ScheduledTaskWebhookService.DeliveryResult result =
                service.deliver(map("webhook_url", ""), map("run_id", "run_1"), "SUCCEEDED", "ok", "");

        assertEquals("SKIPPED", result.status());
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
