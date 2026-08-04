# Agent Platform 对 AgentScope 会话机制的接入说明

本文记录我们在 `agent-platform` 中接入 AgentScope/Harness 会话能力时需要遵守的边界、调用方式和恢复语义。

## 结论

不要在平台兼容层自己拼一套“会话消息表”。

AgentScope Harness 的会话不是简单的聊天记录，而是一组运行态资产：

- `RuntimeContext`
- `AgentState`
- workspace 下的 session index
- session context tree
- append-only session log
- task records
- memory 文件
- sandbox / filesystem / tool execution context

平台应该把用户请求交给 `HarnessAgent.call(...)` 或 `HarnessAgent.streamEvents(...)`，让 HarnessAgent 自己通过它的运行链路写入和恢复这些资产。平台侧只做 UI、API 适配、聚合读取和管理能力。

## AgentScope 会话记录包含什么

### 1. RuntimeContext

`RuntimeContext` 是一次 agent 调用的运行上下文，至少包含：

- `sessionId`
- `userId`
- call-scoped `AgentState`
- string attributes
- typed attributes
- `ToolExecutionContext`
- 可注入的 `WorkspaceManager`
- 可注入的 filesystem / sandbox context

平台调用 agent 时必须传入正确的 `userId` 和 `sessionId`。这是 HarnessAgent 按用户和会话隔离状态的入口。

当前平台代码位置：

- `src/main/java/io/agent/platform/runtime/AgentRuntimeService.java`

当前构造方式：

```java
RuntimeContext.builder()
        .userId(userKey(request))
        .sessionId(sessionKey(request))
        .put("tenant_id", safe(request.tenantId(), "default"))
        .build();
```

### 2. AgentState

`AgentState` 是 agent 真正恢复上下文的核心运行态，不等同于 UI 消息列表。

它可能包含：

- ReAct 上下文
- 当前历史消息
- 工具调用中间态
- agent state slot
- 按 `(userId, sessionId)` 路由的状态

HarnessAgent 注释里明确说明：state store 在 builder 时绑定，调用时通过 `(userId, sessionId)` 选择具体状态槽。

默认 state 目录由 AgentScope 管理，通常在：

```text
~/.agentscope/state/<agentId>/
```

平台不应该自己伪造 AgentState。

### 3. Session index

路径：

```text
agents/<agentId>/sessions/sessions.json
```

用途：

- 记录一个 agent 下有哪些 session
- 保存 session summary
- 保存 updatedAt

典型结构：

```json
{
  "version": 1,
  "sessions": {
    "<sessionId>": {
      "summary": "...",
      "updatedAt": "..."
    }
  }
}
```

对应 AgentScope 方法：

```java
WorkspaceManager.updateSessionIndex(rc, agentId, sessionId, summary)
```

### 4. Session context tree

路径：

```text
agents/<agentId>/sessions/<sessionId>.jsonl
```

这是 LLM-facing 的 session context，可能被 compact/offload。

它不是普通聊天日志，而是 `SessionTree` 管理的上下文树。相关用途：

- 给模型恢复上下文
- 被 session search/history 工具读取
- 被 memory flush/compaction 机制引用
- 可以保存 tool use / tool result / 多模态 block 等消息结构

相关 AgentScope 类：

- `MemoryFlushManager`
- `SessionTree`
- `SessionSearchTool`

相关方法：

```java
WorkspaceManager.resolveSessionContextFile(rc, agentId, sessionId)
```

`MemoryFlushManager.offloadMessages(...)` 会把原始 `Msg` offload 到这个 session tree。

### 5. Session full log

路径：

```text
agents/<agentId>/sessions/<sessionId>.log.jsonl
```

用途：

- append-only 完整日志
- 搜索
- 审计
- 追溯完整会话轨迹

相关方法：

```java
WorkspaceManager.resolveSessionLogFile(rc, agentId, sessionId)
WorkspaceManager.listSessionLogFiles(rc)
```

注意：平台不应该手写一份简化 `.log.jsonl` 来冒充 HarnessAgent 的运行结果。否则会漏掉 tool、task、memory、context tree、state 等信息。

### 6. Task records

路径：

```text
agents/<agentId>/tasks/<sessionId>.json
```

用途：

- 异步任务记录
- subagent task
- async tool 状态
- pending / completed / failed 状态
- taskId 级别的恢复和查询

相关方法：

```java
WorkspaceManager.upsertTaskRecord(...)
WorkspaceManager.getTaskRecords(...)
WorkspaceManager.removeTaskRecord(...)
```

恢复会话时，UI 如果需要展示“等待中/可继续/失败”的任务，也必须读取这部分。

### 7. Memory files

路径：

```text
MEMORY.md
memory/YYYY-MM-DD.md
```

用途：

- 长期记忆
- 每日 memory ledger
- 从 session 中 flush 出来的可复用事实

相关组件：

- `MemoryFlushManager`
- `MemoryConsolidator`
- `MemorySaveTool`

这部分不属于单个 session 文件，但会影响 session 恢复后的回答质量。

## 正确调用链路

平台运行 agent 时应该走：

```text
Frontend
  -> agent-platform Controller
  -> AgentRuntimeService
  -> AgentScopeHarnessFactory.create(...)
  -> HarnessAgent.call(...) / HarnessAgent.streamEvents(...)
  -> AgentScope Harness middleware/tool/memory/state/session pipeline
```

当前核心代码：

```java
return agent(definition)
        .call(request.message(), context)
        .map(msg -> response(definition.agentId(), request, msg));
```

以及 streaming：

```java
return agent(definition)
        .streamEvents(request.message(), context)
        .map(this::envelope);
```

这里的关键是 `context` 必须带正确 `userId/sessionId`。

## 当前平台接入原则

### 应该做

- UI 创建/选择 session 时传 `agent_id`
- UI 发送消息时传 `session_id`
- Controller 把 `agent_id/session_id/user_id/org_id` 传入 `AgentRuntimeService`
- `AgentRuntimeService` 构造 `RuntimeContext`
- 运行通过 `HarnessAgent.call(...)` 或 `HarnessAgent.streamEvents(...)`
- session API 读取 AgentScope workspace 中的 session 资产

### 不应该做

- 不要在 Controller 里手动 `appendSessionMessage(...)`
- 不要自己维护一份内存 `sessionMessages`
- 不要把 UI 消息列表当成可恢复会话
- 不要绕开 HarnessAgent 直接调用模型
- 不要伪造 `.log.jsonl` 来补 UI

## 当前代码状态

### 已经做对的部分

`AgentRuntimeService` 已经使用 HarnessAgent：

- `HarnessAgent.call(...)`
- `HarnessAgent.streamEvents(...)`

并且构造了 `RuntimeContext`：

- `userId`
- `sessionId`
- `tenant_id`

`AgentScopeHarnessFactory` 负责创建 HarnessAgent：

- workspace
- model
- toolkit
- skillRepositories
- subagents

### 已经删除的旁路

之前 Controller 里手写过：

```java
state.appendSessionMessage(...)
```

这会导致平台自己伪造 session log，容易漏掉 AgentScope 的真实运行资产。现在这类手写消息日志应删除，运行结果应该由 HarnessAgent 链路产出。

### 仍需继续完善的部分

当前 `PlatformWorkspaceSessionStore` 还只是 workspace session 的读取适配层，不应承担伪造运行产物的职责。

后续应该扩展它聚合读取：

- `sessions.json`
- `<sessionId>.jsonl`
- `<sessionId>.log.jsonl`
- `tasks/<sessionId>.json`
- AgentState 摘要
- memory 文件

## 会话恢复时应该加载什么

恢复会话不能只加载聊天消息。

推荐恢复顺序：

1. 根据 `agentId + sessionId + userId` 构造 `RuntimeContext`
2. 让 HarnessAgent / AgentStateStore 恢复 `(userId, sessionId)` 对应状态槽
3. 读取 `sessions.json` 获取会话元数据
4. 读取 `<sessionId>.jsonl` 获取 LLM-facing context tree
5. 读取 `<sessionId>.log.jsonl` 获取完整日志
6. 读取 `tasks/<sessionId>.json` 获取 pending task / async tool / subagent task
7. 读取 `MEMORY.md` 和 `memory/YYYY-MM-DD.md`
8. UI 聚合展示这些内容，但不要把 UI 展示结果反写成 HarnessAgent 状态

## 推荐 API 聚合结构

后续会话详情接口可以返回：

```json
{
  "session": {
    "session_id": "...",
    "agent_id": "...",
    "summary": "...",
    "updated_at": "..."
  },
  "messages": [],
  "context_entries": [],
  "tasks": [],
  "agent_state": {
    "available": true,
    "summary": "..."
  },
  "memory": {
    "memory_md": "...",
    "daily": []
  },
  "files": {
    "session_index": "agents/<agentId>/sessions/sessions.json",
    "context": "agents/<agentId>/sessions/<sessionId>.jsonl",
    "log": "agents/<agentId>/sessions/<sessionId>.log.jsonl",
    "tasks": "agents/<agentId>/tasks/<sessionId>.json"
  }
}
```

## 实现注意事项

- `agent_id` 必须参与 session 查询，因为 AgentScope session 是按 agent 分目录。
- `userId` 必须参与恢复，因为 AgentState 按 user/session 隔离。
- `sessionId` 不能随便改名，否则恢复不到原来的 AgentState。
- `.jsonl` context 和 `.log.jsonl` 不是同一个东西。
- memory 是跨 session 影响行为的资产，不能只看当前 session 文件。
- task records 是恢复“运行中/等待中”能力的关键。
- 平台 UI 可以展示 session 数据，但不要成为 session 真相来源。

## 后续工作建议

1. 增加 session detail 聚合接口，读取 context/log/tasks/memory。
2. 工作台会话详情增加 tabs：`消息`、`上下文树`、`任务`、`记忆`、`文件`。
3. 确认 HarnessAgent 当前配置是否启用了 compaction/memory middleware，保证 `<sessionId>.jsonl` 真实产出。
4. 把 run observe 和 session task records 打通，展示 pending async tool/subagent task。
5. 将 AgentStateStore 状态摘要暴露给平台，仅用于观察，不用于平台手写覆盖。
