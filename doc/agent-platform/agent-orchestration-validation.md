# Agent orchestration validation

Validation date: 2026-08-27

## Release status

The Agent orchestration implementation is complete for Router, Pipeline, and Supervisor runtime paths. Pipeline supports both serial/branching execution and explicit parallel fan-out/barrier-join groups. SINGLE remains the direct execution baseline. Agent Pipeline and standalone Workflow are separate products and are not routed through one another.

## Current-head real-model baseline

Before adding Pipeline fan-out, the current `main` application was copied into an isolated SQLite workspace and all existing real-model examples passed:

| Mode | Total |
| --- | ---: |
| SINGLE | 20,747 ms |
| PIPELINE (serial baseline) | 24,265 ms |
| ROUTER | 19,810 ms |
| SUPERVISOR | 54,941 ms |

Runs and detailed persisted timelines are in the ignored artifact `output/orchestration-current-baseline-20260827-141102/results/`.

## Automated coverage

`mvn -o test` completed successfully after the timing-observability pass:

- 134 tests run
- 0 failures
- 0 errors
- 0 skipped

The suite includes:

- LLM Router decision parsing, whitelist validation, fallback, and `enable_thinking=false` request injection
- Pipeline ordered execution, branching, explicit parallel fan-out/join, bounded concurrency, timeout/retry/failure policy, typed joined values, group-level recovery, and legacy migration
- deterministic Pipeline fan-out and durable root-run streaming at concurrency levels 2, 4, and 8, plus mixed successful/failed branch joining as `partial`
- Supervisor PLAN/REVISE, repeated and parallel child calls, budgets, tool-scope preservation, timeout/retry/fallback, and thinking policy
- root task depth/call/token/time budgets and nested task contracts
- SQLite lease fencing, stale-owner rejection, durable cancellation, expired-run takeover, and checkpoint cleanup
- process-recovery simulations proving committed Pipeline and Supervisor phases are not repeated
- durable cancellation and recovery coverage remained green in the complete regression suite
- persisted Router/Supervisor/Pipeline evaluation suites and tenant scoping
- timing interval union, legacy-event pairing, unexplained-gap detection, per-run LLM usage, and cost aggregation

`npm run build` completed Vue type checking and the Vite production build. The only frontend warning is the existing bundle chunk-size advisory.

## Browser and HTTP validation

Playwright was run against an isolated backend on port `18082` and Vite on `15173`:

- administrator login succeeded
- the interaction page submitted through `/platform/frontend/chat/stream`
- the run appeared in Run Observation with persisted steps and events
- the orchestration evaluation editor executed a suite and retained its result
- runtime tool-manifest and evaluation APIs returned HTTP 200 even when the isolated Agent had no model

The isolated Agent intentionally has no model. Both first-class compatibility entries were invoked and returned a persisted business failure instead of an unhandled HTTP 500:

- `/platform/frontend/chat/stream`
- `/platform/frontend/agents/runs`

SQLite inspection after both calls showed terminal `FAILED` durable rows, zero lease timestamps, and zero remaining checkpoints. The browser artifact is `output/playwright/orchestration-durable-run-final.png`.

The Pipeline parallel editor received an additional Playwright pass against Vite on `5173` and an isolated backend on `18084`:

- the existing `orchestration-e2e-workflow` demo displayed both `acceptance-fanout` members and the serial join step
- changing `maxPipelineParallelism` from 2 to 3 returned HTTP 200 and survived a full page reload
- restoring the value to 2 returned HTTP 200 and survived another reload
- both parallel-group values remained `acceptance-fanout` after the save/reload cycle

The final browser artifact is `output/playwright/pipeline-parallel-20260827/pipeline-parallel-save-refresh.png`.

Run timing observability received a separate Playwright pass against Vite on `5173` and an isolated backend on `18085`:

- the historical parallel Pipeline run loaded through the authenticated Run Observation page
- the timing endpoint returned the `agent.run.timing.v1` contract without weakening run access control
- legacy events were paired into the `analyze`, `format`, and `join` Pipeline steps
- the page displayed 97.3% explained coverage, 15.3 s model critical path, 23.9 s cumulative model time, 9.81 s parallel savings, and two unexplained gaps totaling 444 ms
- expanding model usage displayed exactly three calls with Agent, model, measured duration, input tokens, output tokens, and cost

The reviewed browser artifact is `output/playwright/orchestration-timing-20260827/timing-panel-final.png`. The isolated `18085` backend and Playwright session were stopped after validation; the existing `8080/5173` services were not modified.

## Parallel current-head real-model result

After adding Pipeline fan-out/barrier-join, the same isolated workspace passed all four real-model examples again:

| Mode | Total |
| --- | ---: |
| SINGLE | 23,988 ms |
| PIPELINE (parallel fan-out + join) | 16,888 ms |
| ROUTER | 16,883 ms |
| SUPERVISOR | 65,379 ms |

The Pipeline persisted `pipeline_parallel_start`, both grouped step completions, and `pipeline_parallel_end`. The two child calls overlapped, and the group duration recorded in the end event was 8,163 ms. The complete Pipeline used three model calls and still finished 30.4% faster than the earlier two-call serial baseline. Its run ID is `run_a58ba5285f6d4675acec3acaef0f0e43`; the full report is in `output/orchestration-current-baseline-20260827-141102/parallel-results/report.json`.

## Repeatable real-model examples

The repository already contains idempotent examples; do not create duplicate demo Agents:

- `scripts/seed-orchestration-e2e-examples.ps1` creates/updates SINGLE children plus one parallel Pipeline, one Router, and one Supervisor demo.
- `scripts/run-orchestration-e2e.ps1` runs all four cases through the public SSE path and verifies persisted LLM decisions, snapshots, budgets, Pipeline parallel start/end markers and joined output, and multi-step Supervisor events.

Run these scripts in an environment with a configured orchestration/QA model and an administrator session token. The current-head result above used a configured real provider, but it is a single acceptance sample rather than a latency benchmark. Router thinking-off behavior is also verified at the generated model-request body and event-contract layers.

## Recovery semantics to retain

- Checkpointed phases execute once after they are committed.
- A committed Pipeline parallel group executes once after recovery; a group interrupted before its barrier checkpoint may be retried as a whole.
- The call that was in flight at process loss may execute again.
- Tools and external systems with side effects must honor the propagated `idempotency_key`.
- Every production instance must use a unique stable orchestration instance ID and share the same transactional run database.
- A terminal run must have no active lease and no checkpoint rows.
