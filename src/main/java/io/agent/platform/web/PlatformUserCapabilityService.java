/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.platform.control.McpSpec;
import io.agent.platform.control.PlatformStorageLayer;
import io.agent.platform.control.SkillSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** User-owned, persisted capabilities that are resolved only for the active tenant. */
@Component
public class PlatformUserCapabilityService {

    private static final String TABLE = "platform_user_capabilities";
    private final PlatformStorageLayer storage;
    private final PlatformAssetAccessService assetAccess;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PersonalAsset> assets = new ConcurrentHashMap<>();

    public PlatformUserCapabilityService(
            PlatformStorageLayer storage, PlatformAssetAccessService assetAccess) {
        this.storage = storage;
        this.assetAccess = assetAccess;
        if (storage.isSqliteEnabled()) {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + TABLE
                            + " (asset_type TEXT NOT NULL, asset_id TEXT NOT NULL, owner_id TEXT NOT NULL, org_id TEXT NOT NULL, visibility TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(asset_type, asset_id))");
            load();
        }
    }

    public record PersonalTool(
            String toolId,
            String description,
            String endpoint,
            String method,
            Map<String, Object> parameterSchema,
            Map<String, String> headers,
            long timeoutMs) {}

    public record PersonalSkill(SkillSpec spec, String content) {}

    private record PersonalAsset(
            String type,
            String id,
            String ownerId,
            String orgId,
            String visibility,
            Map<String, Object> payload,
            String createdAt,
            String updatedAt) {}

    public List<PersonalTool> tools(PlatformAuthService.Principal principal) {
        return readable("TOOL", principal).stream()
                .map(this::toTool)
                .toList();
    }

    public Optional<PersonalTool> findTool(
            String toolId, PlatformAuthService.Principal principal) {
        return find("TOOL", toolId, principal).map(this::toTool);
    }

    public Map<String, Object> invokeTool(
            String toolId, Map<String, Object> arguments, PlatformAuthService.Principal principal) {
        PersonalTool tool =
                findTool(toolId, principal)
                        .orElseThrow(
                                () ->
                                        new PlatformAuthService.AuthException(
                                                404, "Tool 不存在或当前账号无权访问"));
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(URI.create(tool.endpoint()))
                            .timeout(Duration.ofMillis(tool.timeoutMs()));
            tool.headers().forEach(builder::header);
            String body = mapper.writeValueAsString(arguments == null ? Map.of() : arguments);
            switch (tool.method()) {
                case "GET" -> builder.GET();
                case "DELETE" ->
                        builder.method("DELETE", HttpRequest.BodyPublishers.ofString(body));
                case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body));
                case "PATCH" ->
                        builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
                default -> builder.POST(HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<String> response =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build()
                            .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String result = response.body() == null ? "" : response.body();
            if (result.length() > 20_000) result = result.substring(0, 20_000);
            return map(
                    "ok",
                    response.statusCode() >= 200 && response.statusCode() < 300,
                    "tool_id",
                    toolId,
                    "status_code",
                    response.statusCode(),
                    "result",
                    result,
                    "result_preview",
                    result);
        } catch (Exception error) {
            return map("ok", false, "tool_id", toolId, "error", error.getMessage());
        }
    }

    public List<McpSpec> mcps(PlatformAuthService.Principal principal) {
        return readable("MCP", principal).stream().map(this::toMcp).toList();
    }

    public Optional<McpSpec> findMcp(
            String mcpId, PlatformAuthService.Principal principal) {
        return find("MCP", mcpId, principal).map(this::toMcp);
    }

    public List<PersonalSkill> skills(PlatformAuthService.Principal principal) {
        return readable("SKILL", principal).stream()
                .map(asset -> new PersonalSkill(toSkill(asset), string(asset.payload(), "content", "")))
                .toList();
    }

    public Optional<PersonalSkill> findSkill(
            String skillId, PlatformAuthService.Principal principal) {
        return find("SKILL", skillId, principal)
                .map(asset -> new PersonalSkill(toSkill(asset), string(asset.payload(), "content", "")));
    }

    public Map<String, Object> createTool(
            Map<String, Object> payload, PlatformAuthService.Principal principal) {
        requirePrincipal(principal);
        String id = id(payload, "tool", principal.userId());
        ensureNew("TOOL", id);
        String endpoint = string(payload, "endpoint", string(payload, "url", ""));
        validateHttpEndpoint(endpoint);
        String method = string(payload, "method", "POST").toUpperCase();
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException("个人 HTTP Tool 只支持 GET/POST/PUT/PATCH/DELETE。");
        }
        Map<String, Object> row =
                map(
                        "tool_id", id,
                        "description", string(payload, "description", id),
                        "endpoint", endpoint,
                        "method", method,
                        "parameter_schema", objectMap(payload.get("parameter_schema")),
                        "headers", stringMap(payload.get("headers")),
                        "timeout_ms", Math.min(Math.max(longValue(payload.get("timeout_ms"), 5000), 100), 30_000));
        save("TOOL", id, row, principal, string(payload, "visibility", "PRIVATE"));
        return toolRow(row, principal, id);
    }

    public Map<String, Object> updateTool(
            String toolId, Map<String, Object> payload, PlatformAuthService.Principal principal) {
        PersonalAsset current = writable("TOOL", toolId, principal);
        Map<String, Object> row = new LinkedHashMap<>(current.payload());
        row.putAll(payload == null ? Map.of() : payload);
        row.put("tool_id", toolId);
        return createOrUpdateTool(toolId, row, principal, current.visibility());
    }

    private Map<String, Object> createOrUpdateTool(
            String id,
            Map<String, Object> payload,
            PlatformAuthService.Principal principal,
            String visibility) {
        String endpoint = string(payload, "endpoint", string(payload, "url", ""));
        validateHttpEndpoint(endpoint);
        String method = string(payload, "method", "POST").toUpperCase();
        validateToolMethod(method);
        Map<String, Object> row =
                map(
                        "tool_id", id,
                        "description", string(payload, "description", id),
                        "endpoint", endpoint,
                        "method", method,
                        "parameter_schema", objectMap(payload.get("parameter_schema")),
                        "headers", stringMap(payload.get("headers")),
                        "timeout_ms", Math.min(Math.max(longValue(payload.get("timeout_ms"), 5000), 100), 30_000));
        save("TOOL", id, row, principal, visibility);
        return toolRow(row, principal, id);
    }

    public Map<String, Object> createMcp(
            Map<String, Object> payload, PlatformAuthService.Principal principal) {
        requirePrincipal(principal);
        String id = id(payload, "mcp", principal.userId());
        ensureNew("MCP", id);
        return saveMcp(id, payload, principal, string(payload, "visibility", "PRIVATE"));
    }

    public Map<String, Object> updateMcp(
            String mcpId, Map<String, Object> payload, PlatformAuthService.Principal principal) {
        PersonalAsset current = writable("MCP", mcpId, principal);
        Map<String, Object> row = new LinkedHashMap<>(current.payload());
        row.putAll(payload == null ? Map.of() : payload);
        return saveMcp(mcpId, row, principal, current.visibility());
    }

    private Map<String, Object> saveMcp(
            String id,
            Map<String, Object> payload,
            PlatformAuthService.Principal principal,
            String visibility) {
        String transport = string(payload, "transport", "streamable-http").toLowerCase();
        if ("stdio".equals(transport)) {
            throw new PlatformAuthService.AuthException(
                    403, "普通用户只能创建远程 MCP；stdio MCP 需要管理员配置。");
        }
        String endpoint = string(payload, "endpoint", string(payload, "url", ""));
        validateHttpEndpoint(endpoint);
        McpSpec spec =
                new McpSpec(
                        id,
                        transport,
                        null,
                        stringList(payload.get("args")),
                        stringMap(payload.get("env")),
                        endpoint,
                        stringMap(payload.get("headers")),
                        stringMap(payload.get("query_params")),
                        stringList(payload.get("enable_tools")),
                        duration(payload.get("timeout_ms"), 5000),
                        duration(payload.get("initialization_timeout_ms"), 10000),
                        bool(payload.get("enabled"), true));
        Map<String, Object> row = mcpPayload(spec, payload);
        save("MCP", id, row, principal, visibility);
        return mcpRow(spec, principal);
    }

    public Map<String, Object> createSkill(
            Map<String, Object> payload, PlatformAuthService.Principal principal) {
        requirePrincipal(principal);
        String id = id(payload, "skill", principal.userId());
        ensureNew("SKILL", id);
        String content = string(payload, "content", "");
        if (content.isBlank()) throw new IllegalArgumentException("Skill 内容不能为空。");
        Path directory = skillDirectory(principal.userId(), id);
        try {
            Files.createDirectories(directory);
            Files.writeString(
                    directory.resolve("SKILL.md"),
                    "---\nname: \"" + id + "\"\ndescription: \""
                            + string(payload, "description", id).replace("\"", "\\\"")
                            + "\"\n---\n\n"
                            + content.strip()
                            + "\n");
        } catch (IOException error) {
            throw new IllegalStateException("保存个人 Skill 失败", error);
        }
        SkillSpec spec =
                new SkillSpec(
                        id,
                        "filesystem",
                        directory.toString(),
                        "user:" + principal.userId(),
                        "agent",
                        false,
                        string(payload, "description", id),
                        bool(payload.get("enabled"), true));
        Map<String, Object> row =
                map(
                        "skill_id", id,
                        "type", spec.type(),
                        "location", spec.location(),
                        "source", spec.source(),
                        "scope", spec.scope(),
                        "writable", false,
                        "description", spec.description(),
                        "enabled", spec.enabled(),
                        "content", content);
        save("SKILL", id, row, principal, string(payload, "visibility", "PRIVATE"));
        return skillRow(spec, principal);
    }

    public Map<String, Object> updateSkillFile(
            String skillId,
            String relativePath,
            Map<String, Object> payload,
            PlatformAuthService.Principal principal) {
        PersonalAsset current = writable("SKILL", skillId, principal);
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (!"SKILL.md".equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("个人 Skill 当前只允许更新 SKILL.md。");
        }
        String content = string(payload, "content", "");
        Path file = skillDirectory(principal.userId(), skillId).resolve("SKILL.md").normalize();
        if (!file.startsWith(skillDirectory(principal.userId(), skillId))) {
            throw new IllegalArgumentException("非法 Skill 路径。");
        }
        try {
            Files.writeString(file, content);
        } catch (IOException error) {
            throw new IllegalStateException("保存个人 Skill 文件失败", error);
        }
        Map<String, Object> row = new LinkedHashMap<>(current.payload());
        row.put("content", content);
        save("SKILL", skillId, row, principal, current.visibility());
        return skillRow(toSkill(assets.get(key("SKILL", skillId))), principal);
    }

    public void delete(String type, String id, PlatformAuthService.Principal principal) {
        PersonalAsset asset = writable(type, id, principal);
        assets.remove(key(type, id));
        if ("SKILL".equals(type)) {
            deleteDirectory(skillDirectory(asset.ownerId(), id));
        }
        assetAccess.remove(type, id);
        if (storage.isSqliteEnabled()) {
            try (Connection connection = storage.connection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "DELETE FROM " + TABLE + " WHERE asset_type = ? AND asset_id = ?")) {
                statement.setString(1, type);
                statement.setString(2, id);
                statement.executeUpdate();
            } catch (Exception error) {
                throw new IllegalStateException("删除个人资产失败", error);
            }
        }
    }

    public Map<String, Object> enrichToolRow(PersonalTool tool, PlatformAuthService.Principal principal) {
        return toolRow(
                map(
                        "tool_id", tool.toolId(),
                        "description", tool.description(),
                        "endpoint", tool.endpoint(),
                        "method", tool.method(),
                        "parameter_schema", tool.parameterSchema(),
                        "headers", Map.of(),
                        "timeout_ms", tool.timeoutMs()),
                principal,
                tool.toolId());
    }

    public Map<String, Object> enrichMcpRow(McpSpec spec, PlatformAuthService.Principal principal) {
        return mcpRow(spec, principal);
    }

    public Map<String, Object> mcpRuntimeRow(
            String mcpId, PlatformAuthService.Principal principal) {
        McpSpec spec =
                findMcp(mcpId, principal)
                        .orElseThrow(
                                () ->
                                        new PlatformAuthService.AuthException(
                                                404, "MCP 不存在或当前账号无权访问"));
        Map<String, Object> row =
                map(
                        "id", spec.mcpId(),
                        "name", spec.mcpId(),
                        "transport", spec.transport(),
                        "endpoint", spec.url(),
                        "enabled", spec.enabled(),
                        "args", spec.args(),
                        "env", spec.env(),
                        "headers", spec.headers(),
                        "query_params", spec.queryParams(),
                        "tool_filter", spec.enableTools(),
                        "timeout_ms", spec.timeout() == null ? 5000 : spec.timeout().toMillis());
        if (spec.headers().containsKey("Authorization")) {
            row.put("auth_header", spec.headers().get("Authorization"));
        }
        return row;
    }

    public Map<String, Object> skillDetail(
            String skillId, PlatformAuthService.Principal principal) {
        PersonalSkill skill =
                findSkill(skillId, principal)
                        .orElseThrow(
                                () ->
                                        new PlatformAuthService.AuthException(
                                                404, "Skill 不存在或当前账号无权访问"));
        return map(
                "skill_id", skill.spec().skillId(),
                "name", skill.spec().skillId(),
                "description", skill.spec().description(),
                "type", skill.spec().type(),
                "location", skill.spec().location(),
                "source", skill.spec().source(),
                "scope", skill.spec().scope(),
                "enabled", skill.spec().enabled(),
                "writable", false,
                "resolved_path", skill.spec().location(),
                "exists", Files.exists(Path.of(skill.spec().location())),
                "directory", Files.isDirectory(Path.of(skill.spec().location())),
                "files", List.of("SKILL.md"),
                "skill_markdown", skill.content(),
                "analysis", Map.of());
    }

    public Map<String, Object> skillFile(
            String skillId, String path, PlatformAuthService.Principal principal) {
        PersonalSkill skill =
                findSkill(skillId, principal)
                        .orElseThrow(
                                () ->
                                        new PlatformAuthService.AuthException(
                                                404, "Skill 不存在或当前账号无权访问"));
        if (!"SKILL.md".equalsIgnoreCase(path)) {
            throw new IllegalArgumentException("个人 Skill 当前只支持 SKILL.md。");
        }
        return map(
                "skill_id", skillId,
                "path", "SKILL.md",
                "content", skill.content(),
                "size", skill.content().length());
    }

    public Map<String, Object> enrichSkillRow(PersonalSkill skill, PlatformAuthService.Principal principal) {
        return skillRow(skill.spec(), principal);
    }

    private List<PersonalAsset> readable(String type, PlatformAuthService.Principal principal) {
        return assets.values().stream()
                .filter(asset -> type.equals(asset.type()))
                .filter(asset -> canRead(asset, principal))
                .sorted((a, b) -> a.id().compareToIgnoreCase(b.id()))
                .toList();
    }

    private Optional<PersonalAsset> find(
            String type, String id, PlatformAuthService.Principal principal) {
        PersonalAsset asset = assets.get(key(type, id));
        return asset != null && canRead(asset, principal) ? Optional.of(asset) : Optional.empty();
    }

    private PersonalAsset writable(
            String type, String id, PlatformAuthService.Principal principal) {
        requirePrincipal(principal);
        PersonalAsset asset = assets.get(key(type, id));
        if (asset == null) {
            throw new PlatformAuthService.AuthException(404, "个人资产不存在");
        }
        if (!"PLATFORM_ADMIN".equals(principal.role())
                && !principal.userId().equals(asset.ownerId())) {
            throw new PlatformAuthService.AuthException(403, "没有权限修改个人资产");
        }
        return asset;
    }

    private boolean canRead(PersonalAsset asset, PlatformAuthService.Principal principal) {
        if (principal == null) return false;
        if ("PLATFORM_ADMIN".equals(principal.role())) return true;
        if ("PUBLIC".equals(asset.visibility())) return true;
        if ("ORGANIZATION".equals(asset.visibility())
                && principal.orgId().equals(asset.orgId())) return true;
        return principal.userId().equals(asset.ownerId());
    }

    private void save(
            String type,
            String id,
            Map<String, Object> payload,
            PlatformAuthService.Principal principal,
            String requestedVisibility) {
        String visibility = normalizeVisibility(requestedVisibility, principal);
        PersonalAsset existing = assets.get(key(type, id));
        String now = Instant.now().toString();
        PersonalAsset asset =
                new PersonalAsset(
                        type,
                        id,
                        existing == null ? principal.userId() : existing.ownerId(),
                        existing == null ? principal.orgId() : existing.orgId(),
                        existing == null ? visibility : existing.visibility(),
                        new LinkedHashMap<>(payload),
                        existing == null ? now : existing.createdAt(),
                        now);
        assets.put(key(type, id), asset);
        if (existing == null) {
            assetAccess.registerNew(type, id, principal, asset.visibility(), "PUBLISHED");
        }
        persist(asset);
    }

    private void ensureNew(String type, String id) {
        if (assets.containsKey(key(type, id))) {
            throw new PlatformAuthService.AuthException(409, "同名个人资产已存在");
        }
    }

    private void load() {
        String sql =
                "SELECT asset_type,asset_id,owner_id,org_id,visibility,payload,created_at,updated_at FROM "
                        + TABLE;
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Map<String, Object> payload =
                        mapper.readValue(result.getString("payload"), new TypeReference<>() {});
                PersonalAsset asset =
                        new PersonalAsset(
                                result.getString("asset_type"),
                                result.getString("asset_id"),
                                result.getString("owner_id"),
                                result.getString("org_id"),
                                result.getString("visibility"),
                                payload,
                                result.getString("created_at"),
                                result.getString("updated_at"));
                assets.put(key(asset.type(), asset.id()), asset);
            }
        } catch (Exception error) {
            throw new IllegalStateException("加载个人能力失败", error);
        }
    }

    private void persist(PersonalAsset asset) {
        if (!storage.isSqliteEnabled()) return;
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO "
                                        + TABLE
                                        + " (asset_type,asset_id,owner_id,org_id,visibility,payload,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(asset_type,asset_id) DO UPDATE SET owner_id=excluded.owner_id,org_id=excluded.org_id,visibility=excluded.visibility,payload=excluded.payload,updated_at=excluded.updated_at")) {
            statement.setString(1, asset.type());
            statement.setString(2, asset.id());
            statement.setString(3, asset.ownerId());
            statement.setString(4, asset.orgId());
            statement.setString(5, asset.visibility());
            statement.setString(6, mapper.writeValueAsString(asset.payload()));
            statement.setString(7, asset.createdAt());
            statement.setString(8, asset.updatedAt());
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("保存个人能力失败", error);
        }
    }

    private McpSpec toMcp(PersonalAsset asset) {
        Map<String, Object> p = asset.payload();
        return new McpSpec(
                asset.id(),
                string(p, "transport", "streamable-http"),
                null,
                stringList(p.get("args")),
                stringMap(p.get("env")),
                string(p, "url", string(p, "endpoint", "")),
                stringMap(p.get("headers")),
                stringMap(p.get("query_params")),
                stringList(p.get("enable_tools")),
                duration(p.get("timeout_ms"), 5000),
                duration(p.get("initialization_timeout_ms"), 10000),
                bool(p.get("enabled"), true));
    }

    private SkillSpec toSkill(PersonalAsset asset) {
        Map<String, Object> p = asset.payload();
        return new SkillSpec(
                asset.id(),
                string(p, "type", "filesystem"),
                string(p, "location", ""),
                string(p, "source", "user:" + asset.ownerId()),
                string(p, "scope", "agent"),
                false,
                string(p, "description", asset.id()),
                bool(p.get("enabled"), true));
    }

    private PersonalTool toTool(PersonalAsset asset) {
        Map<String, Object> p = asset.payload();
        return new PersonalTool(
                asset.id(),
                string(p, "description", asset.id()),
                string(p, "endpoint", ""),
                string(p, "method", "POST"),
                objectMap(p.get("parameter_schema")),
                stringMap(p.get("headers")),
                longValue(p.get("timeout_ms"), 5000));
    }

    private Map<String, Object> toolRow(
            Map<String, Object> p, PlatformAuthService.Principal principal, String id) {
        return map(
                "tool_id", id,
                "name", id,
                "display_name", id,
                "description", string(p, "description", id),
                "source_type", "http",
                "type", "http",
                "endpoint", string(p, "endpoint", ""),
                "method", string(p, "method", "POST"),
                "parameter_schema", objectMap(p.get("parameter_schema")),
                "timeout_ms", longValue(p.get("timeout_ms"), 5000),
                "owner_id", principal.userId(),
                "org_id", principal.orgId(),
                "visibility", "PRIVATE");
    }

    private Map<String, Object> mcpRow(McpSpec spec, PlatformAuthService.Principal principal) {
        return map(
                "id", spec.mcpId(),
                "name", spec.mcpId(),
                "transport", spec.transport(),
                "endpoint", spec.url(),
                "enabled", spec.enabled(),
                "owner_id", principal.userId(),
                "org_id", principal.orgId(),
                "visibility", "PRIVATE");
    }

    private Map<String, Object> skillRow(SkillSpec spec, PlatformAuthService.Principal principal) {
        return map(
                "skill_id", spec.skillId(),
                "name", spec.skillId(),
                "display_name", spec.skillId(),
                "description", spec.description(),
                "source", spec.source(),
                "scope", spec.scope(),
                "enabled", spec.enabled(),
                "type", spec.type(),
                "location", spec.location(),
                "writable", spec.writable(),
                "owner_id", principal.userId(),
                "org_id", principal.orgId(),
                "visibility", "PRIVATE");
    }

    private Map<String, Object> mcpPayload(McpSpec spec, Map<String, Object> source) {
        return map(
                "transport", spec.transport(),
                "url", spec.url(),
                "endpoint", spec.url(),
                "args", spec.args(),
                "env", spec.env(),
                "headers", spec.headers(),
                "query_params", spec.queryParams(),
                "enable_tools", spec.enableTools(),
                "timeout_ms", spec.timeout() == null ? 5000 : spec.timeout().toMillis(),
                "initialization_timeout_ms",
                        spec.initializationTimeout() == null
                                ? 10000
                                : spec.initializationTimeout().toMillis(),
                "enabled", spec.enabled(),
                "description", string(source, "description", spec.mcpId()));
    }

    private Path skillDirectory(String userId, String skillId) {
        return storage.resolveWorkspace("user-assets", safeSegment(userId), "skills", safeSegment(skillId));
    }

    private static void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static void validateHttpEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("远程地址不能为空。");
        java.net.URI uri;
        try { uri = java.net.URI.create(endpoint); } catch (Exception error) { throw new IllegalArgumentException("远程地址无效。"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!List.of("http", "https").contains(scheme) || host.isBlank()) {
            throw new IllegalArgumentException("个人 MCP/Tool 只允许 http 或 https 地址。");
        }
        if (host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("0.0.0.0")
                || host.equals("::1")
                || host.startsWith("10.")
                || host.startsWith("192.168.")
                || host.startsWith("169.254.")) {
            throw new IllegalArgumentException("个人远程能力不允许访问本机或内网地址。");
        }
    }

    private static String id(Map<String, Object> payload, String prefix, String userId) {
        String requested = string(payload, prefix + "_id", string(payload, "id", ""));
        String value = requested.isBlank()
                ? prefix + "_" + safeSegment(userId) + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : requested;
        return safeSegment(value);
    }

    private static String safeSegment(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.isBlank() ? "asset" : normalized;
    }

    private static void validateToolMethod(String method) {
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException("个人 HTTP Tool 只支持 GET/POST/PUT/PATCH/DELETE。");
        }
    }

    private static String key(String type, String id) { return type + "\u0000" + id; }

    private static void requirePrincipal(PlatformAuthService.Principal principal) {
        if (principal == null) throw new PlatformAuthService.AuthException(401, "请先登录");
    }

    private static String normalizeVisibility(String requested, PlatformAuthService.Principal principal) {
        String value = requested == null ? "PRIVATE" : requested.trim().toUpperCase();
        if ("PUBLIC".equals(value) && !"PLATFORM_ADMIN".equals(principal.role())) return "PRIVATE";
        if ("ORGANIZATION".equals(value) && !List.of("PLATFORM_ADMIN", "ORG_ADMIN").contains(principal.role())) return "PRIVATE";
        return List.of("PRIVATE", "ORGANIZATION", "PUBLIC").contains(value) ? value : "PRIVATE";
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) source.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(valueText -> !valueText.isBlank()).toList();
    }

    private static String string(Map<String, Object> row, String key, String fallback) {
        Object value = row == null ? null : row.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Duration duration(Object value, long fallbackMs) {
        return Duration.ofMillis(Math.min(Math.max(longValue(value, fallbackMs), 100), 60_000));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return row;
    }
}
