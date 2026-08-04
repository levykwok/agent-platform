# 模型统一接入

## 结论

AgentScope 能解决“运行时调用模型”的问题，但不负责企业平台里的模型供应商管理、密钥管理、价格、监控和权限。

这里的“模型”不能只理解成 chat 模型。AgentScope 里至少有两类模型对象：

```text
对话生成模型：io.agentscope.core.model.Model
向量化模型：io.agentscope.core.embedding.EmbeddingModel
```

所以本模块应该分成两层：

```text
平台模型管理层：我们做，负责供应商、模型配置、API Key、测试、权限、监控
AgentScope 模型运行层：调用 AgentScope Model / EmbeddingModel / ModelRegistry / OpenAIChatModel
```

## 平台模块功能设计

本模块负责把不同供应商、不同协议、不同模型参数统一成平台可管理、运行时可调用的模型资源。

核心功能：

```text
1. 模型供应商管理：维护 provider_id、provider_type、base_url、endpoint_path、状态、描述。
2. 模型配置管理：维护 model_id、模型名、协议类型、请求参数、header、body 扩展项。
3. 密钥配置管理：支持 apiKey、apiKeyEnv，后续切 credential_ref。
4. URL 预览和协议规则：按 openai-compatible、ollama、http_chat 等类型生成真实请求地址。
5. 模型连通性测试：用指定 prompt 发起真实调用，验证鉴权、URL、参数是否正确。
6. 运行时注册：将启用模型注册到 AgentScope ModelRegistry 或构造成 AgentScope Model。
7. 模型可用状态：区分 enabled/disabled/unavailable，运行时只加载可用模型。
8. 扩展参数管理：支持 additionalHeaders、additionalBodyParams、additionalQueryParams。
9. 后续监控预留：为调用次数、耗时、token、成本、错误率预留采集点。
10. 模型能力管理：区分 chat、embedding、multimodal_embedding、rerank 等能力。
11. RAG 接入支撑：embedding 模型需要能被 Knowledge / VectorStore 链路引用。
```

输入资源：

```text
providers.yml / provider 表
models.yml / model 表
API Key 或 credential_ref
供应商协议规则
```

输出资源：

```text
可被 AgentDefinition 引用的 chat model_id
可被 KnowledgeDefinition 引用的 embedding model_id
可被 HarnessAgent 使用的 AgentScope Model
可被 SimpleKnowledge 使用的 AgentScope EmbeddingModel
模型测试结果
模型调用监控记录
```

## AgentScope 对应实现

核心类和概念：

```text
io.agentscope.core.model.Model
io.agentscope.core.model.ModelRegistry
io.agentscope.core.model.OpenAIChatModel
io.agentscope.core.model.GenerateOptions
io.agentscope.core.model.ExecutionConfig
io.agentscope.core.formatter.Formatter
io.agentscope.core.embedding.EmbeddingModel
io.agentscope.core.embedding.openai.OpenAITextEmbedding
io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding
io.agentscope.core.embedding.dashscope.DashScopeMultiModalEmbedding
io.agentscope.core.embedding.ollama.OllamaTextEmbedding
```

AgentScope 的边界是：给定一个模型对象或模型 ID，运行时能把消息发给模型并拿回结果。它不维护供应商表，也不提供模型配置 UI。

## AgentScope 模型对象边界

### Chat 模型对象

AgentScope 的对话模型接口是：

```java
io.agentscope.core.model.Model
```

核心方法：

```java
Flux<ChatResponse> stream(
    List<Msg> messages,
    List<ToolSchema> tools,
    GenerateOptions options
);

String getModelName();
```

典型实现：

```java
io.agentscope.core.model.OpenAIChatModel
```

OpenAI 兼容模型构建需要：

```text
modelName
apiKey
baseUrl
endpointPath
stream
formatter
GenerateOptions
ExecutionConfig
proxy
```

平台配置里的 `model_id` 是平台引用 ID，不等于供应商真实模型名。供应商真实模型名应该放在 `model` / `modelName` 字段里。

### Embedding 模型对象

AgentScope 的向量模型接口在 RAG simple 扩展里：

```java
io.agentscope.core.embedding.EmbeddingModel
```

核心方法：

```java
Mono<double[]> embed(ContentBlock block);

String getModelName();

int getDimensions();
```

已有实现：

```text
OpenAITextEmbedding
DashScopeTextEmbedding
DashScopeMultiModalEmbedding
OllamaTextEmbedding
```

Embedding 模型不是给 Agent 对话循环直接调用的，而是给 Knowledge / RAG 链路调用。典型路径：

```text
SimpleKnowledge.addDocuments(...)
  -> embeddingModel.embed(documentContentBlock)
  -> document.setEmbedding(...)
  -> vectorStore.add(...)

SimpleKnowledge.retrieve(query, config)
  -> TextBlock(query)
  -> embeddingModel.embed(queryBlock)
  -> vectorStore.search(queryEmbedding)
```

所以 embedding 模型应该被知识库配置引用，而不是直接挂在 Agent 的 chat model 字段上。

## 模型能力分类

平台模型配置建议增加能力字段：

```text
kind: chat
kind: embedding
kind: multimodal_embedding
kind: rerank
```

推荐先用 `kind`，因为当前 AgentScope 的 chat 和 embedding 是不同 Java 接口，生命周期和调用参数也不同。

### Chat 模型配置字段

```yaml
modelId: deepseek-v4-flash
kind: chat
provider: deepseek
protocol: openai-compatible
model: deepseek-v4-flash
apiKeyEnv: DEEPSEEK_API_KEY
baseUrl: https://api.deepseek.com
endpointPath: /chat/completions
stream: true
temperature: 0.7
maxTokens: 4096
additionalHeaders: {}
additionalBodyParams:
  thinking:
    type: disabled
enabled: true
```

映射到 AgentScope：

```text
kind=chat
  -> OpenAIChatModel.builder()
  -> ModelRegistry.register(modelId, model)
  -> AgentDefinition.model 引用 modelId
```

### Embedding 模型配置字段

```yaml
modelId: openai:text-embedding-3-small
kind: embedding
provider: openai
protocol: openai
model: text-embedding-3-small
apiKeyEnv: OPENAI_API_KEY
baseUrl: https://api.openai.com/v1
dimensions: 1536
enabled: true
```

映射到 AgentScope：

```text
kind=embedding
  -> OpenAITextEmbedding / DashScopeTextEmbedding / OllamaTextEmbedding
  -> EmbeddingModelRegistry.register(modelId, embeddingModel)
  -> KnowledgeDefinition.embeddingModel 引用 modelId
  -> SimpleKnowledge.builder().embeddingModel(...)
```

### 不应该暴露给普通用户的字段

这些字段可以保留在高级模式或开发者模式，不应该作为常驻表单项：

```text
className
modelFactoryClass
formatterClass
```

原因：

```text
className / modelFactoryClass 是本地 Java 扩展模型使用的，不是普通供应商模型配置。
formatterClass 是请求/响应序列化适配类，普通用户不应该手填。
```

普通用户应该看到的是：

```text
供应商
协议类型
模型能力
模型名
API Key
Base URL
生成接口预览
Embedding 接口预览
额外 Header
额外 Body 参数
超时 / 重试
是否启用
```

## 运行时调用链路

Chat 模型运行链路：

```text
AgentDefinition.model()
  -> AgentScopeHarnessFactory.create(...)
  -> HarnessAgent.builder().model(definition.model())
  -> HarnessAgent.build()
  -> ReActAgent 调用 Model
```

Embedding 模型运行链路：

```text
KnowledgeDefinition.embeddingModel()
  -> EmbeddingModelRegistry.resolve(embeddingModelId)
  -> SimpleKnowledge.builder().embeddingModel(embeddingModel)
  -> addDocuments / retrieve 时调用 embeddingModel.embed(...)
```

对于 OpenAI 兼容协议，平台层负责把配置转换成 AgentScope 能识别的模型配置：

```text
provider
model
baseUrl
endpointPath
apiKey / apiKeyEnv
additionalHeaders
additionalBodyParams
GenerateOptions
```

对于 embedding 协议，平台层负责把配置转换成 AgentScope embedding 实现：

```text
provider
model
baseUrl
apiKey / apiKeyEnv
dimensions
ExecutionConfig
```

注意：OpenAI chat 的接口路径和 embedding 的接口路径不是一个东西。供应商 URL 预览要按 `kind + provider_type` 生成。

```text
openai/gpustack/vllm chat: .../v1/chat/completions
openai/gpustack/vllm embedding: .../v1/embeddings
ollama chat: {root}/api/chat
ollama embedding: {root}/api/embeddings
http_chat: base_url 本身
mock/echo: 无真实外部请求
```

## 当前平台已实现

后端：

```text
ModelConfigRegistry
ModelProviderRegistry
YamlModelConfigRegistry
YamlModelProviderRegistry
PlatformConfigStore
```

前端：

```text
ModelsAdmin.vue
```

落盘：

```text
workspace/models.yml
workspace/providers.yml
```

已支持的方向：

```text
模型列表
供应商列表
OpenAI 兼容 URL 拼接规则
额外 header
额外 body 参数
模型测试
YAML 持久化
```

## 还缺什么

```text
密钥托管，不应该长期明文存在 yml
模型调用监控，包括 latency、token、错误率、成本
embedding 模型统一管理
EmbeddingModelRegistry
Knowledge / RAG 配置管理
模型 fallback 和路由策略
模型权限，哪些 agent / 用户可用哪些模型
模型启停与健康检测
模型调用审计
```

## 推荐下一步

```text
P0: 抽 ModelSecretService，API Key 不直接散落在模型配置里
P1: 做 ModelCallRecorder，包装 Model 调用记录耗时、错误、token
P2: 增加 embedding 模型配置和测试入口
P3: 增加 KnowledgeDefinition，让知识库引用 embedding 模型和 vector store
P4: 模型配置从 YAML 切 DB 时保持 ModelConfigRegistry 接口不变
```

## 接口调用设计

### 前端管理接口

模型列表：

```http
GET /platform/frontend/models
x-org-id: platform
```

Chat 模型保存：

```http
POST /platform/frontend/models
Content-Type: application/json

{
  "model_id": "deepseek-v4-flash",
  "kind": "chat",
  "type": "provider",
  "provider": "openai-compatible",
  "model": "deepseek-v4-flash",
  "base_url": "https://api.deepseek.com",
  "endpoint_path": "/chat/completions",
  "api_key": "sk-***",
  "additional_headers": {
    "X-Request-Source": "company-platform"
  },
  "additional_body_params": {
    "thinking": { "type": "disabled" }
  },
  "enabled": true
}
```

Embedding 模型保存：

```http
POST /platform/frontend/models
Content-Type: application/json

{
  "model_id": "openai:text-embedding-3-small",
  "kind": "embedding",
  "type": "provider",
  "provider": "openai",
  "model": "text-embedding-3-small",
  "base_url": "https://api.openai.com/v1",
  "api_key": "sk-***",
  "dimensions": 1536,
  "enabled": true
}
```

模型测试：

```http
POST /platform/frontend/models/{modelId}/test
Content-Type: application/json

{
  "message": "Hello",
  "stream": false
}
```

供应商列表：

```http
GET /platform/frontend/model-providers
```

供应商保存：

```http
POST /platform/frontend/model-providers
Content-Type: application/json

{
  "provider_id": "deepseek",
  "display_name": "DeepSeek",
  "provider_type": "openai-compatible",
  "default_base_url": "https://api.deepseek.com",
  "endpoint_path": "/chat/completions",
  "secret_ref": "secret://deepseek/api-key",
  "timeout_ms": 60000,
  "status": "enabled"
}
```

### 后端调用链路

```text
ModelsAdmin.vue
  -> PlatformFrontendCompatibilityController
  -> ModelConfigRegistry / ModelProviderRegistry
  -> PlatformConfigStore
  -> models.yml / providers.yml
```

Chat 运行时：

```text
AgentRuntimeService
  -> AgentDefinition.model()
  -> AgentScopeHarnessFactory
  -> HarnessAgent.builder().model(modelId)
  -> AgentScope ModelRegistry.resolve(modelId)
```

Embedding / RAG 运行时：

```text
KnowledgeRuntimeService
  -> KnowledgeDefinition.embeddingModel()
  -> EmbeddingModelRegistry.resolve(embeddingModelId)
  -> SimpleKnowledge.builder().embeddingModel(...).embeddingStore(...)
  -> AgentScopeHarnessFactory 注入 Knowledge
  -> ReActAgent 通过 Hook 或 Tool 触发 Knowledge.retrieve(...)
```

### Java 接入点

```java
modelConfigRegistry.upsert(modelSpec);
modelProviderRegistry.upsert(providerSpec);
modelConfigRegistry.find(modelId);
embeddingModelRegistry.resolve(modelId);
```

### DB 化边界

后续切 DB 时，Controller 和 Runtime 不应该改，只替换：

```text
YamlModelConfigRegistry -> DbModelConfigRegistry
YamlModelProviderRegistry -> DbModelProviderRegistry
```

或把：

```text
PlatformConfigStore
```

替换成：

```text
DbPlatformConfigStore
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

## 2026-07-09 更新：模型接入边界修正

当前实现验证后，模型统一接入要按“平台模型配置 -> AgentScope Model 实例/注册名”的适配链路设计，而不是要求用户直接理解 AgentScope 内置 provider。

关键结论：

```text
1. AgentScope 能调用模型，但它的内置 provider 只覆盖部分标准供应商。
2. 平台必须保留自己的 provider_type / endpoint_path / headers / extra_body / api_key 配置模型。
3. http_chat 这类非 OpenAI 标准路径不能强行转成 openai-compatible。
4. openai-compatible 可按 /v1/chat/completions 规则拼接；http_chat 应按用户填写的完整 endpoint 或 base_url 本身调用。
5. deepseek 这类没有 /v1 路径的配置应允许走 http_chat，而不是硬映射成 openai-compatible。
6. embedding 模型也属于本模块，后续要单独维护 EmbeddingModel 配置和调用路径。
```

平台需要维护的适配类型：

```text
openai-compatible：补 /v1/chat/completions 和 /v1/embeddings。
http_chat：按用户配置 endpoint 直接调用，平台自己封装请求/响应映射。
ollama：/api/chat 和 /api/embeddings。
mock/echo：不发外部请求，仅用于联调。
custom：后续允许通过请求模板/响应模板扩展。
```

AgentScope 对接方式：

```text
1. 平台保存 ModelSpec / ProviderSpec 到 SQLite。
2. 启动或保存模型时注册到 ModelRegistry，或构造平台自定义 Model 实例。
3. AgentDefinition 只保存 model slot / model_id。
4. AgentScopeHarnessFactory.resolveModel 决定最终传给 HarnessAgent 的模型。
5. 不要求业务用户填写 className、formatterClass、modelFactoryClass；这些只能作为高级开发者字段或平台内置适配器字段。
```

当前缺口：

```text
1. EmbeddingModel 的管理和测试还需要补完整。
2. 模型 pricing 不应使用原始 JSON 字段暴露给普通用户，应拆成输入/输出单价、币种、计费单位。
3. 模型测试需要区分 ping、真实 chat、stream chat、embedding。
4. 需要记录模型调用日志、错误、耗时、token、供应商响应原文摘要。
```
