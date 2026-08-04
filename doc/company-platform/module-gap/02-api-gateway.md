# 对外统一 API 网关

## 结论

AgentScope 不是 API 网关。它提供 Java 运行时 API，平台需要自己提供 REST/SSE/WebSocket、鉴权、错误码、审计和协议兼容。

本模块应作为平台外壳，底层只调用 AgentScope runtime，不把 AgentScope 对象直接暴露给前端或第三方。

## 平台模块功能设计

本模块负责对外暴露统一 API，屏蔽底层 AgentScope Java 的对象和调用细节。

核心功能：

```text
1. 统一 AgentRun 入口：支持 sync、stream、async 三种调用方式。
2. 前端兼容接口：为 live-console 提供模型、Agent、Skill、MCP、会话等管理接口。
3. 第三方协议适配：后续支持 OpenAI Chat Completions、AG-UI、A2A、Agent Protocol。
4. 租户和用户上下文解析：从 token/header/session 中解析 orgId、userId、projectId。
5. 请求参数校验：校验 agent_id、session_id、payload、artifact_refs、runtime_options。
6. 统一响应结构：封装 run_id、status、result、error、trace_id。
7. SSE 事件输出：把 AgentScope event 映射成平台事件流。
8. 错误码转换：把模型、工具、MCP、Skill、权限、会话异常转换成平台错误码。
9. 审计入口：管理操作和运行操作都生成审计上下文。
10. 兼容和版本控制：避免前端或第三方直接依赖内部 Java 类。
```

输入资源：

```text
HTTP 请求
认证信息
AgentRun payload
管理端配置请求
```

输出资源：

```text
标准 JSON 响应
SSE 事件流
统一错误码
trace_id
审计记录
```

## AgentScope 对应实现

AgentScope 可调用入口：

```java
HarnessAgent.call(String message, RuntimeContext context)
HarnessAgent.streamEvents(String message, RuntimeContext context)
```

AgentScope 生态中有协议适配方向：

```text
AG-UI
A2A
Agent Protocol
Chat Completions Web
Channel
```

但这些不是我们平台当前的统一 API。

## 当前平台调用链路

```text
HTTP/SSE
  -> AgentRunsCompatibilityController
  -> AgentRuntimeService
  -> HarnessAgent
```

当前主要入口：

```text
POST /agent-runs/run/stream
```

管理兼容入口：

```text
/platform/frontend/...
```

## 平台应该封装什么

API 网关层应负责：

```text
认证鉴权
租户/组织/用户上下文解析
请求 DTO 校验
统一错误码
SSE 或 WebSocket 输出
trace_id 注入
接口审计
限流
跨域和前端兼容
```

不要在 controller 里直接拼业务逻辑，controller 只做协议适配。

## 还缺什么

```text
正式 API DTO
OpenAPI 文档
统一错误响应结构
统一鉴权过滤器
非流式运行接口
异步运行接口
取消运行接口
查询运行状态接口
提交 waiting_user_input 接口
第三方 Chat Completions 兼容接口
```

## 推荐下一步

```text
P0: 定义 AgentRunController 正式 DTO，不再只用 Map
P1: 增加非流式 / stream / async 三套入口
P2: 增加 RuntimeErrorCode 映射
P3: 把 /platform/frontend 兼容接口和正式开放接口分层
```

## 接口调用设计

### AgentRun 流式接口

当前兼容接口：

```http
POST /agent-runs/run/stream
Content-Type: application/json
x-org-id: platform
x-user-id: platform_admin

{
  "agent_id": "platform_knowledge_agent",
  "session_id": "sess_001",
  "input_type": "chat",
  "payload": {
    "query": "帮我分析这个告警"
  },
  "context": {},
  "artifacts": []
}
```

当前 SSE 输出：

```text
event: activity
data: {"type":"activity","step":"receive","title":"接收请求"}

event: done
data: {"type":"done","status":"succeeded","output_ref":{"result":{"answer":"..."}}}
```

目标 SSE 输出应该逐步改成 AgentScope 原生事件映射：

```text
run_started
message_delta
tool_call_started
tool_call_finished
waiting_user_input
final_result
error
```

### 非流式接口建议

```http
POST /agent-runs/run
Content-Type: application/json

{
  "agent_id": "ops_agent",
  "session_id": "sess_001",
  "user_message": "检查数据库连接异常",
  "artifact_ids": [],
  "runtime_options": {
    "timeout_ms": 60000,
    "max_steps": 20
  }
}
```

响应：

```json
{
  "run_id": "run_001",
  "status": "succeeded",
  "result": {
    "final_answer": "...",
    "citations": []
  }
}
```

### 后端服务调用

```java
ChatRequest req = new ChatRequest(orgId, userId, sessionId, query);
Mono<ChatResponse> result = agentRuntime.chat(agentId, req);
Flux<AgentEventEnvelope> events = agentRuntime.stream(agentId, req);
```

### API 网关职责

```text
1. 从请求头 / token 解析用户和租户
2. 校验 agent_id、session_id、payload
3. 调 PermissionService 判断可执行权限
4. 创建 AgentRun 记录
5. 调 AgentRuntime
6. 将 AgentScope 事件映射成平台事件
7. 记录 AgentRunEvent
8. 返回 SSE / JSON
```

### DB 化边界

API 层不直接依赖 YAML 或 AgentScope 类。正式结构：

```text
Controller -> ApplicationService -> Runtime Port -> AgentScope Adapter
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
