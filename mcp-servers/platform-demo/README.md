# Demo MCP transports

This folder contains one demo MCP implementation that can run as:

- stdio: `node mcp-servers/platform-demo/server.mjs --transport stdio`
- streamable HTTP: `node mcp-servers/platform-demo/server.mjs --transport streamable-http --port 8765`
- SSE: `node mcp-servers/platform-demo/server.mjs --transport sse --port 8766`

The stdio server is enabled by default in `workspace/mcps.yml`.
The HTTP/SSE entries are present but disabled by default because they require starting this process separately before binding them to an Agent.

Tool sets are intentionally different per transport so Agent/MCP routing can be tested clearly:

- stdio: `platform_echo`, `platform_text_stats`, `platform_markdown_outline`
- streamable HTTP: `platform_http_fetch`, `platform_json_get`, `platform_uuid`
- SSE: `platform_sum`, `platform_json_validate`, `platform_regex_match`
