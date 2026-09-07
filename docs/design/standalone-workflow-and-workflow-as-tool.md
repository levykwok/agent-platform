# 独立 Workflow 与 Workflow as Tool 设计

## 1. 目标与边界

Workflow 是平台一级资产，与 AgentDefinition 的直接编排模型分离管理。

Agent 自身仍然有完整的 Agent 间编排：

- `SINGLE`：运行当前 Agent；
- `ROUTER`：在 Agent 之间路由；
- `SUPERVISOR`：由当前 Agent 委派子 Agent。
- `PIPELINE`：按显式步骤顺序调用 Agent，并可根据步骤状态跳转。

独立 Workflow 由编排中心创建、编辑、校验、发布和运行。它可以通过 `agent.invoke` 节点调用已发布 Agent，但它的节点、端口和边不进入 AgentDefinition；Agent 的 `PIPELINE` 步骤也不转换成画布节点。

反向调用采用 `Workflow as Tool`：已发布 Workflow 先注册成一个有版本、有 Schema、有权限边界的工具，Agent 的工具配置只引用注册后的工具 ID。

## 2. 领域关系

```text
WorkflowAsset
  └─ 发布版本
       └─ WorkflowToolRegistration
              └─ Agent.toolRefs[]

Agent
  ├─ orchestration.mode=PIPELINE → Agent PipelineStep[]
  └─ toolRefs[] → WorkflowToolRegistration

Workflow
  └─ agent.invoke 节点 → 已发布 Agent
```

禁止以下关系：

- Agent 配置内保存 Workflow nodes/edges；
- 通过 `workflow:<workflowId>` 直接绕过工具注册调用 Workflow；
- 画布根据节点数组顺序自动创建边。

## 3. Workflow 唯一数据模型

```json
{
  "workflow_id": "order-review",
  "version": 3,
  "status": "DRAFT",
  "name": "订单审核",
  "description": "固定业务流程",
  "domain": "platform",
  "trigger_type": "manual",
  "input_schema": {"type": "object"},
  "output_schema": {"type": "object"},
  "nodes": [
    {
      "nodeId": "input",
      "type": "workflow.input",
      "inputPorts": [],
      "outputPorts": [{"portId": "value", "direction": "output"}]
    }
  ],
  "edges": [
    {
      "edgeId": "input-to-review",
      "from": {"nodeId": "input", "portId": "value"},
      "to": {"nodeId": "review", "portId": "value"},
      "kind": "data",
      "binding": {},
      "condition": {},
      "defaultEdge": true
    }
  ]
}
```

`nodes` 和 `edges` 是运行时唯一真相：

- 节点 ID 在 Workflow 内唯一；
- 边只能从输出端口连接到输入端口；
- 边必须引用存在的节点和端口；
- `data` 边传递输入输出数据；
- `control` 边只负责条件选择；
- 画布位置只放在节点 `config.canvas_position`，不参与运行；
- 不允许隐式顺序边；
- 不允许节点 `transitions` 字段；
- 发布前校验端口、Schema、必填输入、边引用和环路。

Workflow 必须有且只能有一个 `workflow.input` 和一个 `workflow.output` 边界节点。边界节点由 Workflow 画布创建，不由 Agent 配置生成。

## 4. Workflow Tool 注册

注册记录：

```json
{
  "tool_id": "workflow_tool_order_review",
  "workflow_id": "order-review",
  "workflow_version": 3,
  "name": "order_review",
  "description": "执行订单审核流程",
  "input_schema": {"type": "object"},
  "allowed_agents": ["customer-service-agent"],
  "enabled": true,
  "status": "ACTIVE"
}
```

注册规则：

1. 只能注册 `PUBLISHED` Workflow；
2. 注册时固定 Workflow 版本；
3. Workflow 发布新版本后，旧注册不自动漂移，必须显式更新注册版本；
4. 非平台管理员必须显式填写 `allowed_agents`，且只能绑定自己有权管理的 Agent；平台管理员留空时表示允许任意显式引用该工具的 Agent；
5. Agent 只保存 `tool_id`，不保存 `workflow_id`、节点或边；
6. Agent 运行时只通过注册记录解析 Workflow；
7. Workflow 被取消发布或版本不匹配时，工具不可用并返回明确错误。

建议接口：

```text
GET    /platform/frontend/workflow-tools
POST   /platform/frontend/workflow-tools
PUT    /platform/frontend/workflow-tools/{toolId}
DELETE /platform/frontend/workflow-tools/{toolId}
```

创建/更新请求至少包含：`workflow_id`、`name`、`description`、`allowed_agents`、`enabled`。服务端根据当前已发布 Workflow 补齐 `workflow_version` 和 `input_schema`。

## 5. 运行链路

### Workflow 直接运行

```text
编排中心 / API
  → WorkflowAssetService.requirePublished
  → WorkflowRuntime
  → nodes + edges
```

### Agent 调用 Workflow Tool

```text
Agent.toolRefs[workflow_tool_order_review]
  → WorkflowToolRegistry.requireForAgent
  → WorkflowAssetService.requirePublished(version)
  → WorkflowTool
  → WorkflowRuntime
```

Workflow 节点调用 Agent 是 `agent.invoke` 的正向依赖；Agent 调 Workflow 必须经过 Workflow Tool 注册。发布和运行时都要阻止循环依赖。

## 6. 画布规则

- 从节点输出端口拖到另一个节点输入端口才创建边；
- 拖入节点只创建节点，不创建边；
- 新建节点 ID 必须稳定且不重复；
- 删除节点同时删除所有关联边；
- 删除端口同时删除引用该端口的边；
- 修改节点 ID 同步重写所有边端点；
- 移动节点只改变画布位置，不改变图结构；
- 每个端口按自身索引计算 Y 坐标，不能把多个端口都画在节点中线；
- 保存和发布前进行一次完整图校验。

## 7. Agent 配置边界

Agent 管理页的“编排”显示 `SINGLE`、`ROUTER`、`SUPERVISOR`、`PIPELINE`。其中 `PIPELINE` 只编辑 Agent 目标和步骤顺序；`WORKFLOW` 专指独立 Workflow 画布资产，不在该页面编辑。

Agent 的 `PIPELINE` 配置示例：

```json
{
  "orchestration": {
    "mode": "PIPELINE",
    "workflow": [
      {"stepId": "research", "agentId": "research-agent", "instruction": "先调研"},
      {"stepId": "write", "agentId": "writer-agent", "instruction": "再整理输出"}
    ]
  }
}
```

Agent 的工具区域显示已注册的 Workflow Tool，保存时只写入：

```json
{
  "tool_scope": {
    "include": ["workflow_tool_order_review"]
  }
}
```

## 8. 模型隔离要求

- Agent 直接编排只使用 `PipelineStep` / `PipelineTransition`；
- 独立 Workflow 只使用 `WorkflowNode` / `WorkflowPort` / `WorkflowEdge`；
- 独立 Workflow 的 `WorkflowNode` 不包含 `transitions`；
- 两套模型不做转换桥接，也不按数组顺序推断画布连线；
- Workflow Tool 是二者之间唯一的反向调用注册边界。

## 9. 已实现的发布、运行与恢复约束

- `platform_workflows` 保存可编辑草稿；`platform_workflow_versions` 保存不可变发布快照；`platform_workflow_publications` 保存唯一活动版本指针。
- 编辑已发布 Workflow 只产生草稿，不改变线上活动版本；再次发布递增版本号。取消发布只删除活动指针，不删除历史快照。
- Workflow Tool 与定时触发器都固定 `workflow_version`，新版本发布后不会自动漂移；Workflow 整体取消发布后，固定版本也不可再发起新调用。
- 独立运行使用持久 Run、节点 Step、SSE 事件、租约和节点级 checkpoint。并行分支恢复时跳过已提交节点，租约丢失后的 checkpoint 写入会被 fencing。
- `human.approval` 到达后把 Run 持久化为 `WAITING` 并释放执行资源；审批接口恢复后由服务端重新取得租约继续执行，不依赖原 SSE 客户端仍在线。
- 调用链保留 tenant、user、session、root task、parent task 和深度；Workflow Tool 不创建硬编码平台身份。

## 10. 节点与安全策略

当前运行时支持边界、返回、Agent/ReAct、LLM、HTTP、数据库查询/写入、数据转换、消息发送、Skill、MCP、子流程、条件、Foreach、并行/汇聚和人工审批节点。

- HTTP 仅允许 `http/https`，拒绝 URL 凭据、localhost、私网/链路本地/组播地址；敏感 Header 必须使用 `env:` 引用。写请求重试必须配置幂等键。
- JDBC URL 必须命中 `AGENT_PLATFORM_WORKFLOW_JDBC_ALLOW_PREFIXES`；用户名和密码必须使用 `env:` 引用；SQL 仅通过 PreparedStatement 绑定参数。
- `database.write` SQL 必须恰好包含一次 `{{idempotency_key}}`，运行时替换为绑定参数，并使用 root run + node 生成稳定键。目标表应对对应列建立唯一约束。
- `skill.invoke` / `mcp.invoke` 会把目标 Agent 投影成 SINGLE 模式，移除普通 Tool、其他 Skill/MCP 和原编排，只暴露被选择的能力。
- 子 Workflow、Workflow Tool 和 Foreach-Workflow 在发布/注册时固定版本；运行时维护调用栈并拒绝递归环。
- 发布 Workflow 时按当前用户校验 Agent、Skill/MCP 宿主 Agent 与子 Workflow 的可见性，Workflow Tool 绑定还会校验目标 Agent 的管理权限，避免跨租户间接调用私有资产。
- 无人值守定时触发器不接受包含 `human.approval` 的 Workflow；此类流程应使用手动或 API 触发。

## 11. 触发入口

```text
POST /platform/frontend/workflows/{workflowId}/run
POST /platform/frontend/workflows/{workflowId}/run/stream

GET  /api/v1/workflows
POST /api/v1/workflows/{workflowId}/run
```

`/api/v1` 使用现有 API Key 认证、限流与审计。Workflow 必须在 Key 的 `allowed_agent_ids` 中显式写为 `workflow:{workflowId}`；空白名单只放行可见 Agent，不隐式开放 Workflow。外部调用返回 `request_id`、`run_id` 和运行观测地址。定时触发器复用 `/api/scheduled-tasks`，创建请求使用 `workflow_id` 代替 `agent_id`。

## 12. 内置公共示例

- `workflow-demo-transform`：输入 → 数据转换 → 输出；无模型依赖。
- `workflow-demo-parallel`：输入 → 并行分叉 → 两个数据转换 → 汇聚 → 输出；无模型依赖。

示例只在 ID 不存在时安装，不覆盖用户或管理员后续修改。可通过 `agent.platform.workflow.demo.enabled=false` 关闭自动安装。

## 13. SQLite 性能与迁移约束

平台连接启用 SQLite WAL、`synchronous=NORMAL` 和 5 秒 busy timeout，降低 Workflow 事件、Step 与 checkpoint 高频落库时的写锁等待。WAL 模式运行期间，最新事务可能仍位于同目录的 `-wal` 文件中，因此不能只热拷贝主 `.db` 文件。迁移或备份应停止服务后再复制数据库，或使用 SQLite 在线备份能力并校验备份；恢复时数据库及其工作区资产仍须保持同一租户边界。
