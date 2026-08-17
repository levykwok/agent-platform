/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.AgentDefinition;
import io.agent.platform.control.AgentDefinitionRegistry;
import io.agent.platform.control.McpRegistry;
import io.agent.platform.control.McpSpec;
import io.agent.platform.control.ModelConfigRegistry;
import io.agent.platform.control.ModelSpec;
import io.agent.platform.control.OrchestrationPolicy;
import io.agent.platform.control.SkillRegistry;
import io.agent.platform.control.SkillSpec;
import io.agent.platform.control.ToolRegistry;
import io.agent.platform.control.ToolSpec;
import java.util.List;
import java.util.Map;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentCatalogController {

    private final AgentDefinitionRegistry registry;
    private final ToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final SkillRegistry skillRegistry;
    private final ModelConfigRegistry modelRegistry;
    private final AgentAssetService assetService;
    private final PlatformAssetAccessService assetAccess;
    private final PlatformAuthService auth;

    public AgentCatalogController(
            AgentDefinitionRegistry registry,
            ToolRegistry toolRegistry,
            McpRegistry mcpRegistry,
            SkillRegistry skillRegistry,
            ModelConfigRegistry modelRegistry,
            AgentAssetService assetService,
            PlatformAssetAccessService assetAccess,
            PlatformAuthService auth) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.modelRegistry = modelRegistry;
        this.assetService = assetService;
        this.assetAccess = assetAccess;
        this.auth = auth;
    }

    private PlatformAuthService.Principal principal(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst("platform_session");
        return auth.current(cookie == null ? "" : cookie.getValue());
    }

    @GetMapping
    public Map<String, List<AgentCatalogItem>> list(ServerHttpRequest request) {
        PlatformAuthService.Principal current = principal(request);
        return Map.of(
                "agents",
                registry.allPublished().stream()
                        .filter(agent -> assetService.canRead(agent.agentId(), current))
                        .map(this::toCatalogItem)
                        .toList());
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<AgentCatalogItem> byId(
            @PathVariable("agentId") String agentId, ServerHttpRequest request) {
        assetService.requireReadable(agentId, principal(request));
        return registry.findPublished(agentId)
                .map(this::toCatalogItem)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private AgentCatalogItem toCatalogItem(AgentDefinition definition) {
        return new AgentCatalogItem(
                definition.agentId(),
                definition.version(),
                definition.name(),
                definition.model(),
                definition.systemPrompt(),
                definition.workspace().toString(),
                definition.toolRefs(),
                definition.mcpRefs(),
                definition.skillRefs(),
                definition.orchestration());
    }

    public record AgentCatalogItem(
            String agentId,
            String version,
            String name,
            String model,
            String systemPrompt,
            String workspace,
            List<String> toolRefs,
            List<String> mcpRefs,
            List<String> skillRefs,
            OrchestrationPolicy orchestration) {}

    @GetMapping("/tools")
    public Map<String, List<ToolSpec>> allTools(ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        return Map.of(
                "tools",
                toolRegistry.all().stream()
                        .filter(spec -> assetAccess.canRead("TOOL", spec.toolId(), current))
                        .toList());
    }

    @GetMapping("/tools/{toolId}")
    public ResponseEntity<ToolSpec> byTool(
            @PathVariable("toolId") String toolId, ServerHttpRequest request) {
        assetAccess.requireReadable("TOOL", toolId, requirePrincipal(request));
        return toolRegistry
                .find(toolId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/mcps")
    public Map<String, List<McpSpec>> allMcps(ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        return Map.of(
                "mcps",
                mcpRegistry.all().stream()
                        .filter(spec -> assetAccess.canRead("MCP", spec.mcpId(), current))
                        .toList());
    }

    @GetMapping("/mcps/{mcpId}")
    public ResponseEntity<McpSpec> byMcp(
            @PathVariable("mcpId") String mcpId, ServerHttpRequest request) {
        assetAccess.requireReadable("MCP", mcpId, requirePrincipal(request));
        return mcpRegistry
                .find(mcpId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/skills")
    public Map<String, List<SkillSpec>> allSkills(ServerHttpRequest request) {
        PlatformAuthService.Principal current = requirePrincipal(request);
        return Map.of(
                "skills",
                skillRegistry.all().stream()
                        .filter(spec -> assetAccess.canRead("SKILL", spec.skillId(), current))
                        .toList());
    }

    @GetMapping("/skills/{skillId}")
    public ResponseEntity<SkillSpec> bySkill(
            @PathVariable("skillId") String skillId, ServerHttpRequest request) {
        assetAccess.requireReadable("SKILL", skillId, requirePrincipal(request));
        return skillRegistry
                .find(skillId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/models")
    public Map<String, List<ModelSpec>> allModels(ServerHttpRequest request) {
        requirePrincipal(request);
        return Map.of("models", modelRegistry.all());
    }

    @GetMapping("/models/{modelId}")
    public ResponseEntity<ModelSpec> byModel(
            @PathVariable("modelId") String modelId, ServerHttpRequest request) {
        requirePrincipal(request);
        return modelRegistry
                .find(modelId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping("/models")
    public Map<String, ModelSpec> upsertModel(
            @RequestBody ModelSpec spec, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        modelRegistry.upsert(spec);
        return Map.of("model", modelRegistry.find(spec.modelId()).orElseThrow());
    }

    @PostMapping("/tools")
    public Map<String, ToolSpec> upsertTool(
            @RequestBody ToolSpec spec, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        toolRegistry.upsert(spec);
        assetAccess.ensurePublic("TOOL", spec.toolId());
        return Map.of("tool", toolRegistry.find(spec.toolId()).orElseThrow());
    }

    @PostMapping("/mcps")
    public Map<String, McpSpec> upsertMcp(
            @RequestBody McpSpec spec, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        mcpRegistry.upsert(spec);
        assetAccess.ensurePublic("MCP", spec.mcpId());
        return Map.of("mcp", mcpRegistry.find(spec.mcpId()).orElseThrow());
    }

    @PostMapping("/skills")
    public Map<String, SkillSpec> upsertSkill(
            @RequestBody SkillSpec spec, ServerHttpRequest request) {
        auth.requireAdmin(requirePrincipal(request));
        skillRegistry.upsert(spec);
        assetAccess.ensurePublic("SKILL", spec.skillId());
        return Map.of("skill", skillRegistry.find(spec.skillId()).orElseThrow());
    }

    private PlatformAuthService.Principal requirePrincipal(ServerHttpRequest request) {
        PlatformAuthService.Principal current = principal(request);
        if (current == null) {
            throw new PlatformAuthService.AuthException(401, "请先登录");
        }
        return current;
    }
}
