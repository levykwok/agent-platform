# Agent orchestration parallel freeze validation — 2026-09-02

## Verdict

The pure Agent-to-Agent orchestration line is accepted for feature freeze. Agent-owned Pipeline and LLM-planned Supervisor both have a platform-owned public parallel demo, persisted UI configuration, real-model execution evidence, explicit parallel events, timing analysis, and automated regression coverage.

This verdict does not merge Pipeline with the standalone Workflow canvas: Pipeline remains a fixed Agent-owned sequence; Workflow remains an independent canvas asset.

## Public demos

The idempotent seed entry point is `scripts/seed-orchestration-e2e-examples.ps1`.

| Demo | Agent ID | Public configuration |
| --- | --- | --- |
| Parallel Pipeline | `orchestration-e2e-workflow` | `PIPELINE`; `analyze` + `format` use `acceptance-fanout`; serial `join`; maximum parallelism 2 |
| Parallel Supervisor | `orchestration-e2e-supervisor` | `SUPERVISOR`; two child bindings; PLAN parallel groups enabled; maximum parallelism 2; maximum steps 5; PLAN/REVISE thinking disabled |

The supporting Analysis, Format, and Join Agents are public platform fixtures. Seeding/upgrading the live SQLite catalog produced six enabled, published, `PUBLIC` / `SYSTEM` Agent records. The pre-seed database is retained at `output/orchestration-freeze-20260902/platform-before-demo-seed.db` for recovery.

## Real-model evidence

### Patched isolated acceptance environment

Both calls were initiated from the Agent Management quick-call UI through Playwright and persisted in the cloned SQLite database.

| Mode | Run ID | Result | End-to-end | Model critical path | Model cumulative | Parallel savings |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| Pipeline | `run_5c4826c8bdd446a79eed7d673b21cb2e` | `E2E_PIPELINE_PARALLEL_OK analyze=succeeded format=succeeded` | 17.2 s | 16.2 s | 28.1 s | 11.8 s |
| Supervisor | `run_7efd0c0cf62945648335fc05781a701e` | `E2E_SUPERVISOR_OK \| analysis=true \| format=true` | 23.7 s | 14.0 s | 22.2 s | 8.30 s |

Pipeline persisted `pipeline_parallel_start` at 12:33:46 and `pipeline_parallel_end` at 12:33:58. Supervisor persisted `supervisor_parallel_start` and `supervisor_parallel_end`; both child model calls started at 12:31:27 and completed around 12:31:35–36, proving overlap rather than serial execution. Supervisor timing coverage was 91.8%; its remaining unexplained time was 1.96 s across three gaps of at least 50 ms.

### Live pre-patch comparison

The production-like live service had already proved actual concurrency before the Supervisor timing-event patch:

- Pipeline `run_7493b90559e54eb8ae281fbe1fe7197c`: succeeded in 23.034 s with 13.893 s parallel savings.
- Supervisor `run_f931eb2a985843338e3250a380333f3d`: succeeded in 16.787 s. Its child model calls began 179 ms apart and both ran for about 5.8 s before either completed. The former 0 ms parallel-savings value was an observability defect, not serial execution; the new Supervisor parallel event pair fixes it.

## Playwright acceptance

Playwright ran against isolated ports 15183/19080 and a cloned database; the existing services on 5173/8080 stayed running.

Verified flows:

1. Platform administrator login and public Agent catalog loading.
2. Pipeline overview and editor: mode, two grouped fan-out steps, serial join, maximum parallelism 2, save, and persisted overview.
3. Supervisor overview and editor: two child bindings, PLAN parallel-group toggle, maximum parallelism 2, maximum steps 5, thinking disabled, save, and persisted overview.
4. Real-model quick calls for both demos with successful acceptance outputs.
5. Run Observation detail, explicit parallel start/end events, overlapping timeline phases, and non-zero parallel savings.
6. Browser console review: zero error-level messages.

Evidence:

- `output/playwright/orchestration-freeze/pipeline-public-demo.png`
- `output/playwright/orchestration-freeze/supervisor-public-demo.png`
- `output/playwright/orchestration-freeze/supervisor-parallel-timing.png`

The isolated workspace and cloned SQLite database are retained under `output/orchestration-freeze-20260902/isolated-workspace`; isolated processes were stopped and `.run/platform-pids.json` was restored to the original 8080/5173 and MCP launchers.

## Code and automated coverage

The runtime now emits Supervisor parallel-group boundaries before child starts and after all grouped child results. Run timing recognizes these events as a parallel group, and both compatibility endpoints expose localized event labels. Event assembly was also changed to avoid blocking `toIterable()` calls on Reactor parallel threads.

Focused orchestration regression passed 28/28 tests across `NestedOrchestrationTest` and `RunTimingAnalyzerTest`. The full Maven suite passed 143/143 tests with zero failures, errors, or skips. The frontend production command (`vue-tsc --noEmit --skipLibCheck && vite build`) also passed. Vite reported only its existing advisory that the main minified chunk is larger than 500 kB; this is a performance optimization item, not an orchestration acceptance failure. `git diff --check` passed.

## Freeze boundary

Included in this freeze:

- SINGLE, LLM Router, Agent-owned Pipeline, and multi-step LLM Supervisor semantics.
- Explicit Pipeline and Supervisor parallelism with bounded concurrency and barrier join.
- Typed nested Agent results, root budgets, persistence/recovery, UI configuration, public demos, and run timing observability.

Deferred beyond this freeze:

- Arbitrary DAG execution or nested parallel groups inside Agent Pipeline.
- Speculative Supervisor execution without an explicit PLAN `parallel_group`.
- Changing the independent Workflow canvas into Agent Pipeline.
