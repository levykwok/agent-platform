# External Agent API

The stable external API is versioned under `/api/v1`. It is separate from the
platform frontend compatibility endpoints.

## Configuration

Enable it with environment variables:

```powershell
$env:AGENT_PLATFORM_EXTERNAL_API_ENABLED = "true"
$env:AGENT_PLATFORM_EXTERNAL_API_KEYS = "demo-key-change-me"
```

Multiple legacy keys may be separated by commas. The workbench can also create account-bound keys
without changing environment variables. Callers send either
`X-API-Key: demo-key-change-me` or `Authorization: Bearer demo-key-change-me`.

## Discover published Agents

```http
GET /api/v1/agents
X-API-Key: demo-key-change-me
```

Managed keys can discover Agents readable by their owning account. Legacy environment keys can
discover public Agents only. Responses include the Agent id, version, name, orchestration mode and
invocation capabilities; prompts, workspace paths and internal bindings are not exposed.

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

For managed keys, the platform derives the runtime tenant and account from the key. Request
`tenant_id` is ignored and `user_id` is treated only as the caller's business identifier.

The response includes `requestId`, `runId`, `observeUrl`, the resolved Agent id, external business
user id, session id and generated text.

## Streaming invocation

Use the same request with:

```http
POST /api/v1/agents/researcher/chat/stream
Accept: text/event-stream
```

The response is Server-Sent Events. Each event keeps the runtime event type,
event id and payload, so workflow and tool activity can be consumed by an
external caller.

# Managed API keys and invocation audit

The external workbench can create an API key for the currently signed-in platform account. The
plain-text secret is returned once; SQLite stores only its SHA-256 hash. A managed key is bound to
the account and organization that created it, and callers cannot override that runtime identity by
passing `tenant_id` or `user_id` in a chat request. `user_id` remains available as an external
business identifier and is namespaced before it reaches the Agent runtime.

Each synchronous or SSE invocation now creates a durable platform Run. The response (or initial
`external_run_started` SSE event) includes `request_id`, `run_id`, and `observe_url`. The workbench
stores a sanitized request history in SQLite and links each item to Run Observation. Image bytes and
inline file contents are deliberately not retained in invocation history, so requests containing
attachments require those attachments to be selected again before replay.

Logged-in management endpoints:

- `GET /platform/frontend/external-api/keys`
- `POST /platform/frontend/external-api/keys`
- `POST /platform/frontend/external-api/keys/{keyId}/revoke`
- `GET /platform/frontend/external-api/invocations?limit=50`

Comma-separated keys configured with `AGENT_PLATFORM_EXTERNAL_API_KEYS` remain supported for
backward compatibility. They are legacy shared credentials, have access only to public Agents, and
their invocation history is not exposed to a platform account.
