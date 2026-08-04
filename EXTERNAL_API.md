# External Agent API

The stable external API is versioned under `/api/v1`. It is separate from the
platform frontend compatibility endpoints.

## Configuration

Enable it with environment variables:

```powershell
$env:AGENT_PLATFORM_EXTERNAL_API_ENABLED = "true"
$env:AGENT_PLATFORM_EXTERNAL_API_KEYS = "demo-key-change-me"
```

Multiple keys may be separated by commas. Callers send either
`X-API-Key: demo-key-change-me` or `Authorization: Bearer demo-key-change-me`.

## Discover published Agents

```http
GET /api/v1/agents
X-API-Key: demo-key-change-me
```

Only the public Agent id, version, name, orchestration mode and invocation
capabilities are returned. Prompts, workspace paths and internal bindings are
not exposed.

## Synchronous invocation

```http
POST /api/v1/agents/researcher/chat
Content-Type: application/json
X-API-Key: demo-key-change-me

{
  "tenant_id": "demo",
  "user_id": "caller-001",
  "session_id": "demo-session-001",
  "message": "请用中文介绍你的能力"
}
```

The response includes `requestId`, the resolved Agent id, session/user ids and
the generated text.

## Streaming invocation

Use the same request with:

```http
POST /api/v1/agents/researcher/chat/stream
Accept: text/event-stream
```

The response is Server-Sent Events. Each event keeps the runtime event type,
event id and payload, so workflow and tool activity can be consumed by an
external caller.
