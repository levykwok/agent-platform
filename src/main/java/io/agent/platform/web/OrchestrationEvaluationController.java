/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/platform/frontend/agents/{agentId}/orchestration-evaluations")
public class OrchestrationEvaluationController {

    private final OrchestrationEvaluationService evaluations;
    private final PlatformAuthService auth;
    private final AgentAssetService agents;

    public OrchestrationEvaluationController(
            OrchestrationEvaluationService evaluations,
            PlatformAuthService auth,
            AgentAssetService agents) {
        this.evaluations = evaluations;
        this.auth = auth;
        this.agents = agents;
    }

    @PostMapping("/run")
    public Mono<Map<String, Object>> run(
            @PathVariable("agentId") String agentId,
            @RequestBody Map<String, Object> payload,
            ServerHttpRequest request) {
        PlatformAuthService.Principal principal = principal(request);
        agents.requireReadable(agentId, principal);
        return evaluations.run(agentId, principal, payload);
    }

    @GetMapping
    public Map<String, Object> list(
            @PathVariable("agentId") String agentId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            ServerHttpRequest request) {
        PlatformAuthService.Principal principal = principal(request);
        agents.requireReadable(agentId, principal);
        List<Map<String, Object>> rows = evaluations.list(agentId, principal, limit);
        return Map.of("items", rows, "evaluations", rows);
    }

    private PlatformAuthService.Principal principal(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst("platform_session");
        PlatformAuthService.Principal principal =
                auth.current(cookie == null ? "" : cookie.getValue());
        if (principal == null) throw new PlatformAuthService.AuthException(401, "请先登录");
        return principal;
    }
}
