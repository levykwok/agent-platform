---
name: product-review
description: Turn a product request and optional visual context into an actionable review with scope, risks, acceptance criteria, and next actions.
---

# Product Review

Use this skill for a product requirement, feature proposal, PRD excerpt, or UI screenshot that needs an actionable review. Do not merely restate the request.

## Inputs

- Requirement text, business goal, users, and constraints.
- Optional UI screenshot: use the visual context already provided by the platform. Do not invent details that are not visible.
- Optional current flow, metrics, target date, and dependencies.

## Procedure

1. Separate facts, assumptions, and missing information.
2. Identify users, scenario, trigger, happy path, exceptions, and success criteria.
3. Evaluate scope, experience, data, technology, dependency, compliance, and launch risks.
4. First run `python3 <files-root>/scripts/validate_brief.py --example` as a deterministic smoke check. For a real brief, save JSON through the file tool and run `python3 <files-root>/scripts/validate_brief.py --file <brief-file>`. If the script cannot run, perform the same checks manually and say so.
5. Produce the fixed output format below. Every P0/P1/P2 risk needs a reason and mitigation.

## Output format

### Requirement summary
### UI or context observations
### Recommended scope
### Risks and dependencies
| Priority | Risk or dependency | Reason | Mitigation |
### Acceptance criteria
### Open questions
### Recommended next actions

## Quality bar

- Never present an assumption as a fact.
- Provide at least three independently testable acceptance criteria, or explain why they cannot be verified.
- The result must let product, engineering, and QA continue working without another interpretation pass.