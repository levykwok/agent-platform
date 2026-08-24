# Supervisor multi-step E2E validation — 2026-08-21

## Result

The adaptive Supervisor completed a real model-backed PLAN/STEP/REVISE/SUMMARY run successfully.

- Run: `run_de18f87c844043448c41d31e881ee5c2`
- Total: 76,729 ms
- First final-answer token: 67,816 ms
- Result: `E2E_SUPERVISOR_OK | analysis=true | format=true`
- Decision source: LLM for PLAN and both REVISE decisions

## Timeline

| Phase | Duration | Result |
|---|---:|---|
| PLAN | 22,699 ms | planned `analysis-child`, then `format-child` |
| Child step 1 | 8,125 ms | analysis result |
| REVISE 1 | 12,119 ms | `NEXT` → `format-child` |
| Child step 2 | 4,647 ms | formatting result |
| REVISE 2 | 5,083 ms | `FINISH` |
| SUMMARY | about 13,995 ms | final acceptance marker |

## Decision-model optimization

The same case was rerun with `qwen3.7max` (reasoning disabled) assigned only to the orchestration decision policy. Child Agents and final synthesis remained on `qwen3.7-plus`.

- Optimized run: `run_c772019d0883486a9fd6eba06dfbcccc`
- Total: 27,020 ms (64.8% lower)
- First final-answer token: 26,608 ms
- Result: `E2E_SUPERVISOR_OK | analysis=true | format=true`
- PLAN and both REVISE events: `decision_source=llm`, `model_id=qwen3.7max`

| Phase | Before | After | Change |
|---|---:|---:|---:|
| PLAN | 22,699 ms | 2,130 ms | -90.6% |
| Child step 1 | 8,125 ms | 4,333 ms | -46.7% |
| REVISE 1 | 12,119 ms | 2,190 ms | -81.9% |
| Child step 2 | 4,647 ms | 5,001 ms | +7.6% |
| REVISE 2 | 5,083 ms | 1,616 ms | -68.2% |
| SUMMARY | about 13,995 ms | about 11,255 ms | -19.6% |

The three orchestration-control calls fell from 39,901 ms to 5,936 ms (85.1% lower). The largest remaining segment is the final QA-model synthesis, which is intentionally independent of the fast decision-model slot.

The persisted event sequence was:

1. `supervisor_plan_start`
2. `supervisor_plan`
3. `supervisor_step_start` (step 1)
4. `supervisor_subagent_result` (step 1)
5. `supervisor_revise_start`
6. `supervisor_revise` (`NEXT`)
7. `supervisor_step_start` (step 2)
8. `supervisor_subagent_result` (step 2)
9. `supervisor_revise_start`
10. `supervisor_revise` (`FINISH`)
11. `supervisor_summary_start`
12. `supervisor_summary_end`

## Browser validation

Playwright opened the Agent management page against an isolated backend and SQLite copy, edited the Supervisor maximum step count from 5 to 3, saved it, and verified both:

- overview text: `SUPERVISOR · 2 个子代理 · 最多 3 步`
- API value: `orchestration.maxSupervisorSteps = 3`

Screenshot: `output/playwright/supervisor-multistep-config.png` (ignored test artifact).

The decision-model optimization UI was also validated with Playwright against the same isolated backend. The test verified that:

- the Model management page renders `orchestration`, `router_decision`, and `supervisor_decision` platform slots;
- the Agent model-policy step renders the same decision controls;
- `supervisor_decision=qwen3.7max` can be saved on the existing Supervisor example;
- a full page reload preserves and reselects that value.

Playwright result: 1 passed in 9.6 seconds. Screenshots:

- `output/playwright/decision-model-platform-slots.png`
- `output/playwright/decision-model-agent-policy.png`

## Isolation

The validation backend used `output/supervisor-multistep-e2e/workspace/platform-platform.db`, copied from the development database. Temporary authentication rows in that copy were reset to create a disposable Playwright administrator. The original workspace database was not modified.
