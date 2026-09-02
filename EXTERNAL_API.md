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

Managed keys support persisted policy controls:

- `allowed_agent_ids`: optional Agent allowlist; an empty list means every Agent readable by the owner.
- `capabilities`: one or both of `chat` (synchronous JSON) and `stream` (SSE).
- `expires_at`: optional future ISO-8601 timestamp.
- `rate_limit_per_minute`: fixed-minute invocation limit from 1 to 6000; the default is 60.

Example creation payload:

```json
{
  "name": "CRM production",
  "allowed_agent_ids": ["customer-support"],
  "capabilities": ["chat"],
  "expires_at": "2026-12-01T00:00:00Z",
  "rate_limit_per_minute": 120
}
```

Policy checks happen before a Run is created. Disallowed Agents or modes return HTTP 403. Rate
limited requests return HTTP 429 with `Retry-After`, `X-RateLimit-Limit`,
`X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers. The limiter is SQLite-backed, so the
active minute window survives backend restarts. Existing managed keys are migrated to all readable
Agents, both invocation modes, and 60 requests per minute.

Authentication and policy rejections are persisted as sanitized `REJECTED` audit rows. They contain
the status/error code, request path, direct peer IP, and a short SHA-256 credential fingerprint for
correlation. They never contain the supplied credential, request body, attachments, or response
content. Invalid credentials are attributed to an anonymous external caller; managed-key 403/429
events remain visible to the key owner.

Owners can rotate an active key with `POST
/platform/frontend/external-api/keys/{keyId}/rotate`. The payload accepts `grace_minutes` from 0 to
1440 (default 15). Rotation creates a new one-time secret with the same owner, Agent allowlist,
capabilities, rate limit, and original expiry. The old secret remains valid until the grace deadline;
zero revokes it immediately.

Each synchronous or SSE invocation now creates a durable platform Run. The response (or initial
`external_run_started` SSE event) includes `request_id`, `run_id`, and `observe_url`. The workbench
stores a sanitized request history in SQLite and links each item to Run Observation. Image bytes and
inline file contents are deliberately not retained in invocation history, so requests containing
attachments require those attachments to be selected again before replay.

Logged-in management endpoints:

- `GET /platform/frontend/external-api/keys`
- `POST /platform/frontend/external-api/keys`
- `POST /platform/frontend/external-api/keys/{keyId}/revoke`
- `POST /platform/frontend/external-api/keys/{keyId}/rotate`
- `GET /platform/frontend/external-api/invocations?limit=50`
- `GET /platform/frontend/external-api/admin/overview?limit=50` (platform administrator only)
- `GET /platform/frontend/external-api/admin/keys?query=&status=ALL&limit=100` (platform administrator only)
- `POST /platform/frontend/external-api/admin/keys/{keyId}/suspend` (platform administrator only)
- `POST /platform/frontend/external-api/admin/keys/{keyId}/resume` (platform administrator only)
- `POST /platform/frontend/external-api/admin/keys/{keyId}/revoke` (platform administrator only)

The administrator overview aggregates key state, invocation totals, recent activity, Agent usage,
failure counts, and average duration. It reads the same sanitized invocation audit records and never
returns API-key secrets or attachment contents.
The administrator key inventory can search by owner email, key name, or key prefix and filter active,
suspended, revoked, or expired keys. Administrators can temporarily suspend/resume or permanently
revoke a user's key, but cannot rotate it or retrieve a replacement secret on the user's behalf.

Comma-separated keys configured with `AGENT_PLATFORM_EXTERNAL_API_KEYS` remain supported for
backward compatibility. They are legacy shared credentials, have access only to public Agents, and
their invocation history is not exposed to a platform account.
