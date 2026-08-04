# Company Platform SQLite Storage Design

## 目标

当前 `company-platform` 同时使用 YAML、JSON、classpath 默认配置和 workspace 文件保存平台状态。随着模型、MCP、Skill、Agent、会话和运行记录增加，文件持久化已经暴露出几个问题：

- 启动目录变化会导致 workspace 指向不同位置。
- YAML/JSON 分散在不同文件，缺少统一索引和事务边界。
- JSON 结构容易被空值、字段演进和列表过滤逻辑破坏。
- `validated / published / rejected` 这类状态只覆盖当前记录，缺少可追溯事件流水。
- 后续切换到 MySQL/PostgreSQL 时，如果继续堆文件，迁移成本会变高。

本设计引入 SQLite 作为轻量平台元数据存储。SQLite 不需要独立数据库服务，数据落在一个 `.db` 文件里，适合作为开发期和单机部署期的统一存储层。

## 非目标

- 不把 Skill zip、解压后的 Skill 文件、附件、大日志全文直接塞进数据库。
- 不一次性迁移所有模块。
- 不引入 JPA/Hibernate。
- 不在第一阶段做复杂多租户权限模型。

## 存储位置

数据库文件：

```text
workspace/platform-platform.db
```

配置项：

```yaml
company:
  platform:
    persistence:
      mode: ${COMPANY_PLATFORM_PERSISTENCE_MODE:file}
      sqlite:
        url: ${COMPANY_PLATFORM_SQLITE_URL:jdbc:sqlite:${company.platform.workspace}/platform-platform.db}
        config-table: ${COMPANY_PLATFORM_SQLITE_CONFIG_TABLE:platform_config}
        session-table-prefix: ${COMPANY_PLATFORM_SQLITE_SESSION_TABLE_PREFIX:platform_}
```

大文件继续保留在 workspace：

```text
workspace/skills/{skill_id}/...
workspace/cache/skill-packages/{package_id}.zip
workspace/attachments/...
```

SQLite 只保存元数据、状态、索引和文件路径。

## 技术选型

依赖：

```xml
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
</dependency>
```

推荐实现：

- `JdbcTemplate`
- 手写 SQL schema migration
- `platform_schema_version` 表记录版本

暂不使用：

- JPA/Hibernate
- MyBatis
- Flyway/Liquibase

原因：当前平台仍在快速调整，直接 SQL + 轻量 repository 更容易控制字段、迁移和兼容。

## 分层设计

新增存储抽象：

```text
com.company.platform.storage
  PlatformStorageProperties
  PlatformDatabase
  PlatformSchemaMigrator
  DomainRepository
  SkillPackageRepository
  ModelRepository
  ProviderRepository
  McpServerRepository
```

调用关系：

```text
Controller
  -> PlatformCompatibilityState / Registry
    -> Repository
      -> SQLite
```

过渡期保留现有 registry：

- 读写入口逐步切到 repository。
- YAML/JSON 作为初始导入来源。
- classpath 默认配置作为 seed 数据来源。

## 第一阶段迁移范围

第一阶段优先迁移“配置元数据”和“审核状态”，不碰高频运行事件。

### domains

替代：

```text
workspace/domains.json
```

表：

```sql
CREATE TABLE IF NOT EXISTS platform_domains (
  domain TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  description TEXT,
  org_id TEXT NOT NULL DEFAULT 'platform',
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

用途：

- 所有页面的 Domain 下拉选择来源。
- Agent、Skill、MCP、模型策略后续都引用该表。

### skill_packages

替代：

```text
workspace/cache/skill-packages.json
```

表：

```sql
CREATE TABLE IF NOT EXISTS skill_packages (
  package_id TEXT PRIMARY KEY,
  skill_id TEXT NOT NULL,
  name TEXT NOT NULL,
  version TEXT NOT NULL,
  domain TEXT NOT NULL,
  source TEXT NOT NULL,
  source_note TEXT,
  upstream_skill_name TEXT,
  filename TEXT,
  zip_path TEXT NOT NULL,
  description TEXT,
  status TEXT NOT NULL,
  validation_errors_json TEXT NOT NULL DEFAULT '[]',
  granted_permissions_json TEXT,
  created_at TEXT NOT NULL,
  published_at TEXT,
  rejected_at TEXT,
  reject_reason TEXT,
  FOREIGN KEY(domain) REFERENCES platform_domains(domain)
);
```

状态：

```text
validated
published
rejected
deprecated
```

说明：

- 上传成功后创建一条 `validated`。
- 发布后同一条记录改为 `published`。
- 拒绝后同一条记录改为 `rejected`。
- ZIP 文件路径仍保存到 `zip_path`。

### skill_package_events

新增，不替代现有文件。

表：

```sql
CREATE TABLE IF NOT EXISTS skill_package_events (
  event_id TEXT PRIMARY KEY,
  package_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  actor TEXT,
  detail_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL,
  FOREIGN KEY(package_id) REFERENCES skill_packages(package_id)
);
```

事件类型：

```text
uploaded
validated
validation_failed
published
rejected
permissions_updated
deleted
```

用途：

- 支持审核流水。
- 支持界面详情展示“上传 -> 校验 -> 发布/拒绝”。
- 避免只看当前 `status` 时不知道发生过什么。

### skills

替代：

```text
workspace/skills.yml
classpath:skills.yml
```

表：

```sql
CREATE TABLE IF NOT EXISTS skills (
  skill_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL,
  location TEXT NOT NULL,
  source TEXT NOT NULL,
  scope TEXT NOT NULL DEFAULT 'agent',
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  writable INTEGER NOT NULL DEFAULT 0,
  package_id TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(package_id) REFERENCES skill_packages(package_id)
);
```

说明：

- 文档和脚本仍存放在 `workspace/skills/{skill_id}`。
- DB 保存注册状态和位置逻辑。
- 页面不展示物理绝对路径。

### model_providers

替代：

```text
workspace/providers.yml
```

表：

```sql
CREATE TABLE IF NOT EXISTS model_providers (
  provider_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  provider_type TEXT NOT NULL,
  base_url TEXT,
  auth_type TEXT,
  api_key_env TEXT,
  api_key_secret_ref TEXT,
  default_headers_json TEXT NOT NULL DEFAULT '{}',
  default_body_json TEXT NOT NULL DEFAULT '{}',
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

说明：

- API Key 明文不建议直接入库。
- 开发期可以保留 `api_key_env`。
- 后续接入 secret manager 时使用 `api_key_secret_ref`。

### models

替代：

```text
workspace/models.yml
```

表：

```sql
CREATE TABLE IF NOT EXISTS models (
  model_id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  model_name TEXT NOT NULL,
  model_kind TEXT NOT NULL,
  provider_call_type TEXT NOT NULL,
  endpoint_path TEXT,
  request_headers_json TEXT NOT NULL DEFAULT '{}',
  request_body_json TEXT NOT NULL DEFAULT '{}',
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(provider_id) REFERENCES model_providers(provider_id)
);
```

`model_kind`：

```text
chat
embedding
rerank
vision
audio
```

`provider_call_type`：

```text
generate
embed
rerank
custom_http
```

### mcp_servers

替代：

```text
workspace/mcps.yml
workspace/cache/mcp-discovery-cache.json
```

表：

```sql
CREATE TABLE IF NOT EXISTS mcp_servers (
  server_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  transport TEXT NOT NULL,
  command TEXT,
  args_json TEXT NOT NULL DEFAULT '[]',
  endpoint TEXT,
  description TEXT,
  timeout_ms INTEGER NOT NULL DEFAULT 5000,
  tool_filter_json TEXT NOT NULL DEFAULT '[]',
  enabled INTEGER NOT NULL DEFAULT 1,
  has_auth INTEGER NOT NULL DEFAULT 0,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

发现工具缓存：

```sql
CREATE TABLE IF NOT EXISTS mcp_discovered_tools (
  server_id TEXT NOT NULL,
  tool_name TEXT NOT NULL,
  tool_id TEXT NOT NULL,
  description TEXT,
  input_schema_json TEXT NOT NULL DEFAULT '{}',
  enabled INTEGER NOT NULL DEFAULT 1,
  discoverable INTEGER NOT NULL DEFAULT 1,
  last_discovered_at TEXT NOT NULL,
  PRIMARY KEY(server_id, tool_name),
  FOREIGN KEY(server_id) REFERENCES mcp_servers(server_id)
);
```

## 第二阶段迁移范围

第二阶段迁移运行态数据：

- `agents`
- `agent_definitions`
- `sessions`
- `agent_runs`
- `run_events`
- `run_steps`
- `memory_items`
- `audit_logs`

建议不要和第一阶段混做，避免一次改动太大。

## YAML/JSON 迁移策略

启动时执行：

1. 创建 SQLite schema。
2. 检查 `platform_schema_version`。
3. 如果 DB 为空，则从现有 YAML/JSON 导入。
4. 导入成功后不删除旧文件。
5. 旧文件改为 backup/source，不再作为主存储。

迁移标记：

```sql
CREATE TABLE IF NOT EXISTS platform_migrations (
  migration_id TEXT PRIMARY KEY,
  applied_at TEXT NOT NULL
);
```

导入记录：

```text
import_domains_json_v1
import_skill_packages_json_v1
import_models_yml_v1
import_providers_yml_v1
import_mcps_yml_v1
```

## API 兼容策略

前端 API 不直接感知存储变化。

继续保持：

```text
GET /platform/frontend/domains
POST /platform/frontend/domains

GET /platform/frontend/skills
POST /platform/frontend/skills/packages/upload
GET /platform/frontend/skills/packages
POST /platform/frontend/skills/packages/{id}/publish

GET /platform/frontend/models
GET /platform/frontend/providers
GET /platform/frontend/mcp
```

Controller 和前端不用关心 YAML 还是 SQLite。

## 事务要求

以下操作必须在事务中完成：

- 上传 Skill 包：写 zip 成功后写 DB；DB 失败则删除 zip。
- 发布 Skill 包：解压目录成功后更新 package status、写 skill 注册、写 event。
- 删除 Skill：删除 registry、删除绑定、写 audit。
- MCP discovery sync：更新 server metadata 和 discovered tools。
- Model/provider 保存：保存 provider/model 和审计记录。

## 文件与 DB 一致性

Skill 包发布：

```text
skill_packages.status = published
skills.skill_id = package.skill_id
workspace/skills/{skill_id}/SKILL.md exists
```

如果解压失败：

- package 保持 `validated`
- 写 event `publish_failed`
- 不更新 skills 表

如果 DB 更新失败：

- 尽量回滚解压目录
- 写日志
- 返回明确错误

## 备份策略

开发期：

```text
workspace/platform.db
workspace/platform.db.backup-{timestamp}
```

上线前：

- 启动前自动备份一次。
- 提供导出 JSON/YAML 命令。

## 后续切换 MySQL/PostgreSQL

Repository 层保持接口不变。

SQLite 特有点限制在：

- SQL 方言
- 连接字符串
- schema migration

后续切换时替换：

```text
PlatformDatabase
PlatformSchemaMigrator
Repository SQL implementation
```

不改 Controller 和前端。

## 实施顺序

建议按以下顺序实现：

1. 加 `sqlite-jdbc` 依赖。
2. 增加 `PlatformStorageProperties`。
3. 增加 `PlatformDatabase`，启动时打开 `platform.db`。
4. 增加 `PlatformSchemaMigrator`，创建第一阶段表。
5. 实现 `DomainRepository`。
6. 实现 `SkillPackageRepository` 和 `SkillPackageEventRepository`。
7. 把 `PlatformCompatibilityState` 中 domain 和 skill package 的内存 map 替换为 repository。
8. 增加从 `domains.json` 和 `skill-packages.json` 的一次性导入。
9. 跑现有页面流程：注册 Domain、上传 Skill 包、发布 Skill、拒绝 Skill 包。
10. 再迁 models/providers/mcp。

## 当前最优先修复项

在真正切 SQLite 前，当前 JSON 版本仍有两个必须注意的问题：

- `Map.copyOf` 不能复制包含 null value 的 map。
- 上传包列表不能在接口失败时显示“暂无记录”，必须显示错误。

这些问题在 SQLite 迁移后会减少，但前端错误展示仍应保留。

## 启动与验证（单机落地）

### 直接启动

```powershell
# 统一 workspace，不依赖命令行当前目录
.\start-platform.ps1 -PersistenceMode file

# 切 SQLite（开发推荐）
.\start-platform.ps1 -PersistenceMode sqlite

# 指定 workspace 路径
.\start-platform.ps1 -PersistenceMode sqlite -Workspace "D:\agent-platform\workspace"
```

### 启动后快速检查

- 日志目录：`logs\platform\`
- sqlite 文件（sqlite 模式）：`workspace\platform-platform.db`
- 常见接口：`/platform/frontend/models`、`/platform/frontend/mcps`、`/platform/frontend/agents`
- 前端入口：`/platform/live?domain=platform&org_id=platform&user_id=platform_admin`
