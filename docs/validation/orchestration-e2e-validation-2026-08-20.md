# Orchestration E2E Validation — 2026-08-20

## Scope

This validation uses a dedicated, reproducible agent set. It does not modify the existing researcher/writer examples.

| Mode | Agent | Topology |
|---|---|---|
| SINGLE | `orchestration-e2e-single-analysis` | One analysis agent |
| SINGLE | `orchestration-e2e-single-format` | One formatting agent |
| WORKFLOW | `orchestration-e2e-workflow` | `analyze` -> `format` |
| ROUTER | `orchestration-e2e-router` | `serial-workflow` -> workflow; `single-analysis` -> analysis; default -> format |
| SUPERVISOR | `orchestration-e2e-supervisor` | Parallel `analysis-child` and `format-child`, followed by supervisor synthesis |

The definitions are idempotently created by `scripts/seed-orchestration-e2e-examples.ps1`. They are public, published platform assets and use the platform QA model slot instead of hard-coding a provider model.

## Reproduce

Use an authenticated platform session token. The scripts call the backend directly because they exercise the SSE runtime API rather than the Vite development proxy.

```powershell
$env:AGENT_PLATFORM_E2E_SESSION_TOKEN = '<session-token>'
./scripts/seed-orchestration-e2e-examples.ps1
./scripts/run-orchestration-e2e.ps1
```

The runner writes machine-readable and Markdown reports to:

- `output/orchestration-e2e/report.json`
- `output/orchestration-e2e/report.md`

## Real runtime results

All four runs completed successfully against `http://localhost:8080`.

| Mode | Run | Total | First text | Result marker |
|---|---|---:|---:|---|
| SINGLE | `run_7b266f65c9f043e6b014cabf16a44140` | 6,981 ms | 5,662 ms | `E2E_ANALYSIS_OK` |
| WORKFLOW | `run_572f2351c3b24ce28518ee7de6a087e1` | 10,587 ms | 10,168 ms | `E2E_FORMAT_OK` |
| ROUTER | `run_27ac872cbd3b48579f9d5128f4b2c8a5` | 25,565 ms | 17,106 ms | `E2E_FORMAT_OK` |
| SUPERVISOR | `run_0d06ec698fb74cdead67fbf702f26ac1` | 23,252 ms | 23,043 ms | `E2E_SUPERVISOR_OK` |

### Layer breakdown

- SINGLE: runtime preparation completed in about 0.8 s; model generation occupied the remaining time.
- WORKFLOW: `analyze` ran from 0.303 s to 5.341 s; `format` ran from 5.358 s to 10.586 s. The two model calls account for almost the entire wall time.
- ROUTER: route selection completed at 0.181 s and workflow execution started at 0.201 s. `analyze` completed at 5.817 s. The formatting model produced its first text at 17.106 s. This sample then had about 6.3 s between the final child `agent_end` and the terminal `done` event, which remains visible in the generated event-level report and should be monitored.
- SUPERVISOR: both children were selected by 0.382 s and returned at 9.027 s and 9.298 s, confirming parallel child execution. Supervisor synthesis began at 9.585 s and produced its first text at 23.043 s.

The detailed report records every SSE event with both relative time and the gap from its predecessor, together with the persisted run, steps, and events returned by the observability APIs.

## Playwright UI validation

The validation used the real browser UI at `http://localhost:5173`.

- Agent Management displayed all five dedicated agents and their correct orchestration topology.
- Quick Run succeeded for all four modes and displayed the expected result markers.
- Runs & Observability displayed the persisted runs, step list, answer, and event timeline.
- SINGLE showed `single_agent_start`, model-call events, `agent_end`, and `run.succeeded`.
- WORKFLOW showed `workflow_start`, both workflow step transitions, and `run.succeeded`.
- ROUTER showed `router_decision`, the delegated workflow and its steps, and `run.succeeded`.
- SUPERVISOR showed two child selections, two child results, supervisor synthesis model events, and `run.succeeded`.
- The browser console reported zero errors after the authenticated validation flow.

Local visual evidence is written to:

- `output/playwright/orchestration-e2e-router-trace.png`
- `output/playwright/orchestration-e2e-supervisor-trace.png`

## Findings

1. The four orchestration modes are functional and independently demonstrable. ROUTER delegates to both a single agent and a workflow by configuration; the validated serial route entered the workflow.
2. The apparent orchestration delay is predominantly model latency. Routing and child selection are sub-second in these samples. The ROUTER terminal-event gap is the one material runtime overhead requiring follow-up measurements.
3. The Vite development proxy returned an empty HTTP 500 for PowerShell/.NET SSE clients during diagnostics, while direct backend SSE calls succeeded. Normal browser Quick Run requests through Vite succeeded. The automation therefore defaults to port 8080.
4. The Supervisor detail page exposes eight built-in schedule tools even though this test definition declares no tools. No schedule tool was invoked in these runs, but the automatic tool exposure should remain part of tool-governance review.
