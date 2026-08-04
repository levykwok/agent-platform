# AgentScope Harness Memory 链路与平台接入说明

## 结论

AgentScope Harness 里的 memory 是长期记忆能力，不是普通聊天记录。

它由三类文件组成：

```text
MEMORY.md
memory/YYYY-MM-DD.md
agents/{agentId}/sessions/{sessionId}.jsonl
```

语义分别是：

```text
MEMORY.md
```

整理后的长期记忆主文件。它是跨会话可复用的 curated memory。

```text
memory/YYYY-MM-DD.md
```

每日长期记忆流水。每次 flush 会把从对话中抽取出的事实、偏好、决策、上下文追加到这里。

```text
agents/{agentId}/sessions/{sessionId}.jsonl
agents/{agentId}/sessions/{sessionId}.log.jsonl
```

会话上下文和日志。它们服务于会话恢复、session search、上下文 offload，不等价于长期记忆。

## 默认是否启用

默认启用。

`HarnessAgent.Builder` 默认配置：

```java
MemoryConfig memoryConfig = MemoryConfig.defaults();
boolean disableMemoryTools = false;
boolean disableMemoryHooks = false;
```

所以只要平台通过 `HarnessAgent.builder()` 构建 agent，并且没有调用：

```java
disableMemoryTools()
disableMemoryHooks()
```

那么 memory tools 和 memory middleware 都会被注册。

我们当前 `AgentScopeHarnessFactory` 没有禁用 memory，因此会使用 AgentScope Harness 默认 memory 链路。

## 长期记忆什么时候产生

长期记忆有两种写入来源：

```text
自动抽取
手动保存
```

并且还有一个周期性整理动作：

```text
daily ledger -> MEMORY.md
```

### 自动抽取

默认自动启用。

链路：

```text
用户和 Agent 对话
  -> HarnessAgent.call(...)
  -> ReActAgent 执行完成
  -> MemoryFlushMiddleware.onAgent(...)
  -> 读取 AgentState.context
  -> 调用模型抽取值得长期保留的信息
  -> 写入 memory/YYYY-MM-DD.md
```

默认策略：

```java
MemoryConfig.FlushTrigger.always()
```

含义：

```text
每次 agent call 结束后都会尝试抽取一次长期记忆。
```

如果模型认为没有值得保存的信息，应该返回：

```text
NO_REPLY
```

这时不会写入 daily ledger。

### 手动保存

AgentScope Harness 默认注册：

```text
memory_save
```

当用户明确表达：

```text
记住 xxx
以后都用 xxx
这个项目规定是 xxx
```

Agent 可以主动调用 `memory_save`。

写入位置：

```text
MEMORY.md
memory/YYYY-MM-DD.md
```

注意：

自动抽取默认只写 `memory/YYYY-MM-DD.md`，后续由 consolidation 合并进 `MEMORY.md`。

`memory_save` 会直接写 `MEMORY.md`，同时也追加 daily ledger。

### 周期性整理

这不是新的抽取，而是合并去重。

链路：

```text
memory/YYYY-MM-DD.md
  -> MemoryMaintenanceMiddleware
  -> MemoryConsolidator
  -> MEMORY.md
```

默认维护间隔：

```java
Duration.ofMinutes(30)
```

作用：

```text
读取当前 MEMORY.md
读取最近更新的 daily memory ledger
调用模型合并、去重、压缩
覆盖写入新的 MEMORY.md
更新 memory/.consolidation_state
```

所以最终语义是：

```text
自动抽取 = 每次 call 后尝试，把新记忆写入 daily ledger
手动保存 = Agent 调 memory_save，直接写 MEMORY.md + daily ledger
周期整理 = 把 daily ledger 合并进 MEMORY.md
```

## 核心组件

### MemorySaveTool

类：

```text
io.agentscope.harness.agent.tool.MemorySaveTool
```

工具名：

```text
memory_save
```

用途：

Agent 主动保存长期记忆。

典型触发场景：

```text
用户要求“记住 xxx”
Agent 判断某些偏好、约定、项目背景、决策需要跨会话保留
```

写入位置：

```text
MEMORY.md
memory/YYYY-MM-DD.md
```

注意：

`MemorySaveTool` 是 AgentScope 设计里推荐的记忆写入口。不要让 LLM 直接用 `write_file` 或 `edit_file` 改 `MEMORY.md`。

### MemoryFlushMiddleware

类：

```text
io.agentscope.harness.agent.middleware.MemoryFlushMiddleware
```

用途：

每次 agent call 结束后，从当前 ReActAgent 的 `AgentState.context` 里读取对话窗口，然后执行两件事：

```text
1. 抽取长期记忆，写入 memory/YYYY-MM-DD.md
2. offload 会话消息，写入 agents/{agentId}/sessions/{sessionId}.jsonl
```

默认 flush 策略：

```java
MemoryConfig.FlushTrigger.always()
```

也就是默认每次 agent call 结束都会尝试 flush。

### MemoryConsolidator

类：

```text
io.agentscope.harness.agent.memory.MemoryConsolidator
```

用途：

周期性把 daily ledger 合并进 `MEMORY.md`。

处理逻辑：

```text
读取当前 MEMORY.md
读取 memory/YYYY-MM-DD.md 中最近更新的 daily entries
调用模型做合并、去重、压缩
覆盖写入新的 MEMORY.md
更新 memory/.consolidation_state
```

`MEMORY.md` 是最终给 Agent 使用的长期记忆主文件。

### MemoryMaintenanceMiddleware

类：

```text
io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware
```

用途：

agent call 后周期性执行维护任务。

它负责：

```text
1. 归档过期 daily memory 文件
2. 触发 MemoryConsolidator
3. 清理过期 session log
```

默认间隔：

```java
Duration.ofMinutes(30)
```

默认保留：

```text
daily memory: 90 days
session log: 180 days
```

### MemorySearchTool / MemoryGetTool / SessionSearchTool

默认注册：

```java
agentToolkit.registerTool(new MemorySearchTool(wsManager));
agentToolkit.registerTool(new MemoryGetTool(wsManager));
agentToolkit.registerTool(new MemorySaveTool(wsManager));
agentToolkit.registerTool(new SessionSearchTool(wsManager));
```

用途：

```text
memory_search: 搜索长期记忆
memory_get: 读取长期记忆
memory_save: 写入长期记忆
session_search: 搜索历史会话
```

这些工具是 Agent 自己在推理过程中调用的，不是平台管理接口。

## 隔离粒度

AgentScope Harness 默认隔离粒度是：

```java
IsolationScope.USER
```

含义：

```text
同一个 userId 共享长期记忆
不同 userId 隔离长期记忆
如果 userId 为空，退化到 sessionId
```

对应 namespace 逻辑：

```java
case USER -> userId 不为空则使用 userId，否则使用 sessionId
case SESSION -> 使用 sessionId
case AGENT / GLOBAL -> 不加 namespace
```

所以 memory 文件实际不是简单地只看 workspace 根目录。

平台读取 memory 时必须用与运行时一致的 `RuntimeContext`：

```java
RuntimeContext.builder()
        .userId(tenantId + ":" + userId)
        .sessionId(sessionId)
        .put("tenant_id", tenantId)
        .build();
```

否则可能读到错误 namespace 下的 `MEMORY.md`。

## 当前平台运行链路

当前对话链路：

```text
frontend AgentWorkbench.vue
  -> POST /agent-runs/run/stream
  -> AgentRunsCompatibilityController
  -> AgentRuntimeService.chat(...)
  -> AgentScopeHarnessFactory.create(...)
  -> HarnessAgent.call(...)
  -> AgentScope Harness middleware/tool/memory/session pipeline
```

`AgentRuntimeService` 当前会构造：

```java
RuntimeContext.builder()
        .userId(userKey(request))
        .sessionId(sessionKey(request))
        .put("tenant_id", safe(request.tenantId(), "default"))
        .build();
```

其中：

```java
userKey = tenant + ":" + user
sessionKey = request.sessionId
```

这和 AgentScope 的默认 `IsolationScope.USER` 是匹配的。

## 平台接入原则

### 运行时不要绕过 Harness

对话运行时不要手写：

```text
MEMORY.md
memory/YYYY-MM-DD.md
session jsonl
session log jsonl
```

应该让：

```text
HarnessAgent.call / streamEvents
```

触发 AgentScope 自己的 middleware 和 tool 链路。

原因：

```text
AgentState.context
MemoryFlushMiddleware
MemoryMaintenanceMiddleware
SessionTree
WorkspaceManager
```

这些内部链路会维护状态一致性。平台手写很容易漏掉 message id、tool call id、session index、namespace、consolidation state。

### 管理侧可以读，但必须按 RuntimeContext 读

平台可以提供 memory 管理和展示接口，但读取必须使用同一套 runtime identity。

正确方式：

```java
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, WorkspaceConstants.MEMORY_MD)
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, "memory/YYYY-MM-DD.md")
```

不要用：

```java
RuntimeContext.empty()
```

除非明确读取全局 namespace。

### 写入长期记忆要分两类

Agent 自动写入：

```text
由 MemorySaveTool / MemoryFlushMiddleware 负责
```

平台管理写入：

```text
通过一个 PlatformMemoryService 封装
```

不要让 controller 直接 append 文件。

建议后续平台服务：

```java
PlatformMemoryService
```

接口：

```java
MemorySnapshot getMemory(agentId, tenantId, userId, sessionId)
List<DailyMemoryFile> listDailyLedgers(agentId, tenantId, userId)
void appendMemory(agentId, tenantId, userId, sessionId, content, source)
void consolidate(agentId, tenantId, userId)
void deleteMemoryEntry(...)
```

第一版可以基于 AgentScope workspace 文件实现。

后续切 DB 时，把实现替换成：

```text
DbPlatformMemoryService
```

但是 runtime 仍然应该尽量走 AgentScope 的 memory pipeline，除非我们明确要重写 Harness memory backend。

## 推荐目录与数据边界

平台 workspace：

```text
workspace/
```

配置文件：

```text
workspace/models.yml
workspace/providers.yml
workspace/agents.yml
workspace/tools.yml
workspace/mcps.yml
workspace/skills.yml
```

AgentScope runtime 数据：

```text
workspace/{namespace}/MEMORY.md
workspace/{namespace}/memory/YYYY-MM-DD.md
workspace/{namespace}/memory/.consolidation_state
workspace/{namespace}/agents/{agentId}/sessions/sessions.json
workspace/{namespace}/agents/{agentId}/sessions/{sessionId}.jsonl
workspace/{namespace}/agents/{agentId}/sessions/{sessionId}.log.jsonl
workspace/{namespace}/agents/{agentId}/tasks/{sessionId}.json
```

其中 `{namespace}` 由 `IsolationScope` 和 `RuntimeContext` 决定。

默认 `IsolationScope.USER` 时：

```text
{namespace} = userId
```

在我们当前平台里：

```text
{namespace} = tenantId + ":" + userId
```

## 我们现在还缺什么

当前对话运行链路已经会走 AgentScope 的 memory middleware。

但管理侧还没有完整接入：

```text
1. 右侧会话详情 panel 读 memory 时要改成带 RuntimeContext
2. 需要 PlatformMemoryService，统一封装 memory 读取、写入、consolidate
3. 需要 Memory 管理页面，查看长期记忆和 daily ledger
4. 需要把 memory scope 做成 agent 配置项
5. 后续如果切 DB，要决定是只管理侧进 DB，还是 Harness runtime memory backend 也换 DB
```

优先级建议：

```text
P0: 修正会话详情 panel 的 memory 读取 RuntimeContext
P1: 做 PlatformMemoryService
P2: 做 Memory 管理页面
P3: agent 配置中增加 memory scope / flush policy / retention policy
P4: DB 化 memory store
```
