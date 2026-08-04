# MCP Demo 工具说明

Demo MCP 位于 `mcp-servers/platform-demo/server.mjs`，可通过三种 transport 启动：

```text
stdio: node mcp-servers/platform-demo/server.mjs --transport stdio
streamable-http: node mcp-servers/platform-demo/server.mjs --transport streamable-http --port 8765
sse: node mcp-servers/platform-demo/server.mjs --transport sse --port 8766
```

三种 transport 的差异：

```text
stdio：本地进程内调用，适合开发机直连。
streamable-http：HTTP 会话持久化，适合平台通过 URL 绑定 MCP server。
sse：服务器推送式长连接，适合事件驱动与高频测试。
```

## stdio tools

| Tool | 用途 | 参数 | 测试输入 | 预期输出 | 适合测试 |
| --- | --- | --- | --- | --- | --- |
| `platform_calculate` | 纯算术表达式计算 | `expression` | `"expression": "((12 + 8) / 5) * 3"` | 返回数字结果 | 工具参数校验与模型 prompt 传参 |
| `platform_text_brief` | 文本统计与关键词抽取 | `text` | `"text": "AgentScope 支持 tool 和 memory pipeline"` | 字符数、行数、关键词列表 | 文本处理工具链 |
| `platform_markdown_toc` | 提取 markdown 标题 | `markdown` | `"markdown": "# 标题A\\n## 子标题"` | JSON 文本，含行号与层级 | schema 读取、返回结构化文本 |

## streamable-http tools

| Tool | 用途 | 参数 | 测试输入 | 预期输出 | 适合测试 |
| --- | --- | --- | --- | --- | --- |
| `platform_fetch_url` | 获取 HTTP URL 信息 | `url`, `max_chars` | `{"url":"https://example.com","max_chars":500}` | `status/title/body_preview` | HTTP MCP transport 与外网抓取 |
| `platform_extract_links` | 抽取 HTML 链接 | `html` / `url` / `limit` | `{"url":"https://example.com","limit":5}` | `links` 列表 | 网络输入与可选参数处理 |
| `platform_make_id` | 生成可读 ID | `prefix`, `count` | `{"prefix":"task_","count":3}` | 形如 `task_xxx` 的列表 | 参数默认值与列表返回 |

## sse tools

| Tool | 用途 | 参数 | 测试输入 | 预期输出 | 适合测试 |
| --- | --- | --- | --- | --- | --- |
| `platform_json_validate` | 校验 JSON 字符串 | `json` | `"json": \"{\\\"a\\\":1,\\\"b\\\":2}\"` | `valid=true, type/object keys` | 异常/边界输入的工具参数 |
| `platform_json_pick` | 按路径读取 JSON 节点 | `json`, `path` | `"json":"{\"user\":{\"name\":\"Tom\"}}","path":"user.name"` | `{\"path\":\"user.name\",\"value\":\"Tom\"}` | schema 与 JSON 路径读取 |
| `platform_date_math` | 日期时间偏移 | `date`, `days`, `hours` | `"date":"2026-07-09T10:00:00+08:00","days":1,"hours":2` | `result` ISO 日期 | 日期格式与数值参数类型 |

## 使用建议

```text
1. 先在 stdio 下验证 tools list 和参数 schema。
2. 再在 streamable-http/sse 下验证平台 MCP 绑定与 transport 差异。
3. 每个工具优先先测“正常输入+非法输入”两条用例。
```

