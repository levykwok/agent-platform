# AI 模型监控工具

## 结论

AgentScope 提供模型调用抽象和 middleware/event 挂点，但模型监控是平台能力，需要我们自己采集、存储、统计和展示。

## 平台模块功能设计

本模块负责采集、统计和展示模型调用质量、性能、成本和可用性。

核心功能：

```text
1. 调用记录：记录每次模型调用的 model_id、provider、agent_id、run_id。
2. 耗时统计：记录首 token 耗时、总耗时、流式耗时。
3. Token 统计：记录 prompt_tokens、completion_tokens、total_tokens。
4. 错误统计：记录超时、鉴权失败、限流、格式错误、供应商错误。
5. 成本统计：结合 pricing 计算调用成本。
6. 可用性检测：定时或手动测试模型是否可用。
7. 趋势分析：按模型、供应商、Agent、用户、时间聚合。
8. 告警：错误率、超时率、成本超阈值触发告警。
9. 监控页面：展示调用量、耗时、错误率、成本。
10. 运行追溯：从 AgentRun 追溯到模型调用详情。
```

输入资源：

```text
Model 调用前后事件
AgentRun context
ModelSpec pricing
供应商错误响应
```

输出资源：

```text
ModelCallRecord
ModelMetrics
告警事件
模型监控页面数据
```

## AgentScope 可用挂点

```text
Model wrapper
Middleware
AgentEvent
ExecutionConfig timeout
Model stream response
```

可包装：

```java
class ObservedModel implements Model
```

在模型调用前后记录：

```text
model_id
provider
latency_ms
success / failed
error_code
token estimate
request_id
run_id
agent_id
user_id
```

## 当前平台已实现

目前只有模型配置和模型测试，没有正式监控。

相关基础：

```text
ModelConfigRegistry
ModelProviderRegistry
```

## 推荐调用链路

```text
YamlModelConfigRegistry / ModelFactory
  -> raw AgentScope Model
  -> ObservedModel(raw, ModelCallRecorder)
  -> HarnessAgent 使用 observed model
```

## 还缺什么

```text
ModelCallRecord
token 统计
成本配置
调用量趋势
错误率趋势
供应商维度统计
Agent 维度统计
告警规则
监控页面
```

## 推荐下一步

```text
P0: 加 ModelCallRecorder 接口，先日志/YAML 或内存记录
P1: Model 包装层统计 latency/error
P2: 模型配置中增加 pricing 字段的结构化表单
P3: DB 化后落 model_call_log 表
```

## 接口调用设计

### 模型调用记录 DTO

```json
{
  "call_id": "model_call_001",
  "run_id": "run_001",
  "agent_id": "ops_agent",
  "model_id": "deepseek-v4-flash",
  "provider": "deepseek",
  "started_at": "2026-06-26T10:00:00Z",
  "latency_ms": 820,
  "status": "succeeded",
  "prompt_tokens": 1200,
  "completion_tokens": 300,
  "error_code": null
}
```

### 查询接口建议

```http
GET /platform/frontend/model-calls?model_id=deepseek-v4-flash&from=2026-06-26T00:00:00Z
```

```http
GET /platform/frontend/model-metrics?group_by=model_id&period=1h
```

### Java 包装方式

```java
class ObservedModel implements Model {
    private final Model delegate;
    private final ModelCallRecorder recorder;

    public Flux<ChatResponse> stream(List<Msg> messages, GenerateOptions opts, ExecutionConfig exec) {
        long start = System.nanoTime();
        return delegate.stream(messages, opts, exec)
            .doOnError(e -> recorder.failed(...))
            .doOnComplete(() -> recorder.succeeded(...));
    }
}
```

### 接入点

```text
ModelConfigRegistry register(spec)
  -> raw Model
  -> ObservedModel(raw, recorder)
  -> ModelRegistry.register(modelId, observed)
```

### 缺口落地

```text
P0: ModelCallRecorder 接口
P1: ObservedModel 包装
P2: model_call_log 表
P3: 模型监控页面
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
