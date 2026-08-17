# 定时任务（Scheduled Tasks）

## 目标

登录用户可以把一个 prompt 绑定到已发布 Agent、平台会话和 Cron 表达式。到点后，任务通过当前项目已有的 `AgentRuntime.chat` 执行，结果追加到绑定会话，并生成站内通知和可查询的执行记录。

定时任务不创建第二套 Agent 调用链，也不接受客户端传入的 `userId` 作为权限依据。HTTP 请求和 Agent 工具调用都使用当前登录/运行时身份。

## 与当前项目的对应关系

| 原文档假设 | 当前项目实现 |
|---|---|
| `GatewayService.runAgent` | `AgentRuntime.chat(agentId, ChatRequest)` |
| `SessionMessageService` | `PlatformCompatibilityState.appendSessionMessage` → `PlatformWorkspaceSessionStore` |
| `PlatformToolsSchemaInitializer` | `ScheduledTaskStore` 的 `@PostConstruct` 幂等建表 |
| `userId` 查询参数 | `platform_session` Cookie 对应的 `PlatformAuthService.Principal` |
| `metadata.source` 会话消息 | session log/message 的 `metadata` 字段；旧 SQLite 表启动时自动补列 |
| 只支持一种数据库 | SQLite 使用现有 `PlatformStorageLayer`；file 模式使用 `workspace/cache/scheduled-tasks.json` |

## 运行架构

- `ScheduledTaskStore`：保存任务、执行记录和通知；SQLite 表使用当前项目的 `platform_` 前缀。
- `ScheduledTaskService`：校验 Agent/会话、解析 Cron、领取到期任务、执行 Agent、记录结果和通知。
- `ScheduledTaskController`：提供任务 CRUD、启停、立即执行和执行历史 API。
- `NotificationController`：提供通知列表、未读数量和已读 API。
- `ScheduledTaskTools`：以 Java tool 形式注册 `schedule_*` 工具；通过 `ScheduledTaskCallContext` 获取运行时用户身份。
- `PlatformApplication` 已启用 Spring Scheduling；服务每轮批量领取任务后在 bounded-elastic 线程池执行。

任务领取使用 `lease_until`，避免同一 JVM 或多个实例同时执行同一任务。领取后先计算下一次 Cron 时间，再异步执行；进程异常时租约过期后可以重试。

执行时会：

1. 以 `role=user`、`metadata.source=scheduled_task` 把 prompt 写入绑定会话。
2. 使用 `TaskContext` 标记 `source=scheduler`、任务 ID 和执行 ID，调用 `AgentRuntime.chat`。
3. 以 `role=assistant`、相同 metadata 写入结果。
4. 写入 `SUCCEEDED`/`FAILED` 执行记录，并创建站内通知。

## 配置

配置位于 `src/main/resources/application.yml`，均可通过环境变量覆盖：

```yaml
agent:
  platform:
    scheduled-tasks:
      enabled: true
      poll-ms: 30000
      initial-delay-ms: 15000
      batch-size: 20
      lease-ms: 120000
      default-timezone: Asia/Shanghai # 留空时使用 JVM 默认时区
```

Cron 使用 Spring `CronExpression`：支持标准 6 位（秒、分、时、日、月、周）格式；输入 5 位 Unix 格式时自动在秒位前补 `0`。任务按自身 `timezone` 计算下一次执行时间并以 UTC ISO-8601 存储。

## 数据存储

SQLite 模式启动时由 `ScheduledTaskStore` 创建以下表，不依赖 Flyway 或项目中不存在的迁移初始化器：

- `platform_scheduled_tasks`：定义、所有权、Agent/会话绑定、Cron、状态、下一次执行时间和租约。
- `platform_scheduled_task_runs`：每次执行的状态、时间、响应和错误。
- `platform_user_notifications`：用户站内通知和已读时间。

file 模式将同样的数据写入 `workspace/cache/scheduled-tasks.json`，采用临时文件替换写入。

任务状态使用 `ACTIVE` / `PAUSED`；API 同时返回布尔字段 `enabled` 方便前端使用。

## HTTP API

所有接口都要求登录，账号从 `platform_session` Cookie 解析；不会使用 URL 中的 `userId` 做授权。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/scheduled-tasks` | 当前用户的任务列表 |
| POST | `/api/scheduled-tasks` | 创建任务 |
| GET | `/api/scheduled-tasks/{id}` | 任务详情 |
| PUT | `/api/scheduled-tasks/{id}` | 更新任务 |
| DELETE | `/api/scheduled-tasks/{id}` | 删除任务 |
| POST | `/api/scheduled-tasks/{id}/enable` | 启用并重新计算下一次执行时间 |
| POST | `/api/scheduled-tasks/{id}/disable` | 暂停 |
| POST | `/api/scheduled-tasks/{id}/run-now` | 立即执行并返回执行记录 |
| GET | `/api/scheduled-tasks/{id}/runs?limit=50` | 执行历史 |
| GET | `/api/notifications?unreadOnly=false&limit=50` | 通知列表和 `unreadCount` |
| POST | `/api/notifications/{id}/read` | 标记单条已读 |
| POST | `/api/notifications/read-all` | 全部标记已读 |

创建/更新请求字段：`name`、`prompt`、`agent_id`、`session_id`、`cron`（或 `cron_expression`）、`timezone`、`status`。未传 `session_id` 时，平台为该任务创建一个绑定会话。SQLite 模式下传入已有会话必须属于当前用户和组织。

## Agent Tools

默认工具配置登记在 `src/main/resources/tools.yml`，绑定到 Agent 后可调用：

`schedule_create` / `schedule_list` / `schedule_get` / `schedule_get_runs` / `schedule_pause` / `schedule_resume` / `schedule_delete` / `schedule_run_now`

工具由 `AgentCapabilityAssembler` 注册 `ScheduledTaskTools`。账号来自运行时上下文，不允许模型通过工具参数伪造用户身份；没有认证运行时上下文时工具会拒绝执行。

## 前端约定

前端应调用上述 `/api` 接口，不再拼接 `userId` 查询参数。任务列表展示 `status`、`next_run_at`、最近执行结果；通知铃铛使用 `unreadCount` 轮询或在任务执行后刷新。会话消息可根据 `metadata.source=scheduled_task` 显示“定时触发”。

## 验证

后端验证命令：

```powershell
mvn -q -DskipTests compile
mvn -q test
```

重点覆盖 Cron 的 5/6 位兼容、任务所有权、暂停后不领取、租约领取、执行记录和通知已读状态。
