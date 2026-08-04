# 文件工具访问策略

## 目标

普通业务 Agent 的文件工具只读路径应限定到 `scoped workspace`，并通过 allowlist/denylist 强制限制，避免越权读取源码、日志和敏感配置。

## 业务 Agent 推荐策略（初始）

### Denylist（禁止）

| 类型 | 路径/模式 | 说明 |
| --- | --- | --- |
| 代码仓库 | `src` | 源码/配置泄露风险 |
| 运行状态 | `workspace/agent-state` | 业务隔离与状态隔离 |
| 备份目录 | `workspace/backup` | 旧数据与敏感快照 |
| 数据文件 | `workspace/*.db` | 生产数据库文件 |
| 日志 | `logs` | 日志可能含令牌、用户数据 |
| 临时构建 | `target` | CI/编译产物 |

### Allowlist（建议）

| 类型 | 路径/模式 | 说明 |
| --- | --- | --- |
| 记忆文件 | `workspace/{agentId}/{org}_{user}/MEMORY.md` | 当前用户当前 Agent 的运行文件 |
| 记忆流水 | `workspace/{agentId}/{org}_{user}/memory/` | 当前用户当前 Agent 日志式记忆 |
| 会话文件 | `workspace/{agentId}/{org}_{user}/sessions/` | 当前会话会话数据 |
| 可疑文件资产 | 已发布 skill cache | 只读，禁止跨用户读取 |
| 已上传文件 | 上传后归档目录 | 仅本次 Agent/会话可见 |

## 最低执行规则

```text
1) 文件工具参数先经过路径标准化
2) 任何 denylist 命中直接拒绝（403）
3) 允许路径仍要二次校验归属（agentId / org / user）
4) 事件日志记录拒绝原因（path, agent_id, user_id, tool_call_id）
```

