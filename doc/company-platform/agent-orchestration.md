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
- `contains`: exact substring match against the user message.
- `keywords`: additional keyword list. A route matches when any keyword appears in the user message.
- `defaultRoute`: fallback route used when no non-default route matches.

Routes are evaluated in order. Non-default routes are checked first; the first match wins. If none match, the first default route wins. If there is no default route, the router agent runs itself.

### WORKFLOW

Runs a linear sequence of agent steps. Each step receives the previous step output as input, optionally prefixed by the step `instruction`.

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
- `FAIL_FAST`: stop the workflow and return the error.
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

Runs a platform-level supervisor flow:

1. Select a subagent from `orchestration.subagents`.
2. Run the selected subagent with the original user request plus binding role/scope.
3. Run the supervisor agent with the user request and subagent result, producing the final answer.

Selection is deterministic. With one subagent, that subagent is selected. With multiple subagents, the runtime scores the user message against each binding id, role, description, target agent id, and target agent name. If there is no positive match, the supervisor runs alone.

The harness also receives `SubagentDeclaration` entries, so model-native subagent usage can evolve independently from this platform-level dispatch.

## Capability assembly

`AgentCapabilityAssembler` applies:

- Java/Python tools from `tool_scope.include`
- MCP servers from `mcp_scope.include`
- MCP per-agent filters from `tool_scope.include` entries shaped like `mcp:<server_id>:<tool_name>`
- Skill repositories from `skill_scope.include`

Server-level MCP filters and agent-level MCP filters are intersected when both are present.

## Next steps

- ROUTER: add LLM classification as an optional route policy.
- SUPERVISOR: support multi-subagent fan-out and explicit user-visible delegation traces.
- WORKFLOW: add conditional edges, failure policy, and step input/output mappings.
