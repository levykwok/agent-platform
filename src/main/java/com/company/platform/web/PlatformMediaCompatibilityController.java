/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/platform/media")
public class PlatformMediaCompatibilityController {

    @PostMapping(value = "/asr/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> asr(
            @RequestBody(required = false) Map<String, Object> payload) {
        return Flux.just(sse("asr", map("text", "", "result", map("text", ""))));
    }

    @PostMapping(value = "/tts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> tts(
            @RequestBody(required = false) Map<String, Object> payload) {
        String silence = Base64.getEncoder().encodeToString(new byte[2400 * 2]);
        return Flux.just(
                sse("meta", map("event", "meta", "sample_rate", 24000, "channels", 1)),
                sse(
                        "audio",
                        map(
                                "event",
                                "audio",
                                "pcm_base64",
                                silence,
                                "sample_rate",
                                24000,
                                "channels",
                                1)));
    }

    @PostMapping("/tts/cancel")
    public Map<String, Object> cancel(@RequestBody(required = false) Map<String, Object> payload) {
        return map("ok", true);
    }

    private static ServerSentEvent<Map<String, Object>> sse(
            String event, Map<String, Object> data) {
        return ServerSentEvent.builder(data).event(event).build();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
