# AgentScope 运行链路总图

本文件给出从前端发起运行到平台 memory 投影回写的完整链路（文档视图）。

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant F as 前端 (chat / agent run)
    participant C as AgentRunsCompatibilityController
    participant S as AgentRuntimeService
    participant D as AgentDefinitionRegistry
    participant H as AgentScopeHarnessFactory
    participant A as HarnessAgent
    participant E as AgentScope Event Pipeline
    participant FE as 前端 SSE 渲染
    participant P as 平台记忆导入服务

    U->>F: 输入问题 + 会话上下文
    F->>C: POST /agent-runs/run/stream
    C->>S: chat/stream(agentId, request)
    S->>D: 查询已发布 AgentDefinition
    D-->>S: AgentDefinition（配置、绑定关系）
    S->>H: create(AgentDefinition)
    H->>H: 映射 model/toolkit/skillRepositories/memory/stateStore/workspace
    H-->>S: HarnessAgent 实例
    S->>A: streamEvents(message, runtimeContext)
    A->>E: emit event（model_call_start / capability_loaded / tool_call / ...）
    E-->>S: event
    S->>S: 包装为 event envelope（run_id、event_type、payload）
    S-->>FE: SSE 事件流

    A->>E: tool/mcp/skill/memory middleware 调用
    E-->>S: tool_result / tool_error / model_call_end / agent_end
    S->>FE: 透传为可展示事件

    S-->>FE: run 完成事件
    S->>P: run 后投影：读 scoped MEMORY.md + daily ledger
    P->>P: 解析 pending memory / scope
    P-->>S: 写 SQLite pending_confirm（memory 管理入口）
```

对应 1~10 步映射：

```text
1. 前端 chat / agent run
2. Controller
3. AgentRuntimeService
4. AgentDefinition 查询
5. AgentScopeHarnessFactory.create
6. model/tool/mcp/skill/memory/stateStore 注入
7. HarnessAgent.streamEvents / call
8. tool call / mcp call / skill load / memory flush
9. event envelope 转前端
10. run 后 memory import
```

说明：本链路不要求改 runtime 源码，仅用于统一文档口径。  
