# 强编排与对话式业务流程方案

## 1. 背景与目标

当前平台的编排能力主要围绕 Agent、Skill 和工具调用展开，适合构建智能体协作流程。但真实业务通常还包含大量确定性的系统步骤，例如查询订单、调用审批接口、写入数据库、发送通知和人工确认。

本方案将编排中心扩展为“业务流程引擎 + AI 节点”模型：由编排器强控制流程，LLM、ReActAgent 和已有 Agent 作为流程中的可控执行节点。同时，固定流程可以被对话 Agent 作为业务工具调用，从而覆盖按钮触发、API 触发和自然语言对话三类入口。

目标：

- 支持 API、数据库、数据处理、审批等固定业务节点；
- 支持在固定流程中嵌入 LLM、ReActAgent 和已有 Agent；
- 支持对话 Agent 调用已发布的 Workflow；
- 保证流程边界、权限、重试、超时和错误策略可控；
- 避免 Agent、Workflow 和子流程之间产生循环调用。

## 2. 产品定位

不把业务流程放到 Agent 管理页面下面，也不再维护两套执行引擎。产品一级模块统一命名为“编排中心”：

```text
编排中心
├── 业务流程
├── 智能体流程
└── 子流程 / 模板
```

三类流程共用同一个 Workflow Runtime，只是节点类型和默认画布不同：

- **业务流程**：固定业务节点为主，AI 节点为辅；
- **智能体流程**：Agent、Skill、MCP 和工具节点为主；
- **混合流程**：API、数据库、LLM、ReActAgent 和 Agent 混合编排。

“智能体流程”不是另一套运行时，而是业务流程引擎上的一种流程模板。

## 3. 强编排模型

强编排的原则是：流程图控制主路径，LLM 只能在节点内部完成限定任务，不能任意修改流程图或绕过业务节点。

典型流程：

```text
获取订单 API
  → 查询客户 API
  → 数据清洗
  → LLM 判断订单状态
  → 条件分支
  → ReActAgent 补充调查
  → 审批 API
  → 通知 API
```

LLM 节点和 ReActAgent 节点可以有内部循环，但循环必须受到最大轮次、超时、Token 预算和停止条件约束。流程的外部顺序、分支和副作用仍由 Workflow Runtime 控制。

## 4. 节点类型

节点目录按职责分组：

### 业务节点

- `http.request`：调用固定 HTTP API；
- `database.query` / `database.write`：查询或写入数据库；
- `data.transform`：字段映射、JSON 转换和数据清洗；
- `file.operation`：文件读取、生成和归档；
- `message.send`：发送站内信、邮件或其他通知。

### AI 节点

- `llm.chat`：一次结构化 LLM 调用；
- `agent.invoke`：调用平台已有 Agent；
- `agent.react`：执行有边界的 ReActAgent 循环；
- `skill.invoke`：执行一个已注册 Skill；
- `mcp.invoke`：调用已授权 MCP 工具。

### 控制节点

- `condition`：在声明好的分支中选择；
- `foreach` / `loop`：批量处理或有上限的循环；
- `parallel` / `join`：并行执行与汇聚；
- `human.approval`：人工审批或补充信息；
- `subflow.invoke`：调用已发布子流程；
- `return`：结束流程并输出结果。

所有节点统一支持输入映射、输出 Schema、超时、重试、错误策略和运行事件。

## 5. 对话触发

Workflow 不只支持按钮触发，触发器与流程定义分离：

- `manual`：界面手动运行；
- `chat`：从对话消息进入；
- `api`：由外部 API 调用；
- `webhook`：由外部事件推送；
- `schedule`：定时运行；
- `event`：由平台或业务事件触发。

对话场景采用两层结构：

```text
用户消息
  → 对话 Agent
  → 选择已授权的 Workflow Tool
  → 固定业务流程
  → 返回结果或等待用户输入
```

如果一个对话入口只绑定一个流程，可以直接进入流程，不需要额外 Router。如果一个 Agent 允许调用多个业务流程，则由 Agent 的受限工具选择完成路由。只有在多个 Agent、多个流程共用一个入口且需要统一分流时，才增加独立的 Chat Router。

Router 或 Agent 的输出只能是平台已发布并授权的流程 ID，不能自由生成节点或任意调用 API。

`Conversation` 和 `Run` 分开管理：

- `Conversation` 保存多轮消息、会话变量和用户上下文；
- `Run` 表示某一次 Workflow 执行，记录节点状态、事件、耗时和输出；
- 流程需要用户补充信息时进入 `waiting`；
- 用户回复后恢复原 Run，而不是重新执行整条流程。

## 6. Workflow 作为 Agent Tool

已发布的 Workflow 可以暴露为一个带 Schema 的业务工具。它不是普通的无约束工具，而是一个拥有固定输入、权限和版本的流程入口。

示例：

```json
{
  "name": "query_order",
  "description": "查询订单状态并返回客户可读结果",
  "workflowId": "order-query-flow",
  "version": 3,
  "inputSchema": {
    "type": "object",
    "required": ["orderId"],
    "properties": {
      "orderId": {"type": "string"}
    }
  },
  "allowedAgents": ["customer-service-agent"]
}
```

统一返回：

```json
{
  "status": "completed",
  "runId": "run_123",
  "output": {}
}
```

需要用户补充信息时返回：

```json
{
  "status": "waiting",
  "runId": "run_123",
  "question": "请提供收货地址"
}
```

Workflow Tool 只允许引用已发布版本。发布时配置工具名称、描述、输入参数、允许调用的 Agent、租户权限和是否允许对话触发。

## 7. Workflow 嵌套已有 Agent

Workflow 可以通过 `agent.invoke` 节点调用已有 Agent，不复制 Agent 定义：

```text
查询订单 API
  → order-analyzer Agent
  → 条件判断
  → customer-service Agent
  → 通知 API
```

每次嵌套调用创建子 Run，并记录：

- `parentRunId`；
- Agent ID 和固定版本；
- 输入、输出和上下文映射；
- Token、耗时、重试和错误；
- waiting 状态及恢复关系。

Agent 也可以反向调用 Workflow Tool，但父流程的权限、超时和预算必须传递给子调用。子调用不能扩大权限或绕过父流程的取消操作。

## 8. 循环调用防护

### 发布时检查

构建 Workflow、Agent 和 Workflow Tool 的依赖图。如果出现以下闭环，禁止发布：

```text
Workflow A → Agent B → Workflow C → Workflow A
```

发布错误必须展示完整调用链，方便定位循环来源。

### 运行时检查

每次调用携带调用栈：

```json
{
  "callStack": [
    "workflow:order-flow",
    "agent:order-analyzer",
    "workflow:notify-flow"
  ],
  "depth": 3
}
```

调用前检查：

- 目标是否已经存在于当前调用栈；
- 是否超过最大嵌套深度；
- 是否超过总执行时间、Token 或子任务数量；
- 是否触发重复调用熔断。

Workflow 内部的 `foreach` 和 `loop` 属于合法控制流，不应仅因为节点重复出现就被判定为嵌套循环。它们必须单独配置最大迭代次数和停止条件。

## 9. 核心数据模型

```text
WorkflowDefinition
  id
  name
  type
  version
  triggers
  inputSchema
  nodes
  edges
  policies

WorkflowNode
  nodeId
  type
  config
  inputMappings
  outputSchema
  retryPolicy
  timeoutPolicy

Run
  runId
  parentRunId
  conversationId
  workflowId
  status
  currentNode
  callStack
  startedAt
  finishedAt
```

## 10. 界面设计

编排中心提供统一画布，节点面板分为：

```text
业务节点 | AI 节点 | 控制节点 | 集成节点
```

Workflow 发布时提供两个开关：

- **允许被 API/手动触发**；
- **暴露为 Agent Tool**。

Workflow Tool 配置页提供：工具名称、描述、输入 Schema、允许调用的 Agent、权限、版本和等待策略。

运行详情统一展示父子 Run、节点耗时、API 调用、Agent 调用、等待点和恢复链路。

## 11. 分阶段实施

### 第一阶段：统一流程模型

- 将当前 Workflow Step 扩展为通用 Workflow Node；
- 保留已有顺序、条件、并行和嵌套能力；
- 增加节点输入输出映射和统一错误策略；
- 增加 `agent.invoke`、`llm.chat` 和 `subflow.invoke`。

### 第二阶段：业务节点

- 增加 HTTP/API 节点；
- 增加数据库、转换、循环、审批和通知节点；
- 增加凭证引用、超时、重试和幂等配置；
- 在前端提供业务节点目录和配置表单。

### 第三阶段：对话和 Workflow Tool

- 将已发布 Workflow 暴露为带 Schema 的 Tool；
- 对话 Agent 只看到被授权的流程工具；
- 接入 Conversation、Run、Waiting 的关联；
- 完成对话补参和原 Run 恢复。

### 第四阶段：安全与可靠性

- 发布期依赖图和循环检测；
- 运行期调用栈、深度、预算和熔断；
- 父子 Run 链路追踪；
- 节点级指标、成本和失败分析。

## 12. 设计边界

- 不把每个 API 都包装成 Agent；
- 不允许 LLM 在强编排流程中自由修改流程图；
- 不为业务流程和智能体流程维护两套 Runtime；
- 不把 Router 做成拥有任意工具权限的超级 Agent；
- 不把“Workflow 作为 Tool”与普通工具混为一谈，必须具备版本、Schema、权限和运行状态。
