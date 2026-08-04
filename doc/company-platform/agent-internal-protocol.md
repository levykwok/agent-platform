# Agent 内部通信协议设计

## 1. 目标

本协议用于同一平台内部的 Agent、Router 和 Workflow 之间传递任务，不直接绑定 HTTP、SSE、A2A 或其他网络协议。

当前目标：

- 统一普通 Agent、Router、Workflow 的输入输出格式
- 支持嵌套编排和多级任务链路
- 支持运行状态、进度、错误、取消和审计
- 为未来跨进程调用保留扩展空间

不在本阶段解决：

- 跨组织 Agent 发现
- 公网身份认证
- 复杂多方协商
- 并行执行和分布式调度

## 2. 核心概念

```text
AgentDefinition  = 平台可配置的 Agent 或编排入口
AgentTask        = 一次具体执行任务
TaskRequest      = 调用方发给目标 Agent 的请求
TaskResult       = 目标 Agent 返回的最终结果
ProgressEvent    = 执行过程中的状态和进度事件
```

编排关系：

```text
用户请求
  └─ root task
       ├─ Router 选择目标
       ├─ Workflow 创建步骤 task
       └─ 目标 Agent 返回 result
```

## 3. TaskRequest

```json
{
  "task_id": "task_01J...",
  "parent_task_id": null,
  "root_task_id": "task_01J...",
  "source_agent_id": "platform-entry",
  "target_agent_id": "research-flow",
  "tenant_id": "platform",
  "user_id": "user-001",
  "session_id": "session-001",
  "step_id": "research",
  "input": {
    "text": "分析这份技术方案",
    "data": {}
  },
  "deadline_at": "2026-07-22T10:00:00Z",
  "metadata": {
    "trace_id": "trace-001",
    "attempt": 1
  }
}
```

字段约定：

| 字段 | 说明 |
| --- | --- |
| `task_id` | 当前任务唯一 ID，重试不变或按实现策略生成 attempt ID |
| `parent_task_id` | 直接调用方任务，用于嵌套编排追踪 |
| `root_task_id` | 整条用户请求链路的根任务 |
| `source_agent_id` | 发起调用的 Agent 或编排入口 |
| `target_agent_id` | 目标 AgentDefinition |
| `step_id` | 当前 Workflow 步骤，可为空 |
| `input` | 结构化输入，至少支持 `text` 和 `data` |
| `deadline_at` | 整体截止时间，不由下游自行延长 |
| `metadata` | trace、重试、实验标记等非业务字段 |

## 4. TaskResult

```json
{
  "task_id": "task_01J...",
  "status": "COMPLETED",
  "output": {
    "content": "技术方案的主要风险是……",
    "data": {
      "status": "complete",
      "risk_count": 3
    }
  },
  "error": null,
  "usage": {
    "input_tokens": 1200,
    "output_tokens": 450,
    "duration_ms": 8200
  },
  "metadata": {
    "target_agent_id": "research-flow"
  }
}
```

状态建议：

```text
COMPLETED  成功完成
FAILED     执行失败
CANCELLED  被用户或上游取消
WAITING    等待人工确认或外部事件
TIMEOUT    超过截止时间
REJECTED   权限或策略拒绝
```

错误统一放在 `error`：

```json
{
  "code": "WORKFLOW_STEP_TIMEOUT",
  "message": "research step exceeded deadline",
  "retryable": true,
  "details": {}
}
```

## 5. ProgressEvent

```json
{
  "event_id": "event_01J...",
  "task_id": "task_01J...",
  "root_task_id": "task_01J...",
  "node_id": "research",
  "type": "TASK_COMPLETED",
  "created_at": "2026-07-22T09:58:20Z",
  "summary": "research-flow completed",
  "payload": {
    "status": "complete"
  }
}
```

事件类型建议：

```text
TASK_CREATED
TASK_STARTED
ROUTE_SELECTED
NODE_STARTED
MODEL_CALL_STARTED
MODEL_CALL_COMPLETED
TOOL_CALL_STARTED
TOOL_CALL_COMPLETED
TRANSITION_SELECTED
TASK_WAITING
TASK_COMPLETED
TASK_FAILED
TASK_CANCELLED
```

## 6. Workflow 分支协议

Agent 输出不直接决定任意目标步骤，只输出状态和内容：

```json
{
  "status": "needs_review",
  "content": "研究结果存在不确定性，需要审核。"
}
```

Workflow 根据配置决定跳转：

```yaml
transitions:
  - when: needs_review
    nextStepId: reviewer
  - when: complete
    nextStepId: writer
  - defaultTransition: true
    nextStepId: writer
```

约束：

- `status` 只能匹配当前步骤声明的状态
- `nextStepId` 必须存在且通过权限校验
- 第一阶段只允许向后跳转
- Agent 不可直接绕过 Workflow 配置指定任意步骤
- 无法解析结构化输出时进入默认分支或失败策略

## 7. 嵌套编排

嵌套调用使用新的子任务，不复用父任务 ID：

```text
root task: entry
  └─ child task: router-1
       └─ child task: review-flow
            └─ child task: reviewer-step
```

每个子任务必须携带：

```text
parent_task_id
root_task_id
trace_id
source_agent_id
target_agent_id
```

这样可以支持：

- 父子任务审计
- 子任务超时向上传播
- 子任务取消
- 按 Agent、步骤、任务统计耗时和 Token

## 8. 当前代码映射

```text
ChatRequest          → TaskRequest.input + 用户上下文
ChatResponse         → TaskResult.output
AgentEventEnvelope   → ProgressEvent
AgentDefinition      → target_agent_id 对应的执行定义
AgentRuntimeService  → TaskDispatcher
```

现阶段先在 Java 进程内使用这些语义，不要求马上引入消息队列或 HTTP。后续如果需要跨进程，只需为同一组消息增加 Transport Adapter。

## 9. 建议落地顺序

1. 将 `ChatRequest/ChatResponse` 的内部调用包装成 `TaskRequest/TaskResult`。
2. 为 `AgentRuntimeService` 增加统一 task_id、parent_task_id 和 root_task_id。
3. 将编排事件统一成 `ProgressEvent` 字段口径。
4. 增加 `TaskResult` 的结构化错误和 usage。
5. 再考虑暂停、恢复、取消和跨进程 Transport Adapter。
