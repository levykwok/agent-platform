# Orchestration and External Workbench Validation — 2026-08-24

## Result

PASS on the current packaged application in an isolated SQLite workspace. The user's services on ports 8080 and 5173 were not modified.

Canonical Agent orchestration is now `SINGLE`, `PIPELINE`, `ROUTER`, or `SUPERVISOR`. `WORKFLOW` remains the name of the standalone canvas asset only. A persisted Pipeline run snapshot contained `mode: PIPELINE` and `orchestration.pipeline`, with no `orchestration.workflow` property.

## Real-model runs

| Mode | Run | Total | Decision / child timing | Result |
|---|---|---:|---|---|
| SINGLE | `run_a073d11ed3bb46399f902047432fe6bb` | 11,115 ms | one model call, first token at 11,021 ms | `E2E_ANALYSIS_OK` |
| PIPELINE | `run_43d0db808dcc4bfa8051c5eb69095f26` | 18,743 ms | analyze about 12.2 s; final format about 6.3 s | `E2E_FORMAT_OK` |
| ROUTER | `run_383b662d01df48b184f677587c1f08ab` | 29,494 ms | LLM route decision 10,168 ms; selected Pipeline then ran both children | `E2E_FORMAT_OK` |
| SUPERVISOR | `run_bb45cc5dc57c407f8a10c4e0f32796bc` | 74,022 ms | PLAN 12,526 ms; two parallel children 14,550/14,545 ms; REVISE 8,482 ms; final synthesis consumed the remaining time | `E2E_SUPERVISOR_OK` |

The first attempt had one transient child-model failure after about 12 seconds. An immediate full retry passed all four modes. This is recorded as upstream/model variability rather than a Pipeline schema failure: Pipeline start, step start, child start, and model-generation events were all present before the failed provider call.

The generated detailed timeline and persisted-event report is in the ignored local artifact `output/orchestration-e2e-current-retry/report.md`.

## External API and workbench

- A real synchronous `/api/v1` request with `files[].content` returned HTTP 200 and `EXTERNAL_FILE_OK`.
- A real SSE request returned HTTP 200 and the event types `single_agent_start`, `capability_loaded`, `text_block_delta`, `agent_result_validated`, and `orchestration_budget`.
- The workbench supports X-API-Key/Bearer authentication, sync/SSE calls, image Base64 input, bounded inline text files, event timing/type summaries, Token/tool counters, response copy/download, code generation, and 20 in-memory replay entries.

## Automated evidence

- `mvn -o clean test`: 93 tests, 0 failures, 0 errors.
- `npm run build`: Vue type-check and production Vite build passed.
- Playwright with real Chrome: 2 tests passed in 2.9 minutes.
  - historical Agent configuration rendered as canonical Pipeline; no WORKFLOW option; existing steps loaded;
  - a newly created Pipeline survived save and page reload;
  - the external workbench placed a selected Markdown file in the real request body and rendered HTTP 200, a non-empty answer, history, and diagnostics.

Screenshots are available in ignored local artifacts:

- `output/playwright/pipeline-canonical-current.png`
- `output/playwright/external-workbench-current.png`
