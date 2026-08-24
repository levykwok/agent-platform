# Agent orchestration

This document describes the platform orchestration model consumed by the AgentScope runtime bridge.

## Runtime entry

Agent runs enter `AgentRuntimeService` with an `agent_id` and `ChatRequest`. The runtime loads the published `AgentDefinition`, then dispatches by `orchestration.mode`.

## Modes

### SINGLE

Runs one agent directly. The agent receives its configured model, prompt, tools, MCP servers, skills, memory workspace, and middleware.

### ROUTER

Selects a target agent before execution.

Route fields:

- `ruleId`: stable identifier for observability.
- `targetAgentId`: agent to run when the route is selected.
- `contains`: semantic hint supplied to the routing model.
- `keywords`: additional intent hints supplied to the routing model.
- `defaultRoute`: deterministic fallback used only when the LLM decision fails or is invalid.

The Router makes one stateless, tool-free LLM decision and must return one configured `route_id`. Returned IDs are checked against the route whitelist. If the call times out, fails, or returns invalid output, the first configured default route is used; when no default exists, the first declared route is used. Keyword matching is not the primary runtime path.

### PIPELINE

Runs an Agent-owned fixed sequence of steps. Its canonical mode is `PIPELINE`; the step array remains stored under `AgentDefinition.orchestration.workflow` for data compatibility. It is not the standalone `WorkflowAsset` canvas, does not create a Workflow asset/version, and does not reuse canvas nodes or edges. Each step receives the previous step output as input, optionally prefixed by the step `instruction`. Persisted Agent configurations using the former mode name `WORKFLOW` are loaded as `PIPELINE` and are written back with the canonical name.

Step-level execution policy is optional and backward-compatible:

```yaml
workflow:
  - stepId: research
    agentId: researcher
    instruction: Analyze the request.
    timeoutMs: 120000
    maxRetries: 2
    failurePolicy: FAIL_FAST # FAIL_FAST, SKIP, USE_INPUT
```

- `timeoutMs`: maximum execution time for the step, including streamed final steps.
- `maxRetries`: retry count after a timeout or execution failure.
- `FAIL_FAST`: stop the Pipeline and return the error.
- `SKIP`: mark the step as fallback and continue with the previous input.
- `USE_INPUT`: same fallback data behavior, making the intent explicit for future typed mappings.

Steps can branch to a later step based on the previous step output:

```yaml
workflow:
  - stepId: research
    agentId: researcher
    transitions:
      - when: needs_review
        nextStepId: reviewer
      - nextStepId: writer
        defaultTransition: true
  - stepId: reviewer
    agentId: reviewer
  - stepId: writer
    agentId: writer
```

The first matching `when` substring wins; if none matches, `defaultTransition` wins; if no transition matches, execution falls through to the next list step. The initial implementation only allows forward jumps, so cycles are rejected during configuration validation.

Current limits:

- no parallel fan-out
- no typed input/output mapping
- final step streams to the client; intermediate steps run as blocking calls with summary events

### SUPERVISOR

Runs an adaptive platform-level supervisor flow:

1. A tool-free LLM PLAN call creates an ordered list of binding IDs and delegated instructions.
2. Run the next child Agent with the original request, delegated instruction, and bounded previous results.
3. A tool-free LLM REVISE call chooses `FINISH` or another allowed child call and may replace the remaining plan.
4. Repeat steps 2–3 until finished or `maxSupervisorSteps` is reached.
5. Run the supervisor Agent once to synthesize all child results into the final answer.

`maxSupervisorSteps` defaults to 5 and is capped at 10. Calls are sequential by default because later instructions may depend on earlier results. When `supervisorParallelEnabled` is true, adjacent PLAN steps with the same non-empty `parallel_group` run concurrently up to `maxSupervisorParallelism` and the root runtime concurrency limit. REVISE runs once after the complete group. Steps without a group remain sequential. The same binding may be called more than once. PLAN and REVISE outputs are validated against the configured binding whitelist before execution. Invalid PLAN output falls back to configured order; invalid REVISE output follows the next planned step or finishes.

Each child still receives only the tools explicitly allowed by its binding. Harness-native subagent creation remains disabled for these scoped child calls.

## Nested Agent business result contract

Every model result crossing an Agent boundary is normalized to `agent.result.v1`:

```json
{
  "status": "succeeded | partial | failed",
  "data": {},
  "summary": "short human-readable result",
  "artifacts": [],
  "error": null
}
```

Legacy plain text remains accepted and is normalized to `status=succeeded`, `data.text=<text>`, and the same text as `summary`. A child binding can define `outputSchema`; an Agent can define `model_policy.output_schema`. Both schemas validate the `data` value before it is supplied to the next Agent. The supported JSON Schema subset includes `type`, `required`, `properties`, `items`, `enum`, and `additionalProperties=false`.

## Root task budget

The root task ID is propagated through Router targets, Pipeline steps, Supervisor children, and nested Agents. The following `model_policy.runtime` limits are shared by the complete tree:

- `root_timeout_ms` (default `180000`)
- `root_max_agent_calls` (default `20`)
- `root_max_tokens` (default `100000`)
- `root_max_depth` (default `6`)

Agent and model calls reserve from one thread-safe root budget. Model usage middleware adds returned token counts. Exceeding a limit fails the root task instead of silently falling back to a Router or Supervisor decision.

## Run snapshot and quality metrics

At run creation the persisted run freezes `agent_version`, the configured model, model policy, complete orchestration policy/child bindings, and `config_snapshot`. Historical runs therefore retain the configuration used at execution time even after the published Agent changes.

`GET /platform/frontend/agents/orchestration/metrics` aggregates Router labels/accuracy, Supervisor fallback rate, average child calls, decision and run P95 latency, token usage, and estimated model cost. Router accuracy is only calculated from explicit run labels posted to `.../runs/{runId}/orchestration-evaluation`; unlabeled success is not treated as a correct route.

## Decision model configuration

Router and Supervisor control calls should use a small, low-latency model with reasoning disabled. The runtime resolves models in this order:

1. Agent `router_decision` / `supervisor_decision` / `pipeline_decision` model policy. The former `workflow_decision` key remains readable for compatibility.
2. Agent `orchestration` model policy.
3. Legacy Agent `routing` model policy.
4. Platform `router_decision` / `supervisor_decision` slot.
5. Platform `orchestration` slot.
6. Normal Agent QA/chat model.

Control calls are limited to 512 output tokens, temperature 0, and a maximum 30-second timeout. These settings do not change child-Agent or final synthesis calls.

## Capability assembly

`AgentCapabilityAssembler` applies:

- Java/Python tools from `tool_scope.include`
- MCP servers from `mcp_scope.include`
- MCP per-agent filters from `tool_scope.include` entries shaped like `mcp:<server_id>:<tool_name>`
- Skill repositories from `skill_scope.include`

Server-level MCP filters and agent-level MCP filters are intersected when both are present.

## Boundary with standalone Workflow

The independent Workflow center continues to use `WorkflowAsset`, graph nodes/edges, typed ports, publishing, and versions. Agent `orchestration.mode=PIPELINE` is only a fixed Agent-to-Agent sequence. The name `WORKFLOW` is reserved for the independent canvas asset.
