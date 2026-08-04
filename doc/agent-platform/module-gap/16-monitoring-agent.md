# 监盘智能体

## 结论

监盘智能体同样是业务 Agent，不是 AgentScope 自带模块。AgentScope 提供运行底座，平台需要提供监盘业务上下文、告警工具、展示组件和安全策略。

## 平台模块功能设计

本模块是面向监盘和告警分析的业务智能体，重点处理实时告警、遥测状态、关联分析和处置建议。

核心功能：

```text
1. 告警解读：解释告警含义、严重程度、影响范围。
2. 告警归因：结合遥测、拓扑、历史事件分析可能原因。
3. 告警关联：识别多个告警之间的上下游关系。
4. 状态摘要：生成当前系统或设备状态摘要。
5. 处置建议：给出检查项、处理步骤和升级建议。
6. 风险确认：涉及控制操作时提醒人工确认。
7. 历史复用：检索历史会话、长期记忆、相似告警经验。
8. 多模态输入：支持告警截图、监盘画面、表格等输入。
9. 结构化输出：输出 severity、evidence、actions、need_confirmation。
10. 值班辅助：生成值班记录、交接摘要和异常说明。
```

输入资源：

```text
告警 ID
设备 ID
遥测数据
历史事件
监盘截图
告警分析 Skill
```

输出资源：

```text
告警摘要
原因排序
证据列表
处置建议
值班记录
```

## AgentScope 可提供的底座

```text
HarnessAgent
MCP tools
Skill repositories
memory
session
subagents
workspace/knowledge
stream events
```

## 推荐 AgentDefinition

```yaml
agentId: monitoring_agent
name: 监盘智能体
model: deepseek-v4-flash
systemPrompt: |
  你是面向监盘和告警处置的智能体...
toolRefs: []
mcpRefs: []
skillRefs: []
orchestration:
  mode: SINGLE
```

## 典型调用链路

```text
告警/监盘问题输入
  -> monitoring_agent
  -> 查询告警/遥测 MCP
  -> 调用告警分析 Skill
  -> 必要时检索长期记忆/历史会话
  -> 输出处置建议和风险提示
```

## 还缺什么

```text
监盘业务 prompt
告警分析 Skill
遥测/告警 MCP 工具
告警上下文 RAG
业务权限
监盘结果展示组件
处置建议确认流程
```

## 推荐下一步

```text
P0: 建 monitoring_agent 基础 AgentDefinition
P1: 定义告警/遥测 MCP 接口
P2: 写告警归因、处置建议、风险确认 Skill
P3: 前端增加告警卡片和结构化结果展示
```

## 接口调用设计

### 创建监盘 Agent

```http
POST /platform/frontend/agents
Content-Type: application/json

{
  "agent_id": "monitoring_agent",
  "version": "v1",
  "name": "监盘智能体",
  "model": "deepseek-v4-flash",
  "system_prompt": "你是监盘和告警分析智能体...",
  "mcp_refs": ["monitoring-mcp"],
  "skill_refs": ["alarm-root-cause", "monitoring-summary"],
  "orchestration": { "mode": "SINGLE" }
}
```

### 告警分析调用

```http
POST /agent-runs/run/stream
Content-Type: application/json

{
  "agent_id": "monitoring_agent",
  "session_id": "sess_mon_001",
  "payload": {
    "query": "分析 10:21 的通信中断告警"
  },
  "context": {
    "alarm_id": "alarm_001",
    "device_id": "dev_001"
  }
}
```

### 推荐 Skill

```text
alarm-root-cause
alarm-correlation
monitoring-summary
risk-confirmation
```

### 推荐 MCP

```text
告警查询 MCP
遥测查询 MCP
设备拓扑 MCP
历史事件 MCP
```

### 输出结构建议

```json
{
  "summary": "通信中断可能由前置服务异常导致",
  "severity": "high",
  "evidence": [],
  "recommended_actions": [],
  "needs_confirmation": true
}
```

### 缺口落地

```text
P0: monitoring_agent 基础定义
P1: 告警/遥测 MCP
P2: 告警结果结构化展示
P3: 高风险处置建议确认流程
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
