/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.control.AgentDefinition;
import com.company.platform.control.AgentDefinitionRegistry;
import com.company.platform.control.McpRegistry;
import com.company.platform.control.McpSpec;
import com.company.platform.control.ModelConfigRegistry;
import com.company.platform.control.ModelSpec;
import com.company.platform.control.OrchestrationPolicy;
import com.company.platform.control.SkillRegistry;
import com.company.platform.control.SkillSpec;
import com.company.platform.control.ToolRegistry;
import com.company.platform.control.ToolSpec;
import java.util.List;
import java.util.Map;
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

    public AgentCatalogController(
            AgentDefinitionRegistry registry,
            ToolRegistry toolRegistry,
            McpRegistry mcpRegistry,
            SkillRegistry skillRegistry,
            ModelConfigRegistry modelRegistry) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.modelRegistry = modelRegistry;
    }

    @GetMapping
    public Map<String, List<AgentCatalogItem>> list() {
        return Map.of("agents", registry.allPublished().stream().map(this::toCatalogItem).toList());
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<AgentCatalogItem> byId(@PathVariable String agentId) {
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
    public Map<String, List<ToolSpec>> allTools() {
        return Map.of("tools", toolRegistry.all());
    }

    @GetMapping("/tools/{toolId}")
    public ResponseEntity<ToolSpec> byTool(@PathVariable String toolId) {
        return toolRegistry
                .find(toolId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/mcps")
    public Map<String, List<McpSpec>> allMcps() {
        return Map.of("mcps", mcpRegistry.all());
    }

    @GetMapping("/mcps/{mcpId}")
    public ResponseEntity<McpSpec> byMcp(@PathVariable String mcpId) {
        return mcpRegistry
                .find(mcpId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/skills")
    public Map<String, List<SkillSpec>> allSkills() {
        return Map.of("skills", skillRegistry.all());
    }

    @GetMapping("/skills/{skillId}")
    public ResponseEntity<SkillSpec> bySkill(@PathVariable String skillId) {
        return skillRegistry
                .find(skillId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/models")
    public Map<String, List<ModelSpec>> allModels() {
        return Map.of("models", modelRegistry.all());
    }

    @GetMapping("/models/{modelId}")
    public ResponseEntity<ModelSpec> byModel(@PathVariable String modelId) {
        return modelRegistry
                .find(modelId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping("/models")
    public Map<String, ModelSpec> upsertModel(@RequestBody ModelSpec spec) {
        modelRegistry.upsert(spec);
        return Map.of("model", modelRegistry.find(spec.modelId()).orElseThrow());
    }

    @PostMapping("/tools")
    public Map<String, ToolSpec> upsertTool(@RequestBody ToolSpec spec) {
        toolRegistry.upsert(spec);
        return Map.of("tool", toolRegistry.find(spec.toolId()).orElseThrow());
    }

    @PostMapping("/mcps")
    public Map<String, McpSpec> upsertMcp(@RequestBody McpSpec spec) {
        mcpRegistry.upsert(spec);
        return Map.of("mcp", mcpRegistry.find(spec.mcpId()).orElseThrow());
    }

    @PostMapping("/skills")
    public Map<String, SkillSpec> upsertSkill(@RequestBody SkillSpec spec) {
        skillRegistry.upsert(spec);
        return Map.of("skill", skillRegistry.find(spec.skillId()).orElseThrow());
    }
}
