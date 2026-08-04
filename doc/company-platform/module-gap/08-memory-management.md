# 记忆管理

## 结论

AgentScope Harness 的 memory 是长期记忆，默认启用。它能自动抽取、手动保存、周期合并，但缺平台级记忆治理：确认、可信度、冲突合并、向量索引、权限和 UI。

详细链路见：

```text
doc/company-platform/agentscope-memory.md
```

## 平台模块功能设计

本模块负责长期记忆的治理和平台化管理。AgentScope 负责自动抽取和注入，平台负责可视化、确认、编辑、停用、冲突治理和权限。

核心功能：

```text
1. 长期记忆读取：读取当前用户/Agent namespace 下的 MEMORY.md。
2. Daily ledger 查看：展示 memory/YYYY-MM-DD.md 抽取流水。
3. 手工保存记忆：用户或管理员手动新增长期记忆。
4. 自动抽取接入：复用 MemoryFlushMiddleware 自动抽取结果。
5. 记忆确认：对高影响记忆进入 pending_confirm，人工确认后生效。
6. 记忆编辑：修正错误或过期记忆。
7. 记忆停用/删除：支持 inactive/expired/deleted/merged。
8. 记忆检索：按 scope、agent、业务对象、关键词查询。
9. 记忆冲突合并：发现相似或矛盾记忆，人工或模型辅助合并。
10. 记忆注入策略：控制哪些记忆进入运行时上下文。
```

输入资源：

```text
MEMORY.md
memory/YYYY-MM-DD.md
RuntimeContext
AgentRun/session/source_ref
用户确认操作
```

输出资源：

```text
AgentMemory
MemoryRetrievalResult
记忆审计记录
可注入上下文的记忆片段
```

## AgentScope 对应实现

关键类：

```text
MemorySaveTool
MemorySearchTool
MemoryGetTool
MemoryFlushMiddleware
MemoryFlushManager
MemoryConsolidator
MemoryMaintenanceMiddleware
MemoryConfig
```

关键文件：

```text
MEMORY.md
memory/YYYY-MM-DD.md
memory/.consolidation_state
```

## 长期记忆产生时机

自动抽取：

```text
HarnessAgent.call(...)
  -> ReActAgent 执行完成
  -> MemoryFlushMiddleware
  -> 调模型抽取长期事实
  -> append memory/YYYY-MM-DD.md
```

手动保存：

```text
Agent 调用 memory_save
  -> append MEMORY.md
  -> append memory/YYYY-MM-DD.md
```

周期合并：

```text
MemoryMaintenanceMiddleware
  -> MemoryConsolidator
  -> daily ledger 合并去重
  -> overwrite MEMORY.md
```

## 当前平台已实现

当前没有完整 Memory 管理服务。运行时通过 Harness 默认链路间接启用 memory。

已有文档：

```text
doc/company-platform/agentscope-memory.md
```

## 推荐调用方式

运行时配置：

```java
HarnessAgent.builder()
    .memory(MemoryConfig.builder()
        .flushPrompt(...)
        .flushTrigger(...)
        .consolidationPrompt(...)
        .build())
```

管理侧读取：

```java
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, "MEMORY.md")
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, "memory/YYYY-MM-DD.md")
```

## 还缺什么

```text
PlatformMemoryService
记忆列表/详情/新增/确认/停用接口
pending_confirm / active / rejected 状态流转
可信度评分
冲突检测与合并
向量索引
scope: user/project/org/agent/global
记忆管理页面
AgentDefinition 暴露 memory 配置
```

## 推荐下一步

```text
P0: 修正会话详情 panel 的 memory 读取 RuntimeContext
P1: 增加 PlatformMemoryService，只读 MEMORY.md/daily ledger
P2: AgentDefinition 增加 memory 配置：enabled、flushPrompt、flushTrigger、scope
P3: 管理侧 DB 化 agent_memory，运行时仍先复用 AgentScope memory pipeline
```

## 接口调用设计

### 查询长期记忆

```http
GET /platform/frontend/memory?agent_id=ops_agent&scope=user&scope_id=platform:platform_admin
```

响应建议：

```json
{
  "memory_md": "...",
  "daily_ledgers": [
    {
      "date": "2026-06-26",
      "path": "memory/2026-06-26.md",
      "content": "..."
    }
  ]
}
```

### 手工保存记忆

```http
POST /platform/frontend/memory
Content-Type: application/json

{
  "agent_id": "ops_agent",
  "scope": "user",
  "scope_id": "platform:platform_admin",
  "content": "- 用户希望告警分析结果使用表格展示",
  "source_ref": {
    "session_id": "sess_xxx",
    "run_id": "run_xxx"
  }
}
```

短期实现可以 append 到 AgentScope workspace；长期应该写平台 DB 的 `agent_memory`，再决定是否同步到 AgentScope memory backend。

### 运行时自动抽取

不需要平台接口主动触发。默认链路：

```text
HarnessAgent.call
  -> MemoryFlushMiddleware
  -> MemoryFlushManager.flushMemories
  -> memory/YYYY-MM-DD.md
```

### 自定义抽取 prompt

Agent 配置建议：

```json
{
  "memory": {
    "enabled": true,
    "flush_trigger": "always",
    "flush_prompt": "你是企业运维记忆抽取器...",
    "consolidation_prompt": "...",
    "scope": "USER"
  }
}
```

运行时转换：

```java
HarnessAgent.builder()
    .memory(MemoryConfig.builder()
        .flushPrompt(memory.flushPrompt())
        .flushTrigger(MemoryConfig.FlushTrigger.always())
        .build())
```

### DB 化边界

AgentScope 文件：

```text
MEMORY.md
memory/YYYY-MM-DD.md
```

平台 DB：

```text
agent_memory
memory_embedding_index
memory_audit_log
```

短期不要破坏 AgentScope 自动链路；平台 DB 可以作为治理层。

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

## 2026-07-09 更新：Memory 真实链路修正

AgentScope Harness memory 有三条链路，必须区分。

```text
1. 显式保存：模型调用 memory_save 工具，直接写 MEMORY.md 和 memory/YYYY-MM-DD.md。
2. 自动 flush：MemoryFlushMiddleware 在 agent call 结束后调用模型抽取，写 memory/YYYY-MM-DD.md。
3. consolidation：MemoryConsolidator 周期性把 daily ledger 合并进 MEMORY.md。
```

默认自动抽取 prompt 位于：

```text
agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/MemoryFlushManager.java
MemoryFlushManager.DEFAULT_FLUSH_PROMPT
```

默认触发策略：

```text
MemoryConfig.Builder.flushTrigger = FlushTrigger.always()
MemoryFlushMiddleware 在每次 agent call 完成后 flush。
```

平台覆盖方式：

```text
HarnessAgent.builder()
  .memory(MemoryConfig.builder()
    .flushPrompt(PLATFORM_MEMORY_FLUSH_PROMPT)
    .build())
```

当前平台已经选择不改 AgentScope 源码，而是在 AgentScopeHarnessFactory 中覆盖 flushPrompt。覆盖策略：

```text
1. 用户明确说“记住/以后记得/帮我记下/remember/note this”时，应抽取为记忆。
2. 主观评价、情绪表达、骂人内容，不作为客观事实保存，而作为“用户表达/用户观点”保存。
3. 不在抽取阶段做道德审判、事实核验或拒绝主观表达。
4. 凭据、密钥、违法指令、高敏隐私不保存。
```

路径修正：

```text
错误路径：workspace/{agentId}/MEMORY.md
正确路径：workspace/{agentId}/{org}_{user}/MEMORY.md
正确 daily：workspace/{agentId}/{org}_{user}/memory/YYYY-MM-DD.md
```

平台 SQLite 与 AgentScope 文件的关系：

```text
SQLite：平台长期记忆治理主库，保存 pending/active/rejected/merged 等状态。
MEMORY.md/daily md：AgentScope runtime 工作文件，按 user namespace 隔离。
平台投影：run 前把 SQLite active memory 写入 scoped MEMORY.md 的 managed block。
平台导入：run 后从 scoped MEMORY.md 读非 managed bullet，导入 SQLite pending_confirm。
```

缺口：

```text
1. Memory 页面需要按 agent + user 展示 MEMORY.md 和 daily ledger。
2. 自动 flush 写入 daily 后，平台是否立即导入 SQLite 需要策略化。
3. consolidation 合并 MEMORY.md 后应展示来源和更新时间。
4. episodic index 目前只展示状态，不等于 AgentScope memory。
5. 需要 memory 操作审计：谁确认、谁拒绝、谁合并、从哪个 run 导入。
```

## 2026-07-09 追加发现：跨 Agent 用户记忆不是 AgentScope 文件 memory 的职责

实测确认，AgentScope Harness 的文件 memory 按 agent + user namespace 隔离，典型路径为：

```text
workspace/{agentId}/{org}_{user}/MEMORY.md
workspace/{agentId}/{org}_{user}/memory/YYYY-MM-DD.md
```

这个设计适合作为 runtime 隔离边界，但不适合作为平台级“跨 Agent 用户记忆”的主存储。

结论：

```text
1. AgentScope 的 agent+user memory 隔离是合理 runtime 设计，不是错误。
2. 跨 Agent 用户画像、偏好、长期背景，应由平台 SQLite memory governance 层管理。
3. AgentScope 的 MEMORY.md 应被视为当前 agent runtime 的注入/工作文件，而不是平台级用户记忆主库。
```

平台记忆应拆成至少四类 scope：

```text
user_global：org_id + user_id，所有授权 agent 可读取。
agent_user：org_id + user_id + agent_id，仅当前 agent 读取。
session：org_id + user_id + agent_id + session_id，仅当前会话读取。
org_global：org_id 级公共上下文，按权限注入。
```

运行时注入策略：

```text
运行 agent 时，平台先从 SQLite 取：
1. user_global memories
2. 当前 agent_user memories
3. 当前 session memories
4. 授权 org_global memories

然后按分区投影到当前 AgentScope scoped MEMORY.md 的 platform managed block。
```

示例：

```markdown
<!-- company-platform-memory:start -->
# Platform Managed Memory

## User Global Memory
- 用户偏好中文回答。

## Agent Memory
- researcher 中用户关注电流诊断方法。

## Session Memory
- 当前会话正在测试 memory 链路。
<!-- company-platform-memory:end -->
```

AgentScope 自己通过 `memory_save` 或 flush 产生的文件内容，平台导入时应进入 `pending_confirm`，并根据内容和来源判断归属 scope，而不是默认都当成 agent_user。

要点复核：

```text
- MEMORY.md 与 memory/YYYY-MM-DD.md 继续保留为 AgentScope runtime 文件。
- 平台 SQLite 是 user_global / agent_user / session / org_global 的治理主库，不应被该 runtime 文件替代。
- 注入策略必须保持分区顺序一致，避免不同 scope 的记忆串场。
```
