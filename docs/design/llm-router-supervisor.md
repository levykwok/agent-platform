# LLM Router and Supervisor decisions

## Decision

Router and Supervisor selection are model decisions. Keyword matching is not the primary runtime path.

- Router performs one stateless, tool-free LLM call and must return one allowed `route_id`.
- Supervisor performs one stateless, tool-free LLM PLAN call and returns an ordered list of allowed child calls.
- Supervisor child Agents run sequentially with binding-scoped capabilities. Each step receives the original request, its delegated instruction, and bounded previous results.
- After every child call, a stateless, tool-free LLM REVISE call chooses `FINISH` or the next allowed child call. It may follow or replace the remaining plan.
- Supervisor performs a final LLM synthesis after REVISE finishes or the configured step budget is reached.

The decision model receives only the original user request and a platform-generated candidate manifest. Retrieved document context is removed before routing so RAG content cannot redirect orchestration. Candidate system prompts are not copied into the decision prompt. Candidate IDs are validated against the published configuration before execution. User text cannot add a route, Agent, Tool, MCP server, or Skill.

## Model resolution

The decision call uses the first configured model in this order:

1. `model_policy.router_decision` or `model_policy.supervisor_decision`, according to mode.
2. `model_policy.orchestration`.
3. `model_policy.routing` (legacy compatibility).
4. The platform `router_decision` or `supervisor_decision` slot, according to mode.
5. The platform `orchestration` slot.
6. The Agent's normal QA/chat model resolution.

The management UI exposes all three platform decision slots and the same Agent-level overrides. Bind a low-latency model with reasoning disabled to these slots. Decision calls are tool-free, use temperature 0, cap output at 512 tokens, and have a 30-second upper timeout. Child calls and final Supervisor synthesis continue to use their normal QA/chat model, so the faster control model does not reduce answer quality.

## Output contracts

Router:

```json
{"route_id":"allowed-route-id","reason":"brief explanation"}
```

Supervisor PLAN:

```json
{"steps":[{"binding_id":"allowed-binding-id","instruction":"specific delegated task"}],"reason":"brief explanation"}
```

Supervisor REVISE:

```json
{"action":"NEXT","next_step":{"binding_id":"allowed-binding-id","instruction":"specific delegated task"},"remaining_steps":[],"reason":"brief explanation"}
```

`{"action":"FINISH","reason":"brief explanation"}` ends delegation. Markdown fences and explanatory prose are discouraged, but the parser can extract one JSON object from surrounding text. Unknown IDs are never executed. The old `binding_ids` PLAN response remains accepted for backward compatibility.

`orchestration.maxSupervisorSteps` defaults to 5 and is clamped to 10. Repeated calls to the same binding are allowed within that total budget.

## Failure behavior

Availability is preserved if the model times out, fails, or returns invalid JSON:

- Router uses the configured default route, or the first declared route when no default exists.
- Supervisor PLAN falls back to the declared binding order within the step budget.
- Supervisor REVISE follows the next step in the remaining plan; if none remains, it finishes.

Fallback is explicit rather than silent. The persisted decision event contains `decision_source=fallback`, the reason, and elapsed decision time. E2E validation requires `decision_source=llm` for both modes and fails on fallback.

## Observability

Router emits:

- `router_decision_start`
- `router_decision`

Supervisor emits:

- `supervisor_plan_start`
- `supervisor_plan`
- `supervisor_step_start` and `supervisor_subagent_result` for each child call
- `supervisor_revise_start` and `supervisor_revise` after each child call below the budget
- `supervisor_summary_start` and `supervisor_summary_end`

Decision events include `decision_source`, `model_id`, `duration_ms`, step IDs, and the model's brief reason. Decision prompts and raw model output are not persisted.

## Real validation — 2026-08-21

An isolated backend and SQLite copy were used so the deployed development service was not interrupted.

| Mode | Run | Total | LLM decision | Decision model | Result |
|---|---|---:|---:|---|---|
| Router | `run_60da62c62dda49d08041aca00d6096a1` | 19,307 ms | 5,121 ms | `qwen3.7-plus` | selected `serial-workflow` |
| Supervisor (legacy one-shot baseline) | `run_fcbfd55f449c4b2f988babd45c744a06` | 64,790 ms | 7,012 ms | `qwen3.7-plus` | selected both declared children |

A Router request deliberately omitted every configured route keyword and the `contains` phrase. The LLM still selected `serial-workflow` from the semantic two-stage intent:

- Run: `run_60da62c62dda49d08041aca00d6096a1`
- Decision source: `llm`
- Decision time: 5,121 ms
- Target: `orchestration-e2e-workflow`

The Supervisor measurement above predates the adaptive PLAN/REVISE loop and is retained only as a baseline. The full automated report is generated under the ignored `output/` directory by `scripts/run-orchestration-e2e.ps1`.

### Fast decision-model validation

The same adaptive Supervisor case was rerun with `qwen3.7max` (reasoning disabled) assigned only to `model_policy.orchestration`; child Agents and final synthesis remained on `qwen3.7-plus`.

| Phase | QA-model baseline | Fast decision model | Change |
|---|---:|---:|---:|
| PLAN | 22,699 ms | 2,130 ms | -90.6% |
| REVISE 1 | 12,119 ms | 2,190 ms | -81.9% |
| REVISE 2 | 5,083 ms | 1,616 ms | -68.2% |
| All control decisions | 39,901 ms | 5,936 ms | -85.1% |
| Supervisor total | 76,729 ms | 27,020 ms | -64.8% |

Optimized run: `run_c772019d0883486a9fd6eba06dfbcccc`. PLAN and both REVISE events persisted `decision_source=llm` and `model_id=qwen3.7max`. The remaining largest segment was the final QA-model SUMMARY at about 11.3 seconds.
