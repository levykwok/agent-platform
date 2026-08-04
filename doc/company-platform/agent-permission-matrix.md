# Agent 类型权限矩阵

## 默认执行权限（运行时）

以下为默认能力边界，实际结果应受组织权限和审核策略进一步收敛。

| Agent 类型 | 读源码 | 读 logs | 读 agent-state | 执行 shell | 访问 MCP | 读跨会话 session |
| --- | --- | --- | --- | --- | --- | --- |
| business agent | 否 | 否 | 否 | 否 | 条件允许（白名单） | 否 |
| research agent | 否 | 否 | 否 | 否 | 条件允许（白名单） | 否 |
| writer agent | 否 | 否 | 否 | 否 | 条件允许（白名单） | 否 |
| debug agent | 是（限定目录） | 是（限定服务目录） | 是 | 是 | 是 | 条件允许 |
| ops agent | 否（默认） | 是（服务日志） | 否/条件 | 条件允许（安全网格） | 是 | 否 |
| admin agent | 是 | 是 | 是 | 是 | 是 | 是 |

## 说明

```text
- “条件允许”表示需要管理员手工白名单、时间窗口、审批步骤或角色限制
- “跨会话 session”指读取未在当前 session_id 约束下的 session memory/session context
- debug/admin 通道必须有最强审计与速率限制
```

