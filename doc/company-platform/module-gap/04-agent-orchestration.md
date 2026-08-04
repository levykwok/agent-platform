# 智能体编排

## 结论

AgentScope Harness 原生支持子 Agent 和 workspace 声明式 subagent，但平台级路由、工作流、版本化编排和可视化编排仍需要我们做。

编排不应该绕过 Harness，而应该在平台层决定“调用哪个 HarnessAgent / 如何组合调用”。

## 平台模块功能设计

本模块负责定义多个 Agent 或多个执行节点之间的协作方式。

核心功能：

```text
1. 单 Agent 模式：直接调用一个 AgentDefinition。
2. 路由模式：根据规则、关键词、分类模型或策略选择目标 Agent。
3. 工作流模式：按步骤串行执行多个 Agent 或节点。
4. Supervisor 模式：主 Agent 管理多个子 Agent，并在推理中决定是否委派。
5. 子 Agent 绑定：定义 binding_id、target_agent_id、description、tools、是否对用户可见。
6. 节点资源控制：为节点配置模型、工具、MCP、Skill 可见范围。
7. 输入输出映射：上游节点输出如何作为下游节点输入。
8. 编排运行事件：记录 node_started、node_finished、node_failed。
9. 编排版本管理：Agent 发布时固化 orchestration 配置。
10. 可视化编辑预留：后续以图形方式编辑节点和边。
```

输入资源：

```text
AgentDefinition
OrchestrationPolicy
RouteRule
WorkflowStep
SubagentBinding
```

输出资源：

```text
最终被调用的 AgentDefinition
工作流执行结果
子 Agent 声明
编排事件记录
```

## AgentScope 对应实现

AgentScope 能力：

```text
SubagentDeclaration
workspace/subagents/*.md
SubagentsMiddleware
DynamicSubagentsMiddleware
agent_spawn
后台子任务
子 Agent 事件转发
```

AgentScope 也有多 Agent 文档方向：

```text
routing
workflow
supervisor
handoff
subagent
```

## 当前平台已实现

```text
OrchestrationPolicy
OrchestrationMode
SubagentBinding
RouteRule
WorkflowStep
AgentRuntimeService.route(...)
AgentRuntimeService.runWorkflow(...)
AgentScopeHarnessFactory.subagent(...)
```

当前模式：

```text
SINGLE
ROUTER
WORKFLOW
SUPERVISOR
```

## 调用链路

路由：

```text
AgentRuntimeService.chat(agentId, request)
  -> definition.orchestration().mode()
  -> route(definition, request)
  -> runSingle(targetDefinition, request)
```

工作流：

```text
input = user message
for each WorkflowStep:
  stepAgent = definition(step.agentId)
  output = runSingle(stepAgent, stepRequest)
return final output
```

子 Agent：

```java
builder.subagent(SubagentDeclaration.builder()
    .name(binding.bindingId())
    .description(binding.description())
    .workspace(target.workspace())
    .model(target.model())
    .tools(binding.toolRefs())
    .build())
```

## 还缺什么

```text
可视化编排页面
编排 DSL 的条件、分支、并行、重试
节点级工具/Skill/MCP 可见性
节点级模型配置
HITL 节点
编排运行事件追踪
编排版本快照
失败恢复策略
```

## 推荐下一步

```text
P0: 先把 Agent 编排配置放进 Agent 管理页面
P1: workflow 增加 step input/output 映射
P2: runtime 记录每个编排 step 的 AgentRunEvent
P3: 支持节点级 toolRefs/mcpRefs/skillRefs 覆盖
```

## 接口调用设计

### 编排配置示例

路由：

```json
{
  "mode": "ROUTER",
  "routes": [
    {
      "rule_id": "alarm",
      "contains": "告警",
      "target_agent_id": "monitoring_agent"
    },
    {
      "rule_id": "ops",
      "contains": "部署",
      "target_agent_id": "ops_agent"
    }
  ]
}
```

工作流：

```json
{
  "mode": "WORKFLOW",
  "workflow": [
    {
      "step_id": "diagnose",
      "agent_id": "ops_diagnoser",
      "instruction": "先定位问题原因"
    },
    {
      "step_id": "report",
      "agent_id": "report_agent",
      "instruction": "把诊断结果整理成报告"
    }
  ]
}
```

Supervisor / 子 Agent：

```json
{
  "mode": "SUPERVISOR",
  "subagents": [
    {
      "binding_id": "reviewer",
      "target_agent_id": "code_review_agent",
      "description": "代码审查专家",
      "tool_refs": ["read_file", "grep_files"],
      "expose_to_user": true
    }
  ]
}
```

### 平台运行时调用

```java
switch (definition.orchestration().mode()) {
    case ROUTER -> runSingle(route(definition, request), request);
    case WORKFLOW -> runWorkflow(definition, request);
    case SINGLE, SUPERVISOR -> runSingle(definition, request);
}
```

### AgentScope 子 Agent 调用

```java
SubagentDeclaration declaration = SubagentDeclaration.builder()
    .name(binding.bindingId())
    .description(binding.description())
    .workspace(target.workspace())
    .model(target.model())
    .tools(binding.toolRefs())
    .build();

HarnessAgent.builder().subagent(declaration);
```

### 前端接口建议

```http
PUT /platform/frontend/agents/{agentId}/orchestration
Content-Type: application/json

{
  "mode": "WORKFLOW",
  "workflow": [...],
  "routes": [],
  "subagents": []
}
```

### 运行事件记录建议

每个编排节点写入：

```text
AgentRunEvent.event_type = node_started / node_finished / node_failed
payload.step_id
payload.agent_id
payload.input_ref
payload.output_ref
```

### DB 化边界

编排可以先作为 AgentSpec JSON 字段存储，后续复杂化再拆：

```text
agent_orchestration
agent_orchestration_node
agent_orchestration_edge
```

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

## 2026-07-09 更新：编排实现边界

当前平台应把编排定义放在 AgentDefinition 中，但实际执行仍通过我们封装的 runtime 调 AgentScope HarnessAgent。

编排层级：

```text
SINGLE：单 Agent 直接运行。
WORKFLOW：平台 runtime 按 step 顺序调用多个 AgentScope HarnessAgent。
SUPERVISOR：平台 runtime 先发 supervisor 事件，再调主 agent；复杂路由后续补。
```

当前验证结论：

```text
1. Workflow 不等于 AgentScope 自动多智能体魔法，当前是平台 runtime 逐步调用 agent。
2. 每个 step 应在事件流里明确显示 agent_id、step_id、开始、结束、输出摘要。
3. 最终流式输出只对最后一步有意义；中间步骤可展示事件和摘要。
4. 多 agent 执行过程不能把所有事件压成一个 activity 框，要按 agent/step 分组。
```

缺口：

```text
1. Supervisor 的路由规则和动态选择还不完整。
2. Workflow 的错误恢复、重试、跳转、条件分支还缺。
3. 编排可视化需要显示 step DAG、每步输入输出、耗时、调用模型、工具调用。
4. 编排定义后续应从 YAML/SQLite 迁移为统一 AgentDefinition 存储。
```
