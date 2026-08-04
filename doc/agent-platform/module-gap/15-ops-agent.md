# 运维智能体

## 结论

运维智能体不是 AgentScope 内置模块，而是基于 AgentScope Harness 组装出来的业务 Agent。平台要沉淀运维 prompt、工具、MCP、Skill、知识库、记忆策略和测试集。

## 平台模块功能设计

本模块是基于平台运行底座组装的运维业务智能体，负责运维问答、故障诊断、巡检、报告和操作建议。

核心功能：

```text
1. 运维问答：回答系统软件部署、配置、运行、故障相关问题。
2. 告警分析：结合告警、日志、服务状态定位原因。
3. 巡检报告：调用工具采集状态并生成巡检结果。
4. 故障处置建议：输出分步骤排查方案和风险提示。
5. 变更风险检查：分析变更内容、影响范围和回滚建议。
6. 工单辅助：生成工单摘要、处置记录、复盘报告。
7. 运维知识检索：结合知识库/RAG/历史会话/长期记忆。
8. 工具调用：调用日志、监控、配置、服务状态 MCP。
9. 高风险确认：涉及重启、切换、删除、写配置时必须确认。
10. 经验沉淀：把有效处置经验写入长期记忆或 Skill。
```

输入资源：

```text
用户问题
告警/日志/服务状态 MCP
运维 Skill
知识库/RAG
长期记忆
```

输出资源：

```text
诊断结论
处置步骤
风险提示
工单/报告内容
可沉淀记忆
```

## AgentScope 可提供的底座

```text
HarnessAgent
AGENTS.md / systemPrompt
MCP tools
Skill repositories
memory
session
subagents
workspace/knowledge
filesystem / sandbox
```

## 当前平台已具备的通用能力

```text
AgentDefinition
Model 管理
Tool/MCP/Skill registry
AgentRuntimeService
AgentWorkbench 对话界面
```

但还没有成型的运维业务 Agent 资产。

## 推荐 AgentDefinition

```yaml
agentId: ops_agent
name: 运维智能体
model: deepseek-v4-flash
systemPrompt: |
  你是面向系统软件平台的运维智能体...
toolRefs: []
mcpRefs: []
skillRefs: []
orchestration:
  mode: SINGLE
```

## 运行业务链路

```text
用户选择 ops_agent
  -> AgentWorkbench 发送消息
  -> AgentRuntimeService
  -> HarnessAgent.call
  -> 运维 MCP / Skill / memory / session
```

## 还缺什么

```text
运维领域 prompt
运维 Skill 包
运维 MCP 工具
运维知识库/RAG
运维风险控制
业务测试样例
告警/工单/日志系统接入
```

## 推荐下一步

```text
P0: 建 ops_agent 基础 AgentDefinition
P1: 写 3-5 个运维 Skill：告警分析、巡检报告、故障定位、变更风险检查
P2: 接入至少一个真实运维 MCP
P3: 建业务测试集，验证回答和工具调用链路
```

## 接口调用设计

### 创建运维 Agent

```http
POST /platform/frontend/agents
Content-Type: application/json

{
  "agent_id": "ops_agent",
  "version": "v1",
  "name": "运维智能体",
  "model": "deepseek-v4-flash",
  "system_prompt": "你是系统软件平台运维智能体...",
  "mcp_refs": ["ops-mcp"],
  "skill_refs": ["alarm-analysis", "incident-report"],
  "orchestration": { "mode": "SINGLE" }
}
```

### 对话调用

```http
POST /agent-runs/run/stream
Content-Type: application/json

{
  "agent_id": "ops_agent",
  "session_id": "sess_ops_001",
  "payload": {
    "query": "数据库连接失败告警怎么排查？"
  }
}
```

### 推荐 Skill

```text
alarm-analysis
incident-report
change-risk-check
service-health-check
log-diagnosis
```

### 推荐 MCP

```text
告警查询 MCP
日志查询 MCP
服务状态 MCP
工单系统 MCP
配置查询 MCP
```

### 运行链路

```text
用户问题
  -> ops_agent
  -> 读取长期记忆和会话上下文
  -> 调 Skill 形成分析流程
  -> 调 MCP 查询事实
  -> 输出诊断结论和操作建议
  -> 高风险操作触发确认
```

### 缺口落地

```text
P0: ops_agent 基础定义
P1: 运维 Skill 包
P2: 至少一个真实 MCP
P3: 运维测试集和评估脚本
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
