# Agent orchestration validation

Validation date: 2026-08-26

## Release status

The Agent orchestration implementation is complete for Router, Pipeline, and Supervisor runtime paths. SINGLE remains the direct execution baseline. Agent Pipeline and standalone Workflow are separate products and are not routed through one another.

## Automated coverage

`mvn -o clean test` completed successfully:

- 116 tests run
- 0 failures
- 0 errors
- 0 skipped

The suite includes:

- LLM Router decision parsing, whitelist validation, fallback, and `enable_thinking=false` request injection
- Pipeline ordered execution, branching, timeout/retry/failure policy, typed boundary values, and legacy migration
- Supervisor PLAN/REVISE, repeated and parallel child calls, budgets, tool-scope preservation, timeout/retry/fallback, and thinking policy
- root task depth/call/token/time budgets and nested task contracts
- SQLite lease fencing, stale-owner rejection, durable cancellation, expired-run takeover, and checkpoint cleanup
- process-recovery simulations proving committed Pipeline and Supervisor phases are not repeated
- persisted Router/Supervisor/Pipeline evaluation suites and tenant scoping

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

## Repeatable real-model examples

The repository already contains idempotent examples; do not create duplicate demo Agents:

- `scripts/seed-orchestration-e2e-examples.ps1` creates/updates SINGLE children plus one Pipeline, one Router, and one Supervisor demo.
- `scripts/run-orchestration-e2e.ps1` runs all four cases through the public SSE path and verifies persisted LLM decisions, snapshots, budgets, Pipeline markers, and multi-step Supervisor events.

Run these scripts in an environment with a configured orchestration/QA model and an administrator session token. The isolated browser workspace used for this validation has no provider credentials, so this round does not claim a fresh real-provider latency measurement. The thinking-off behavior is verified at the generated model-request body and event-contract layers, not by inventing a timing comparison.

## Recovery semantics to retain

- Checkpointed phases execute once after they are committed.
- The call that was in flight at process loss may execute again.
- Tools and external systems with side effects must honor the propagated `idempotency_key`.
- Every production instance must use a unique stable orchestration instance ID and share the same transactional run database.
- A terminal run must have no active lease and no checkpoint rows.
