# RAG 知识检索

## 结论

AgentScope 已经提供 RAG 运行时链路，但它不是完整知识库平台。

它能解决：

```text
Knowledge 抽象
文档向量化
向量检索
RAG Hook 自动注入上下文
RAG Tool 由模型按需调用
第三方知识库集成接口
```

它不负责：

```text
知识库管理界面
文档上传审批
文档解析流水线
分块策略管理
向量库资源管理
知识库权限
知识库版本
知识召回评测
知识命中审计
```

所以平台应该做“知识库管理 + RAG 配置 + 权限 + 持久化”，运行时优先使用 AgentScope 的 `Knowledge` / `GenericRAGHook` / `KnowledgeRetrievalTools` 链路。

## 平台模块功能设计

RAG 模块负责把平台知识库资产接入智能体运行时。

核心功能：

```text
1. 知识库管理：维护 knowledge_id、名称、描述、类型、状态。
2. Embedding 模型绑定：每个本地向量知识库绑定一个 embedding_model_id。
3. 向量库绑定：维护 vector_store 类型、连接信息、collection、dimensions。
4. 文档管理：上传、解析、分块、入库、删除、重建索引。
5. 检索配置：limit、score_threshold、vector_name、rerank、召回策略。
6. Agent 绑定：AgentDefinition 里引用 knowledge_refs。
7. RAG 接入模式：支持 auto、tool、off。
8. 权限控制：用户和 Agent 只能访问授权知识库。
9. 审计监控：记录查询、命中文档、分数、耗时、错误。
```

## AgentScope 对应实现

核心类：

```text
io.agentscope.core.rag.Knowledge
io.agentscope.core.rag.knowledge.SimpleKnowledge
io.agentscope.core.rag.GenericRAGHook
io.agentscope.core.rag.KnowledgeRetrievalTools
io.agentscope.core.rag.model.Document
io.agentscope.core.rag.model.DocumentMetadata
io.agentscope.core.rag.model.RetrieveConfig
io.agentscope.core.rag.store.VDBStoreBase
io.agentscope.core.embedding.EmbeddingModel
```

第三方知识库扩展：

```text
BailianKnowledge
DifyKnowledge
HayStackKnowledge
RAGFlowKnowledge
```

Simple RAG 扩展里，`SimpleKnowledge` 是完整本地 RAG 链路：

```text
EmbeddingModel + VDBStoreBase -> SimpleKnowledge
```

第三方 RAG 扩展一般只负责检索，不负责文档导入和索引构建。

## RAG 是不是工具

RAG 本身不是工具。AgentScope 提供两种接入方式：

| 接入方式 | 是否工具 | 触发方式 | AgentScope 类 |
|---|---:|---|---|
| Hook 模式 | 否 | 每轮推理前自动检索并注入上下文 | `GenericRAGHook` |
| Tool 模式 | 是 | 注册成工具，由模型决定何时调用 | `KnowledgeRetrievalTools` |

平台配置建议：

```yaml
rag:
  enabled: true
  mode: auto
  knowledgeRefs:
    - platform-policy
  retrieve:
    limit: 5
    scoreThreshold: 0.5
```

可选模式：

```text
auto: 使用 GenericRAGHook，自动检索。
tool: 使用 KnowledgeRetrievalTools，模型按需调用。
off: 不注入 RAG。
```

## 入库调用链路

本地知识库入库链路：

```text
文档上传 / 读取
  -> 文档解析
  -> 文档分块
  -> List<Document>
  -> SimpleKnowledge.addDocuments(documents)
  -> DocumentMetadata.getContent()
  -> EmbeddingModel.embed(contentBlock)
  -> Document.setEmbedding(...)
  -> VDBStoreBase.add(...)
```

AgentScope 关键逻辑：

```java
embeddingModel
    .embed(contentBlock)
    .doOnNext(embedding -> doc.setEmbedding(embedding))
    .thenReturn(doc);
```

平台要补的是：

```text
文档上传接口
文档解析器
chunk 配置
入库任务状态
失败重试
索引重建
文档版本
```

## 检索调用链路

查询检索链路：

```text
用户问题 query
  -> Knowledge.retrieve(query, retrieveConfig)
  -> SimpleKnowledge.retrieve(...)
  -> TextBlock(query)
  -> EmbeddingModel.embed(queryBlock)
  -> VDBStoreBase.search(queryEmbedding)
  -> List<Document>
```

AgentScope 关键逻辑：

```java
TextBlock queryBlock = TextBlock.builder().text(query).build();

return embeddingModel
    .embed(queryBlock)
    .flatMap(queryEmbedding ->
        embeddingStore.search(
            SearchDocumentDto.builder()
                .queryEmbedding(queryEmbedding)
                .limit(config.getLimit())
                .build()
        )
    );
```

## Agent 运行时接入链路

Hook 模式：

```text
AgentDefinition.knowledgeRefs
  -> KnowledgeRuntimeService 构建 Knowledge
  -> ReActAgent.builder().knowledge(knowledge)
  -> ReActAgent 创建 GenericRAGHook
  -> 每轮推理前 Knowledge.retrieve(...)
  -> 检索结果注入上下文
  -> Chat Model 生成答案
```

Tool 模式：

```text
AgentDefinition.knowledgeRefs
  -> KnowledgeRuntimeService 构建 Knowledge
  -> KnowledgeRetrievalTools(knowledge, retrieveConfig)
  -> Toolkit 注册 retrieveKnowledge 工具
  -> 模型决定是否调用工具
  -> 工具内部 Knowledge.retrieve(...)
  -> 工具结果返回模型
```

完整结构：

```text
EmbeddingModel
  <- SimpleKnowledge
      <- GenericRAGHook
          <- ReActAgent 自动 RAG

EmbeddingModel
  <- SimpleKnowledge
      <- KnowledgeRetrievalTools
          <- ReActAgent 工具调用 RAG
```

## 平台配置建议

知识库定义：

```yaml
knowledgeId: platform-policy
type: simple
displayName: 公司制度知识库
embeddingModel: openai:text-embedding-3-small
vectorStore:
  type: in-memory
  dimensions: 1536
retrieve:
  limit: 5
  scoreThreshold: 0.5
enabled: true
```

Agent 绑定：

```yaml
agentId: policy-assistant
model: deepseek-v4-flash
knowledgeRefs:
  - platform-policy
rag:
  enabled: true
  mode: auto
```

## 当前平台已实现

当前平台已经有：

```text
模型管理
Agent 管理
运行时对话
工具 / MCP / Skill 管理
YAML 配置持久化
```

还没有形成完整 RAG 管理链路：

```text
KnowledgeDefinition
KnowledgeRegistry
EmbeddingModelRegistry
VectorStoreRegistry
文档入库接口
AgentDefinition.knowledgeRefs 运行时注入
RAG Hook / Tool 模式配置
```

## 接口调用设计

知识库列表：

```http
GET /platform/frontend/knowledge-bases
```

知识库保存：

```http
POST /platform/frontend/knowledge-bases
Content-Type: application/json

{
  "knowledge_id": "platform-policy",
  "type": "simple",
  "display_name": "公司制度知识库",
  "embedding_model": "openai:text-embedding-3-small",
  "vector_store": {
    "type": "in-memory",
    "dimensions": 1536
  },
  "retrieve": {
    "limit": 5,
    "score_threshold": 0.5
  },
  "enabled": true
}
```

文档入库：

```http
POST /platform/frontend/knowledge-bases/platform-policy/documents
Content-Type: multipart/form-data

file=@policy.pdf
```

检索测试：

```http
POST /platform/frontend/knowledge-bases/platform-policy/retrieve
Content-Type: application/json

{
  "query": "年假怎么申请？",
  "limit": 5,
  "score_threshold": 0.5
}
```

Agent 绑定 RAG：

```http
POST /platform/frontend/agents/policy-assistant
Content-Type: application/json

{
  "agent_id": "policy-assistant",
  "model": "deepseek-v4-flash",
  "knowledge_refs": ["platform-policy"],
  "rag": {
    "enabled": true,
    "mode": "auto"
  }
}
```

## Java 调用点

构建 embedding 模型：

```java
EmbeddingModel embeddingModel =
        OpenAITextEmbedding.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-3-small")
                .dimensions(1536)
                .build();
```

构建本地知识库：

```java
VDBStoreBase store = InMemoryStore.builder()
        .dimensions(1536)
        .build();

SimpleKnowledge knowledge = SimpleKnowledge.builder()
        .embeddingModel(embeddingModel)
        .embeddingStore(store)
        .build();
```

文档入库：

```java
knowledge.addDocuments(documents).block();
```

检索：

```java
RetrieveConfig config = RetrieveConfig.builder()
        .limit(5)
        .scoreThreshold(0.5)
        .build();

List<Document> docs = knowledge.retrieve("年假怎么申请？", config).block();
```

Hook 模式：

```java
Hook ragHook = new GenericRAGHook(knowledge, config);
```

Tool 模式：

```java
KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge, config);
```

## DB 化边界

后续切 DB 时建议拆表：

```text
knowledge_base
knowledge_vector_store
knowledge_document
knowledge_chunk
knowledge_agent_binding
knowledge_retrieve_log
```

当前 YAML 可以先对应：

```text
workspace/knowledge.yml
workspace/vector-stores.yml
```

运行时接口保持：

```text
KnowledgeRegistry
KnowledgeRuntimeService
EmbeddingModelRegistry
VectorStoreRegistry
```

不要让 Controller 或 AgentRuntimeService 直接读 YAML / DB。

## 还缺什么

```text
Embedding 模型管理还没接入平台 registry
KnowledgeDefinition 还没定义
VectorStoreDefinition 还没定义
文档解析和分块链路还没做
RAG Hook / Tool 模式还没暴露配置
AgentDefinition 还没绑定 knowledgeRefs
RAG 检索日志和命中文档面板还没做
知识库权限还没做
```

## 推荐下一步

```text
P0: 给模型配置增加 kind=chat/embedding。
P1: 实现 EmbeddingModelRegistry。
P2: 定义 KnowledgeDefinition 和 knowledge.yml。
P3: 实现 SimpleKnowledgeRuntimeFactory。
P4: AgentDefinition 增加 knowledgeRefs 和 rag.mode。
P5: 运行时接入 GenericRAGHook / KnowledgeRetrievalTools。
P6: 前端增加知识库管理和检索测试页面。
```

## 2026-07-09 更新：RAG 与工具/Skill 的关系

AgentScope 有 RAG 扩展，但平台不能把 RAG 简化成一个普通工具。更合理的层次是：

```text
知识库管理：平台管理 collection、document、chunk、embedding、权限。
检索运行：可以以工具形式暴露给 agent，也可以作为 middleware/context 注入。
Skill：适合封装领域工作流和脚本，不适合作为通用知识库主存储。
MCP：适合接外部检索服务或企业知识服务。
```

当前建议：

```text
1. 知识库资产进入平台存储和 SQLite 元数据。
2. 文档原文/切片后续可接对象存储；当前先不引入 MinIO。
3. Embedding 模型接入属于 model-unified-access。
4. RAG 查询应在事件流中展示：query、collection、topK、命中文档、score、注入片段。
5. 如果作为 tool 暴露，需要在 Agent 的工具范围里明确显示。
```

缺口：

```text
1. EmbeddingModel 管理未完整。
2. 文档上传、切片、索引重建、删除和权限过滤未完整。
3. RAG 命中证据和最终回答引用还需要统一展示。
```
