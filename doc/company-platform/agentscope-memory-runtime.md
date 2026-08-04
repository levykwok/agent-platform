# AgentScope Memory 三链路说明

AgentScope runtime 有三条记忆链路，三者功能不同：

1. 显式工具 `memory_save`
2. 自动抽取 `MemoryFlushMiddleware`
3. `MemoryConsolidator` 日更合并

## 1) memory_save 显式工具链路

触发条件：模型或用户行为判断为"用户要求记住 X"时主动调用工具。

链路：

```text
用户对话 -> MemorySaveTool -> MEMORY.md append + memory/YYYY-MM-DD.md append
```

说明：

```text
1. 先写入 agent+user scoped 的 MEMORY.md
2. 同步写入 daily ledger
3. 平台记忆治理可从 run 后 pending 流转并人工确认
```

## 2) MemoryFlushMiddleware 自动抽取链路

触发条件：每次 Agent run 结束后（默认配置）。

链路：

```text
call 完成 -> ReAct state -> MemoryFlushMiddleware -> memory/Y-m-d.md append
```

说明：

```text
- 通过模型抽取客观、可复用信息
- 默认策略通常为 flush_trigger=always
- 不会直接覆盖 MEMORY.md
```

## 3) MemoryConsolidator 合并链路

触发条件：定时 maintenance 或手动触发。

链路：

```text
recent daily ledgers -> MemoryConsolidator -> 归并去重 -> MEMORY.md overwrite
```

路径要求（必须使用 runtime scoped 路径）：

```text
workspace/{agentId}/{org}_{user}/MEMORY.md
workspace/{agentId}/{org}_{user}/memory/YYYY-MM-DD.md
```

## Platform SQLite 与 AgentScope 文件的关系

```text
SQLite 平台治理层
- 记录 status、scope、来源、确认状态、审计字段
- 适合做跨-agent 查询、管理、搜索、冲突治理

AgentScope 文件层
- 按 agent+user 运行时隔离的长期记忆/流水
- 适合运行时快速读取与模型调用上下文投影
```

为什么两套都要保留：

```text
1. AgentScope 需要可执行上下文的本地文件格式。
2. 平台需要可治理的状态机（pending/active/rejected/merged）和权限隔离。
3. 两者解耦后，平台可独立实现审核流，而不必重写 AgentScope memory pipeline。
```

