# 独立 Workflow 与 Workflow as Tool 设计

## 1. 目标与边界

Workflow 是平台一级资产，与 AgentDefinition 的直接编排模型分离管理。

Agent 自身仍然有完整的 Agent 间编排：

- `SINGLE`：运行当前 Agent；
- `ROUTER`：在 Agent 之间路由；
- `SUPERVISOR`：由当前 Agent 委派子 Agent。
- `WORKFLOW`：按显式步骤顺序调用 Agent，并可根据步骤状态跳转。

独立 Workflow 由编排中心创建、编辑、校验、发布和运行。它可以通过 `agent.invoke` 节点调用已发布 Agent，但它的节点、端口和边不进入 AgentDefinition；Agent 的 `WORKFLOW` 步骤也不转换成画布节点。

反向调用采用 `Workflow as Tool`：已发布 Workflow 先注册成一个有版本、有 Schema、有权限边界的工具，Agent 的工具配置只引用注册后的工具 ID。

## 2. 领域关系

```text
WorkflowAsset
  └─ 发布版本
       └─ WorkflowToolRegistration
              └─ Agent.toolRefs[]

Agent
  ├─ orchestration.mode=WORKFLOW → Agent WorkflowStep[]
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
4. `allowed_agents` 非空时，只允许其中的 Agent 绑定该工具；
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

Agent 管理页的“编排”显示 `SINGLE`、`ROUTER`、`SUPERVISOR`、`WORKFLOW`。其中 `WORKFLOW` 只编辑 Agent 目标和步骤顺序；独立 Workflow 画布不在该页面编辑。

Agent 的 `WORKFLOW` 配置示例：

```json
{
  "orchestration": {
    "mode": "WORKFLOW",
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

- Agent 直接编排只使用 `WorkflowStep` / `WorkflowTransition`；
- 独立 Workflow 只使用 `WorkflowNode` / `WorkflowPort` / `WorkflowEdge`；
- 独立 Workflow 的 `WorkflowNode` 不包含 `transitions`；
- 两套模型不做转换桥接，也不按数组顺序推断画布连线；
- Workflow Tool 是二者之间唯一的反向调用注册边界。
