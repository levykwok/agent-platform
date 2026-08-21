# LLM Router and Supervisor decisions

## Decision

Router and Supervisor selection are model decisions. Keyword matching is not the primary runtime path.

- Router performs one stateless, tool-free LLM call and must return one allowed `route_id`.
- Supervisor performs one stateless, tool-free LLM call and returns the smallest sufficient set of allowed `binding_id` values.
- Supervisor child Agents still run with binding-scoped capabilities, in parallel up to the configured concurrency limit.
- Supervisor performs its normal final LLM synthesis after the selected children return.

The decision model receives only the original user request and a platform-generated candidate manifest. Retrieved document context is removed before routing so RAG content cannot redirect orchestration. Candidate system prompts are not copied into the decision prompt. Candidate IDs are validated against the published configuration before execution. User text cannot add a route, Agent, Tool, MCP server, or Skill.

## Model resolution

The decision call uses the first configured model in this order:

1. `model_policy.router_decision` or `model_policy.supervisor_decision`, according to mode.
2. `model_policy.orchestration`.
3. `model_policy.routing`.
4. The Agent's normal QA/chat model resolution.

This permits a smaller classification model for routing without changing the child or synthesis model.

## Output contracts

Router:

```json
{"route_id":"allowed-route-id","reason":"brief explanation"}
```

Supervisor:

```json
{"binding_ids":["allowed-binding-id"],"reason":"brief explanation"}
```

Markdown fences and explanatory prose are discouraged, but the parser can extract one JSON object from surrounding text. Unknown IDs are never executed.

## Failure behavior

Availability is preserved if the model times out, fails, or returns invalid JSON:

- Router uses the configured default route, or the first declared route when no default exists.
- Supervisor uses the declared subagents up to `max_subagents`.

Fallback is explicit rather than silent. The persisted decision event contains `decision_source=fallback`, the reason, and elapsed decision time. E2E validation requires `decision_source=llm` for both modes and fails on fallback.

## Observability

Router emits:

- `router_decision_start`
- `router_decision`

Supervisor emits:

- `supervisor_decision_start`
- `supervisor_decision`
- `supervisor_subagent_selected` for each selected child

Completion events include `decision_source`, `model_id`, `duration_ms`, selected IDs, and the model's brief reason. Decision prompts and raw model output are not persisted.

## Real validation — 2026-08-21

An isolated backend and SQLite copy were used so the deployed development service was not interrupted.

| Mode | Run | Total | LLM decision | Decision model | Result |
|---|---|---:|---:|---|---|
| Router | `run_60da62c62dda49d08041aca00d6096a1` | 19,307 ms | 5,121 ms | `qwen3.7-plus` | selected `serial-workflow` |
| Supervisor | `run_fcbfd55f449c4b2f988babd45c744a06` | 64,790 ms | 7,012 ms | `qwen3.7-plus` | selected both declared children |

A Router request deliberately omitted every configured route keyword and the `contains` phrase. The LLM still selected `serial-workflow` from the semantic two-stage intent:

- Run: `run_60da62c62dda49d08041aca00d6096a1`
- Decision source: `llm`
- Decision time: 5,121 ms
- Target: `orchestration-e2e-workflow`

The full automated report is generated under the ignored `output/` directory by `scripts/run-orchestration-e2e.ps1`.
