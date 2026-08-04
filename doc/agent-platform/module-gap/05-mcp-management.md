# MCP Server 管理

## 结论

AgentScope 能把 MCP Server 暴露的工具注册进 `Toolkit`，但不提供完整 MCP Server 管理平台。

MCP 管理模块要自己做注册、检测、同步、schema、风险、权限、审计；运行时再把可用 MCP 工具注入 Harness。

## 平台模块功能设计

本模块负责 MCP Server 的平台侧生命周期管理，以及 MCP Tool 在运行时的可见性控制。

核心功能：

```text
1. MCP Server 注册：维护 server id、transport、url、command、args、headers、env。
2. 鉴权配置：支持 token/api key/cert/credential_ref，接口和日志脱敏。
3. 连接检测：验证 Server 可连接、可初始化、可返回工具列表。
4. 工具发现：从 MCP Server 同步 tools、input_schema、output_schema。
5. 工具元数据维护：维护 display_name、description、risk_level、side_effect、confirm_required。
6. Server 启停：控制 enabled/disabled/unavailable 状态。
7. Agent 绑定：控制某个 Agent 可以使用哪些 MCP Server 或 MCP Tool。
8. 运行时加载：在 HarnessAgent 构建时把 MCP 工具注册进 Toolkit。
9. 健康检测：定时检查 Server 状态并标记不可用。
10. 调用审计：关联 AgentRun、tool_call、server_id、tool_name、耗时、结果。
```

输入资源：

```text
McpSpec
MCP Server 连接信息
MCP Tool schema
Agent mcpRefs
权限上下文
```

输出资源：

```text
可注册到 Toolkit 的 McpServerConfig
MCP Tool 元数据
健康检测记录
同步日志
工具调用审计
```

## AgentScope 对应实现

关键类：

```text
io.agentscope.harness.agent.tools.McpServerConfig
io.agentscope.harness.agent.tools.McpServerRegistrar
io.agentscope.core.tool.Toolkit
```

AgentScope workspace 也支持：

```text
workspace/tools.json
```

## 当前平台已实现

```text
McpRegistry
YamlMcpRegistry
McpSpec
AgentCapabilityAssembler.applyMcps(...)
PlatformConfigStore
```

落盘：

```text
workspace/mcps.yml
```

## 运行时调用链路

```text
AgentDefinition.mcpRefs()
  -> McpRegistry.find(mcpRef)
  -> McpSpec
  -> McpServerConfig
  -> McpServerRegistrar
  -> Toolkit
  -> HarnessAgent.builder().toolkit(toolkit)
```

## AgentScope 能解决什么

```text
运行时连接 MCP Server
把 MCP tool 注册到 Toolkit
Agent 推理时调用 MCP tool
```

## AgentScope 不解决什么

```text
MCP Server 管理页面
连接检测记录
工具发现和 schema 同步入库
工具风险等级
工具调用确认策略
密钥 credential_ref
Server 健康状态
Tool 可用状态
调用审计
```

## 还缺什么

```text
MCP 连接检测接口
MCP 工具同步接口
MCP Tool 元数据模型
MCP Tool 绑定 Agent 的影响范围查询
MCP Tool 风险控制
MCP 调用记录
MCP 密钥托管
```

## 推荐下一步

```text
P0: 做 McpManagementService，封装注册/检测/同步
P1: 保存 MCP Tool schema 和工具状态
P2: Agent 绑定时从 MCP Tool 列表选择，而不是手写 ref
P3: 高风险 MCP Tool 接入 HITL / confirm
```

## 接口调用设计

### MCP Server 注册

```http
POST /platform/frontend/mcps
Content-Type: application/json

{
  "mcp_id": "ops-mcp",
  "transport": "sse",
  "url": "http://127.0.0.1:9000/sse",
  "headers": {
    "Authorization": "Bearer ${OPS_MCP_TOKEN}"
  },
  "timeout": "PT10S",
  "initialization_timeout": "PT30S",
  "enabled": true
}
```

### MCP Server 列表

```http
GET /platform/frontend/mcps
```

### 连接检测建议

```http
POST /platform/frontend/mcps/{mcpId}/check
```

响应：

```json
{
  "mcp_id": "ops-mcp",
  "status": "success",
  "latency_ms": 120,
  "tools_count": 18,
  "error_message": null
}
```

### 工具同步建议

```http
POST /platform/frontend/mcps/{mcpId}/sync-tools
```

响应：

```json
{
  "sync_id": "sync_001",
  "added_count": 2,
  "updated_count": 1,
  "removed_count": 0
}
```

### 运行时注入链路

```text
AgentDefinition.mcpRefs()
  -> McpRegistry.find(mcpId)
  -> McpSpec
  -> AgentCapabilityAssembler.applyMcps(toolkit, definition)
  -> McpServerConfig
  -> McpServerRegistrar
  -> Toolkit
  -> HarnessAgent.builder().toolkit(toolkit)
```

### Java 伪代码

```java
McpSpec spec = mcpRegistry.find(mcpRef).orElseThrow();
McpServerConfig config = toMcpServerConfig(spec);
new McpServerRegistrar(toolkit).register(config);
```

### Agent 绑定接口建议

```http
PUT /platform/frontend/agents/{agentId}/mcp-refs
Content-Type: application/json

{
  "mcp_refs": ["ops-mcp", "monitoring-mcp"]
}
```

### DB 化边界

当前 YAML：

```text
mcps.yml
```

后续 DB：

```text
mcp_server
mcp_tool
mcp_server_check
mcp_tool_sync_log
```

Registry 保持：

```java
McpRegistry.all()
McpRegistry.find(mcpId)
McpRegistry.upsert(spec)
```

## 当前平台接入基线

当前主运行链路：

```text
Frontend AgentWorkbench
  -> POST /agent-runs/run/stream
  -> AgentRunsCompatibilityController
  -> AgentRuntimeService.chat(...)
  -> AgentScopeHarnessFactory.create(...)
  -> HarnessAgent.call(...)
  -> ReActAgent + Middleware + Toolkit + Workspace + Memory + Session
```

当前平台配置统一入口：

```text
PlatformConfigStore
  -> models.yml
  -> providers.yml
  -> agents.yml
  -> tools.yml
  -> mcps.yml
  -> skills.yml
```

当前默认 workspace：

```text
workspace/
```

## 2026-07-09 更新：MCP 管理与运行时接入

当前更准确的 MCP 边界：

```text
平台负责 MCP server 配置、启停状态、发现工具、缓存工具目录、UI 展示、Agent 绑定关系。
AgentScope 负责在 HarnessAgent 运行时根据 McpServerConfig 注册 MCP client 和工具。
```

工具发现策略：

```text
1. 不建议每次 Agent 运行时都实时 tools/list。
2. 平台应定时发现并缓存 MCP 工具目录。
3. 缓存可以进入 SQLite 或工作区 cache，用于 UI 展示和 Agent 绑定校验。
4. Agent 运行时仍以 MCP server 为主绑定对象；选择 MCP server 默认启用其当前可见工具。
5. 单个工具 enable/visible/test/history 是高级治理能力，不应打断普通绑定流程。
```

协议边界：

```text
stdio：可由 AgentScope/Stdio transport 启动子进程，适合本地工具。
streamable-http：外部 server 自己启动，平台只保存 URL 和 headers。
sse：需要确认 AgentScope 当前 MCP client 支持的具体 endpoint 形式，旧式 SSE 与新式 streamable-http 不应混用。
```

当前缺口：

```text
1. MCP 卡片需要明确显示 server 级健康状态和工具级发现状态。
2. 工具列表应按 MCP server 分组，避免同名工具混淆。
3. 后端日志刷屏需要控制 discovery 定时任务日志级别。
4. MCP server lifecycle 是否由平台托管要按 transport 区分，不能一刀切。
```
