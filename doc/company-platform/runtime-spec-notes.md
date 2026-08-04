# RuntimeSpec / Capability Planning Notes

本文记录后续架构点，当前不作为优先实现项。

## 背景

`company-platform` 已经有一层平台 runtime wrapper：

- `AgentDefinition`
- `AgentRuntimeService`
- `AgentScopeHarnessFactory`
- `AgentCapabilityAssembler`
- 前端兼容 API
- SQLite 配置/会话/审计/探测存储

因此后续不是“重新包一层”，而是在现有平台层中补“本轮运行态能力裁剪”。

## 当前状态

当前运行链路主要是：

```text
AgentDefinition
  -> AgentScopeHarnessFactory
  -> AgentCapabilityAssembler
  -> HarnessAgent
```

`AgentDefinition` 里的 `toolRefs`、`mcpRefs`、`skillRefs` 基本直接成为本轮可见能力。

## 后续目标

引入 `RuntimeSpec`，区分：

- `AgentDefinition`：静态 Agent 配置上限。
- `RuntimeSpec`：本轮实际启用的 model/tool/mcp/skill/memory。

示例：

```java
record RuntimeSpec(
    AgentDefinition definition,
    String model,
    Map<String, Object> modelPolicy,
    List<String> toolRefs,
    List<String> mcpRefs,
    List<String> skillRefs,
    Map<String, Object> runtimeHints
) {}
```

## CapabilityPlanner 思路

后续可以让模型只输出结构化能力需求，而不是直接暴露底层加载工具：

```json
{
  "need_skills": ["platform-research-kit"],
  "need_tools": ["execute"],
  "need_mcps": ["platform-demo-stdio"],
  "reason": "需要运行 research-kit 脚本"
}
```

平台解析后：

1. 以 `AgentDefinition` 为上限校验。
2. 结合用户权限、业务域、工具风险等级裁剪。
3. 生成 `RuntimeSpec`。
4. 传给 AgentScope adapter。

## 注意点

- 不修改 AgentScope 源码。
- 不推翻当前 `company-platform` runtime wrapper。
- 第一版可以先规则化生成 `RuntimeSpec`，不接 LLM planner。
- 当前不作为优先功能，先推进缺失平台功能。
