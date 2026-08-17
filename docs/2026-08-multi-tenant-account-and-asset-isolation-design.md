# 多租户账号与资产隔离设计

## 1. 文档信息

- 状态：MVP 已实现，生产加固待办
- 日期：2026-08-14
- 适用范围：平台用户、组织、Agent、Workflow、MCP、Skill、Tool、知识库、文件和运行数据
- 当前结论：账号申请、管理员审核、一次性密码设置、Session 身份和主要平台资产的用户/组织隔离已形成闭环；普通用户可创建私有远程 MCP、HTTP Tool 和 Skill。stdio MCP、Python Tool、公共资产治理及执行沙箱仍保持管理员控制。

## 2. 目标与非目标

### 目标

1. 支持用户自助提交账号申请，由管理员审核后创建账号。
2. 支持审核通过后的安全首次登录和邮件通知。
3. 支持用户拥有自己的 Agent、Workflow、MCP、Skill、Tool、知识库和测试数据。
4. 将现有平台资产保留为平台公共资产，普通用户可使用但不可直接修改。
5. 对数据库、文件系统、向量库、运行记录和外部 API 统一实施租户隔离。
6. 为 MCP、Python Tool 和 Skill 提供可控的执行边界。

### 非目标

- 第一阶段不做复杂计费、企业 SSO、跨组织协作和完整 RBAC 编辑器。
- 不把“前端传入的 user_id/org_id”视为安全边界。
- 不允许用户通过请求参数指定任意服务器文件路径或任意执行环境。

## 3. 现状与主要风险

当前前端通过 localStorage、查询参数和 `x-user-id/x-org-id` 请求头携带用户及组织信息；后端部分接口直接使用这些值。它们属于调用元数据，不是可信身份。

当前主要风险：

1. 调用方可能伪造用户或组织上下文。
2. Agent、MCP、Skill、Tool 核心模型没有统一的 owner、tenant 和 visibility 字段。
3. Agent catalog 的部分读写接口缺少用户级授权边界。
4. 知识库和 Qdrant 的过滤不能只依赖客户端传入的组织字段。
5. MCP stdio、Python Tool 和远程 MCP 可能触达文件、网络、进程和凭据。
6. Workflow 画布坐标属于 UI 字段，运行模型不应直接依赖它；创建和保存时需要显式转换。

## 4. 总体架构

```text
Authenticated User
        |
        v
Organization / Tenant + Membership + Role
        |
        v
AuthorizationService + TenantContext
        |
        +--> Agent / Workflow / MCP / Skill / Tool
        +--> Knowledge Base / Document / File
        +--> Session / Memory / Run / Trace
        +--> External API Key / Quota / Audit
```

身份必须来自服务端 Session（或受控的短期 Token），组织必须来自当前用户的 Membership，资源访问必须经过统一授权服务。

## 5. 身份与账号生命周期

### 5.1 核心数据

```text
users
- id, email, display_name, password_hash
- status, must_change_password, email_verified_at
- failed_attempts, locked_until, last_login_at, created_at

organizations
- id, name, slug, status, quota_profile

organization_memberships
- org_id, user_id, role, status

account_applications
- id, email, display_name, project, reason
- status: PENDING/APPROVED/REJECTED/CANCELLED
- reviewer_id, review_reason, reviewed_at, created_at

password_setup_tokens
- user_id, token_hash, expires_at, used_at

email_outbox
- recipient, template, payload, status, retry_count
- next_retry_at, sent_at, last_error

audit_events
- actor_id, org_id, action, resource_type, resource_id
- result, metadata, created_at
```

密码只保存 Argon2id 或 BCrypt 哈希。一次性链接只保存哈希后的 Token。

### 5.2 申请与审核流程

```text
用户提交申请
  -> PENDING
  -> 管理员审核
       -> REJECTED
       -> APPROVED
            -> 创建 users
            -> 创建 organization/membership
            -> 生成一次性密码设置 Token
            -> 写入 email_outbox
            -> 邮件发送/重试
```

申请页面收集邮箱、姓名、项目、用途和验证码。提交申请时不创建可登录账号，避免未审核账号占用登录入口。

管理员审核页面需要支持：查看详情、分配组织、分配角色、设置配额、设置能力开关、批准、拒绝、拒绝原因和批量处理。所有动作写入审计日志。

### 5.3 首次登录

推荐邮件发送一次性密码设置链接，而不是发送明文密码：

1. Token 有效期建议 24 小时。
2. Token 只能使用一次。
3. 设置密码后立即失效。
4. 登录后可以强制邮箱验证和补充资料。

如必须发送临时密码，则设置 `must_change_password=true`，首次登录强制改密，且明文密码不得写入日志、数据库和 outbox 持久化内容。

## 6. 资产模型与可见性

Agent、Workflow、MCP、Skill、Tool、知识库、文档集合和需要隔离的运行资源统一增加：

```text
org_id
owner_type: SYSTEM / ORGANIZATION / USER
owner_id
created_by
updated_by
visibility: PRIVATE / ORGANIZATION / PUBLIC
status: DRAFT / TESTING / PENDING_REVIEW / PUBLISHED / DISABLED
```

现有平台资产迁移为：

```text
owner_type = SYSTEM
owner_id = platform
visibility = PUBLIC
status = PUBLISHED
```

公共资产表示“可查看和使用”，不表示“所有人可修改”。普通用户需要修改公共资产时，执行“复制到我的空间”。

用户新建资产默认：

```text
owner_type = USER
owner_id = currentUser
org_id = currentOrg
visibility = PRIVATE
status = DRAFT
```

公共发布流程：

```text
DRAFT -> TESTING -> PENDING_REVIEW -> PUBLISHED
                                      \-> REJECTED/DISABLED
```

Agent 对 Tool/MCP/Skill 的引用在保存和发布时校验：引用资源必须属于当前组织、当前用户，或属于已审核的系统公共范围。

## 7. 角色与权限

| 角色 | 主要权限 |
|---|---|
| PLATFORM_ADMIN | 平台用户审核、公共资产、全局策略和审计 |
| ORG_ADMIN | 组织成员、组织资产和组织配额 |
| BUILDER | 创建和编辑自己的资产，申请发布 |
| TESTER | 使用授权资产，运行测试，不能发布或管理成员 |
| VIEWER | 查看和使用被授权资产 |

权限判断必须由后端完成：

```text
principal = authenticated session
tenant = membership(principal, requested tenant)
resource = load resource by id
authorize(principal, tenant, action, resource)
```

请求头和查询参数只能作为兼容信息，不能覆盖认证主体。管理员代操作必须显式开启并写入审计日志。

## 8. 文件、知识库和运行数据隔离

### 8.1 文件和 Workspace

用户不能提交任意服务器路径。平台根据资源 ID 生成路径：

```text
workspace/tenants/{orgId}/users/{userId}/agents/{agentId}/
workspace/tenants/{orgId}/users/{userId}/workflows/{workflowId}/
workspace/tenants/{orgId}/documents/{documentId}/
```

所有下载、预览、上传、移动和删除操作都必须先验证资源归属。

### 8.2 Qdrant

每个 chunk 必须写入 `org_id`，建议同时写入 `owner_id` 和 `visibility`。所有查询、删除和重建索引都由服务端追加租户过滤，不能使用客户端传入的过滤器作为唯一条件。

### 8.3 Session、Memory、Run、Trace

运行数据的主键和查询条件应包含 `org_id + user_id`，外部 API Key 还要绑定到具体组织、用户、Agent 和 scopes，避免一个全局 Key 代表所有用户。

## 9. MCP、Tool、Skill 安全策略

用户创建的 MCP、HTTP Tool、Skill 默认只允许私有测试。发布为组织或平台公共资产前必须经过审核。普通用户不能创建 stdio MCP、Python Tool 或直接修改平台公共资产。

最低安全要求：

- stdio MCP 在沙箱/容器内运行；当前入口仍仅允许平台管理员配置。
- 普通用户的远程 MCP 仅接受 HTTP/SSE 地址，并拒绝明显的本机、内网和链路本地地址；上线前仍需补 DNS 解析后的出口策略。
- 普通用户的 HTTP Tool 由服务端代发请求，资产记录绑定当前用户和组织，其他用户只能看到公共资产。
- Skill 内容写入 `workspace/user-assets/{userId}/skills/{skillId}/SKILL.md`，不接受调用方任意服务器路径。
- 限制命令、工作目录、网络地址、CPU、内存、进程数和执行时长。
- 使用 Secret 引用，不允许把真实密钥直接写入 MCP 配置。
- Python Tool 禁止访问整个服务器 workspace。
- 公共资产发布前检查依赖、脚本内容、外网访问和凭据使用。
- 失败、超时、超额和禁用动作全部写入审计日志。

## 10. API 与前端页面

### 10.1 账号 API

```text
POST /auth/apply
GET  /auth/applications/{id}/status
POST /auth/setup-password
POST /auth/login
POST /auth/logout
GET  /auth/me
```

### 10.2 管理 API

```text
GET  /admin/account-applications
POST /admin/account-applications/{id}/approve
POST /admin/account-applications/{id}/reject
GET  /admin/users
GET  /admin/audit-events
GET  /admin/email-outbox
```

### 10.3 资产 API

资产 API 不接受调用方指定的 owner。owner、org 和 created_by 从当前认证主体得到。

```text
GET/POST /api/me/agents
GET/POST /api/me/workflows
GET/POST /api/me/mcps
GET/POST /api/me/skills
GET      /api/catalog/public
POST     /api/assets/{id}/submit-review
```

前端可继续使用画布坐标，但坐标应作为 `config.canvas_position` 持久化，运行模型只接收规范化后的节点字段。

## 11. 配额与反滥用

每个组织配置：

- 成员数量
- Agent/Workflow/MCP/Skill 数量
- 文件总容量
- 单文件大小
- 每日运行次数
- 并发运行数
- MCP/Tool 执行时长
- 邮件申请和登录失败频率

申请接口、登录接口和 Token 使用接口增加 IP/邮箱限流、验证码和账号锁定策略。对外返回错误时避免暴露“邮箱是否已注册”等用户枚举信息。

## 12. 分阶段实施

### P0：认证与安全基线（已完成 MVP）

- 用户、组织、Membership、Session。
- 平台管理员迁移。
- 统一 `CurrentPrincipal` 和 `TenantContext`。
- 移除可伪造请求头作为身份来源。
- 管理员接口和资产写接口加授权。
- 现有资产标记为系统公共资产。

### P1：申请、审核和邮件（已完成 MVP；SMTP 为部署配置项）

- 申请页面和管理员审核页面。
- 审批状态机、审计日志。
- 一次性密码设置链接。
- SMTP 配置、email outbox、失败重试和发送状态。

### P2：资产归属和数据过滤（主要链路已完成）

- Agent、Workflow、MCP、Skill、Tool、知识库和文件补充 owner/org/visibility/status。
- 列表、详情、修改、删除、执行统一做授权。
- Qdrant、Workspace、Session、Memory、Run 做租户过滤。

### P3：个人测试空间（MVP 已完成，沙箱与配额待补）

- 我的资产页面。
- 复制公共资产。
- 私有草稿和测试运行。
- 资源配额和 MCP/Tool 沙箱。

### P4：公共资产治理

- 发布审核、版本、下架和回滚。
- 使用统计。
- 组织级 API Key 和 scopes。
- 审计导出和管理报表。

## 13. 验收标准

1. 用户 A 无法读取、修改或删除用户 B 的私有资产。
2. 伪造 `x-user-id/x-org-id` 不会改变当前身份和数据范围。
3. 未审核用户无法登录或调用受保护接口。
4. 审批通过后能生成一次性初始化链接，链接过期或使用后不可再次使用。
5. 公共资产可使用但普通用户不能直接修改。
6. Qdrant 检索不会跨组织返回数据。
7. 用户无法通过路径参数读取任意服务器文件。
8. 用户创建的 MCP/Tool 不能访问其他用户的 workspace 和未授权凭据。
9. 邮件失败可重试，审批状态和发送状态可追踪。
10. Workflow 新建、保存、重启恢复后，节点画布位置保持一致，UI 字段不会污染运行模型。

## 14. 当前待办

- 继续补充 Workflow、知识库、文件和运行记录的跨账号浏览器回归。
- 生产部署必须配置安全 Cookie、SMTP、限流和备份；不能把本地开发环境的初始化链接策略带入生产。
- 任何扩大到 stdio/Python 或公共发布的入口必须先完成独立运行沙箱、凭据隔离、网络出口策略和资源配额。
- Workflow 已先行接入 Session 隔离：网页 CRUD/运行需要登录，私有资产按 owner，组织资产按 org，公共资产可读；Workflow Tool 的全局注册仍只允许平台管理员。
- Agent 已接入轻量资产元数据表：旧配置自动标记为 `SYSTEM/PUBLIC`，新建 Agent 默认 `USER/PRIVATE`，目录、详情、配置管理和运行入口按 Session 授权。

## 15. MVP 启动配置

首次部署建议通过环境变量配置平台管理员，不把初始密码写入仓库：

```text
AGENT_PLATFORM_AUTH_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
AGENT_PLATFORM_AUTH_BOOTSTRAP_ADMIN_PASSWORD=<首次管理员密码>
AGENT_PLATFORM_AUTH_BASE_URL=https://agent.example.com
AGENT_PLATFORM_AUTH_SECURE_COOKIE=true
```

需要邮件发送时，再配置：

```text
AGENT_PLATFORM_AUTH_MAIL_ENABLED=true
AGENT_PLATFORM_SMTP_HOST=smtp.example.com
AGENT_PLATFORM_SMTP_PORT=587
AGENT_PLATFORM_SMTP_USERNAME=...
AGENT_PLATFORM_SMTP_PASSWORD=...
AGENT_PLATFORM_SMTP_AUTH=true
AGENT_PLATFORM_SMTP_STARTTLS=true
```

账号入口为 `/platform/live/access`。邮件未启用时，管理员审核接口仅在开发环境返回一次性初始化链接；生产环境应启用 SMTP，避免通过管理页面传递初始化凭据。

## 2026-08 implementation update

本轮已落地：

- `platform_asset_metadata` 统一记录 Tool、MCP、Skill 的 owner、组织、可见性和状态；历史注册表资产自动登记为平台公共资产。
- `platform_user_capabilities` 持久化普通用户的私有远程 MCP、HTTP Tool 和 Skill；资产查询、修改、删除和运行入口按当前 Session 的 owner/org 校验。
- Tool/MCP/Skill 目录读取要求登录并按可见性过滤；普通用户可以创建和测试自己的远程 MCP、HTTP Tool、Skill，stdio MCP、Python 工具执行和全局注册表写操作仍要求平台管理员。
- Agent、Workflow、文档、知识库分组、附件、运行记录和聊天会话均接入当前登录主体；HTTP 请求头不再决定用户或组织范围。
- 文档上传默认归属当前用户并设为私有；检索、预览、重建索引、删除按可读/可写规则校验。
- 运行记录、步骤、事件、人工等待、会话及附件按用户和组织隔离；管理员可以进行平台级运维查看。
- 长期记忆创建时强制写入当前用户和组织，列表、详情、更新、删除按归属校验。
- 账号审计使用独立的 `platform_account_audit_events` 表，兼容历史版本中已有的运行事件表 `platform_audit_events`，避免升级旧 SQLite 数据库时发生 schema 冲突。
- 前端启动后从 `/platform/auth/me` 同步认证主体；URL、localStorage 和 `x-user-id/x-org-id` 只作为兼容信息，不能覆盖已登录主体。Vite 开发代理同时转发 `/platform/auth` 和 `/platform/admin`。

### 16. 验证记录

- 后端：`mvn -q -DskipTests compile` 通过。
- 后端全量：`mvn -q test`，19 个测试套件、51 个测试全部通过，0 failures / 0 errors；其中包含账号、资产、Agent、Workflow 隔离、会话附件重启恢复、会话归属校验和 RAG 范围安全回归。
- 前端：`frontend/live-console/npm run build` 通过；Vite 仅提示 bundle 超过 500 kB 的性能建议。
- Playwright：已验证申请、管理员批准、一次性密码设置、普通用户登录、个人 MCP/Skill/HTTP Tool 创建、刷新恢复、公共资产可见性、个人工具测试入口，以及对话附件上传后在知识库“对话文件”中的默认展示；期间修复了认证代理缺失、旧审计表 schema 冲突、管理员路径变量绑定、不可变列表修改、前端硬编码管理员身份、个人 Skill 默认过滤和 multipart 会话参数未绑定等问题。

## 17. 对话文件与知识库归档

对话中由用户上传的 PDF、Office、Markdown、TXT、CSV 文件现在按以下方式处理：

1. 文件本体写入 `workspace/documents/{doc_id}/v1/`，文档解析结果、原始字节和来源元数据写入 SQLite 的 `platform_knowledge_documents`；服务重启后会重新加载。
2. 文档记录带有 `source_type=conversation_attachment` 和 `source_session_id`，知识库页面提供系统虚拟文件夹“对话文件”，默认打开并按当前账号可见范围展示。
3. 会话与附件的关系写入 `platform_session_attachments`，因此重启后仍能在原对话恢复附件列表；删除会话/附件时同步清理关系记录。知识库中的文档本体不会因会话关系删除而丢失。
4. “对话文件”是自动归档视图，不是用户可上传的普通知识库分组；普通上传仍只能选择“全部文档”“未分组”或实际分组。

生成文件统一通过 `POST /platform/session/generated-artifacts`（`/platform/session/artifacts` 为别名）上传，携带 `session_id`、可选 `message_id` 和文件内容；服务会按 `source_type=conversation_generated` 写入同一套文档表、原始文件存储、解析和向量索引链路，并自动出现在“对话文件”视图中。普通文本回答不会被误认为文件。

### 17.1 检索安全约束

- 未指定文档范围时，只检索当前主体可读的文档。
- 指定 `doc_id` / `document_ids` / 附件 / `allowed_doc_versions` 时，服务端按 `doc_id + version_id` 做交集过滤；无权限、不存在或版本不匹配时返回空检索结果，不能回退到全量检索。
- Agent 工作台和交互问答统一读取上述范围字段；工作台切换或刷新会话时恢复已持久化附件。
- 同一用户、组织和来源类型的相同内容按 SHA-256 去重；从会话移除附件只删除会话关系，知识库文件保留。
- 上传附件前必须确认 `session_id` 属于当前用户和组织；无法确认归属时返回 404，不允许仅凭猜测的会话 ID 写入关系。
