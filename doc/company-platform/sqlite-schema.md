# SQLite 表说明（Company Platform）

> 当前版本以 `file` 与 `sqlite` 两条持久化路径并存为前提，以下按“当前实际 SQLite 体系统计”整理。

数据库位置：

```text
workspace/platform-platform.db
```

## 表清单与治理说明

| 领域功能 | SQLite 实体 | 主键 | 关键字段 | 是否运行时必需 | 是否可迁移到 DB | 是否仍需文件系统配合 |
| --- | --- | --- | --- | --- | --- | --- |
| domains | `platform_domains` | `domain_id` | `payload`, `updated_at` | 是（平台列表） | 是（统一到 DB） | 否 |
| providers | `platform_config`（`config_key='providers.yml'`） | `config_key` | `content`, `updated_at` | 是（模型供应商解析） | 是 | 是（seed 与兼容场景） |
| models | `platform_config`（`config_key='models.yml'`） | `config_key` | `content`, `updated_at` | 是（模型列表） | 是 | 是（seed 与兼容场景） |
| agents | `platform_config`（`config_key='agents.yml'`） | `config_key` | `content`, `updated_at` | 是（运行前定义查询） | 是 | 是（发布配置与默认文件） |
| tools | `platform_config`（`config_key='tools.yml'`） | `config_key` | `content`, `updated_at` | 是（工具目录） | 是 | 是（Tool/脚本实际执行依赖） |
| mcp | `platform_config`（`config_key='mcps.yml'`） + `platform_mcp_discovered_tools` | `server_id`（发现缓存） | `probe_payload`, `tools_payload`, `updated_at` | 是（MCP 列表 + 工具发现） | 是 | 是（MCP 运行时连接信息） |
| skills | `platform_skill_packages` | `package_id` | `payload`, `updated_at` | 是（skill 包） | 是 | 是（workspace/skills 的脚本、SKILL.md） |
| sessions | `platform_agent_sessions` | `(agent_id, session_id)` | `title`, `user_id`, `domain`, `created_at`, `updated_at` | 是（会话查询） | 是 | 是（session context/task 文件） |
| sessions | `platform_session_messages` | `id`（自增） | `agent_id`, `session_id`, `role`, `content`, `created_at` | 是（会话消息） | 是 | 是（历史恢复与上下文） |
| sessions | `platform_session_contexts` / `platform_session_tasks` | `(agent_id, session_id)` | `content` | 是（运行时上下文） | 是 | 是（session assets） |
| memories | `platform_memories` | `memory_id` | `payload`, `updated_at` | 是（治理层 pending/active） | 是 | 是（与 AgentScope memory 文件协作） |
| packages | `platform_skill_packages` | `package_id` | `payload`, `updated_at` | 是（审核/发布） | 是 | 是（workspace 缓存 ZIP） |

补充：`platform_audit_events`、`platform_probe_runs`、`platform_migration_history`、`platform_mcp_discovered_tools` 等为支持治理与可观测性的辅助表，不直接承载业务资产。

## 字段层级建议（新需求时使用）

```text
1. 平台元数据：优先使用 SQLite 表
2. 平台执行资产：仍保留 workspace 文件（agentscope runtime 资源和附件）
3. 对比旧实现：文件是辅助与兼容层，非主元数据层
```

