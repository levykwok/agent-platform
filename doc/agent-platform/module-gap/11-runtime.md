# 运行时

## 结论

运行时是最应该复用 AgentScope 的模块。我们的平台只应该做 AgentDefinition 到 HarnessAgent 的装配、请求/响应协议适配、运行记录、权限过滤和异常映射。

不要重写 ReAct 循环、AgentState、memory、session、skill loading、MCP runtime。

## 平台模块功能设计

本模块负责把平台管理的 AgentDefinition、模型、工具、MCP、Skill、会话、记忆等资源装配成一次可执行的 AgentRun。

核心功能：

```text
1. AgentHandle 获取：按 agent_id/version 获取或构建 HarnessAgent。
2. RuntimeContext 构造：注入 userId、sessionId、tenantId、runId。
3. 资源装配：装配模型、Toolkit、MCP、Skill、子 Agent、workspace。
4. 统一执行：支持 sync、stream、async。
5. 流式事件：把 AgentScope AgentEvent 映射为平台 SSE event。
6. 运行记录：记录 AgentRun、AgentRunEvent、AgentToolCall。
7. 等待用户输入：处理 clarification/confirmation/approval。
8. 异常降级：模型、工具、记忆、RAG、会话失败时按策略处理。
9. 取消和超时：支持 run cancel、step timeout、整体 timeout。
10. 缓存管理：AgentDefinition 发布新版本时刷新 HarnessAgent cache。
```

输入资源：

```text
AgentRunRequest
AgentDefinition
ModelSpec
ToolSpec/McpSpec/SkillSpec
ChatSession
ArtifactRef
Permission context
```

输出资源：

```text
AgentExecutionResult
AgentRunEvent stream
AgentRun DB record
Session updates
Memory/session side effects from AgentScope
```

## AgentScope 对应实现

关键类：

```text
HarnessAgent
ReActAgent
RuntimeContext
AgentState
AgentStateStore
Middleware
Toolkit
AgentEvent
ExecutionConfig
GenerateOptions
```

## 当前平台已实现

```text
AgentRuntime
AgentRuntimeService
ChatRequest
ChatResponse
AgentEventEnvelope
AgentRunsCompatibilityController
AgentScopeHarnessFactory
AgentCapabilityAssembler
```

## 当前调用链路

```text
AgentRuntimeService.chat(agentId, request)
  -> registry.findPublished(agentId)
  -> orchestration mode
  -> runtimeContext(request)
  -> agent(definition).call(request.message(), context)
  -> ChatResponse
```

stream 方向已有方法：

```java
agent(definition).streamEvents(request.message(), context)
```

但当前 `/agent-runs/run/stream` 主要还是把 `chat(...)` 结果包装成 SSE，不是真正透传 AgentScope event stream。

## 还缺什么

```text
真正使用 streamEvents 输出 token/event
AgentRun 表
AgentRunEvent 表
AgentToolCall 表
取消运行
超时控制
等待输入恢复
错误码映射
事件重放
异步运行
运行状态查询
```

## 推荐下一步

```text
P0: AgentRunsCompatibilityController 改成调用 runtime.stream(...)
P1: 建 PlatformRunRecorder，记录 run/event/tool_call
P2: 加 runtime timeout/cancel
P3: waiting_user_input 接 resume
P4: AgentRun 使用固定 AgentSpec snapshot
```

## 接口调用设计

### 当前同步封装

```java
public Mono<ChatResponse> chat(String agentId, ChatRequest request) {
    AgentDefinition definition = definition(agentId);
    RuntimeContext context = runtimeContext(request);
    return agent(definition)
        .call(request.message(), context)
        .map(msg -> response(definition.agentId(), request, msg));
}
```

### 目标流式封装

```java
public Flux<AgentEventEnvelope> stream(String agentId, ChatRequest request) {
    AgentDefinition definition = definition(agentId);
    RuntimeContext context = runtimeContext(request);
    return agent(definition)
        .streamEvents(request.message(), context)
        .map(this::envelope);
}
```

### Controller 应调用 stream

```java
@PostMapping(value = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Map<String, Object>>> streamRun(...) {
    return runtime.stream(agentId, request)
        .map(event -> toSse(event));
}
```

### AgentRun 生命周期

```text
created
running
waiting_user_input
succeeded
failed
cancelled
```

### 运行记录接口建议

查询 run：

```http
GET /agent-runs/{runId}
```

查询事件：

```http
GET /agent-runs/{runId}/events?after_id=0&limit=200
```

取消 run：

```http
POST /agent-runs/{runId}/cancel
```

### RuntimeContext 规范

```java
RuntimeContext.builder()
    .userId(tenantId + ":" + userId)
    .sessionId(sessionId)
    .put("tenant_id", tenantId)
    .put("run_id", runId)
    .build();
```

### 缺口落地

```text
P0: /run/stream 改为 runtime.stream
P1: AgentRunRecorder 记录 run/event/tool_call
P2: timeout/cancel/waiting resume
P3: Runtime 降级策略配置化
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

## 2026-07-09 更新：Runtime 责任边界

Runtime 是平台和 AgentScope 的真正接缝层。

当前职责：

```text
1. 从 AgentDefinition 构建 HarnessAgent。
2. 解析模型策略，选择最终模型。
3. 注入 Toolkit、MCP、SkillRepository。
4. 设置 workspace、AgentStateStore、MemoryConfig、CompactionConfig。
5. run 前投影平台 active memory 到 scoped MEMORY.md。
6. run 后从 scoped MEMORY.md 导入 AgentScope 新增记忆到 SQLite pending_confirm。
7. 把 AgentScope event 转换成前端可读事件流。
```

关键修正：

```text
管理和 runtime 不应该混在一起。管理层保存定义和资产，runtime 消费定义并构建可执行 agent。
工具/skill/MCP 的真实注入点在 runtime 创建 HarnessAgent 时，不在 UI 或 registry 查询时。
```

当前缺口：

```text
1. Agent 事件需要继续压缩噪音，保留 receive、capability_loaded、tool_call、tool_result、skill_load、memory_save、model_call、agent_end。
2. model/tool/skill/mcp 调用失败要有统一错误结构。
3. Runtime cache 需要按 AgentDefinition 版本/更新时间失效。
4. Workflow 每步应拥有独立 trace segment。
```

## 2026-07-09 追加发现：Runtime 必须执行文件工具权限收敛

由于 AgentScope 内置文件工具可以读 workspace/project 中的文件，如果平台将项目根目录、源码目录、agent-state、日志和备份都暴露给普通业务 Agent，会造成越权信息检索。

Runtime 层需要按 Agent 类型和环境模式设置文件访问边界：

```text
业务 Agent：只能访问授权知识库、当前 user scoped memory/session、必要 skill cache。
开发/调试 Agent：可访问源码，但必须有显式权限和审计。
运维 Agent：可访问 logs/metrics，但不能默认给普通用户使用。
```

建议默认禁止普通业务 Agent 访问：

```text
src
target
workspace/agent-state
workspace/backup
workspace/*.db
logs
*.key / *.pem / *.env
```

这不是 AgentScope 单独能解决的问题。AgentScope 提供 filesystem/sandbox 能力，平台 runtime 必须根据业务权限选择 filesystem root、allowlist、denylist 和 tool policy。

### 文件工具访问风险补充（普通业务 Agent）

`grep_files` 工具的常见误用会放大文件读权限风险：只要起点路径过宽，它可跨 workspace 与系统边界遍历到不该读的文件，绕过 memory/session 的治理入口。

默认建议把普通业务 Agent 的文件工具策略聚焦到「运行时必要最小集」：

```text
1. 目标资源默认从 scoped workspace 读取
2. 不允许读取平台源码、runtime 元数据与备份
3. 明确拒绝二进制密钥文件和环境变量文件
4. 仅在工具参数层面校验 denylist/allowlist
```

建议 denylist：

```text
src
workspace/agent-state
workspace/backup
workspace/*.db
logs
target
```

示例 allowlist（供业务 Agent 默认 profile 使用）：

```text
workspace/{agentId}/{org}_{user}/knowledge
workspace/{agentId}/{org}_{user}/sessions
workspace/{agentId}/{org}_{user}/MEMORY.md
workspace/{agentId}/{org}_{user}/memory/
已发布 skill cache 下的只读静态资源
```

补充原则：  
平台应把文件工具访问治理作为可配置策略，按 Agent 类型分层（business/research/writer/debug/ops/admin）下发不同路径白名单与 denylist，并在 tool args 与事件日志里记录拒绝原因。
