---
name: release-brief
description: Turn a reviewed product proposal into a cross-functional release brief with scope, gates, risks, owners, launch, and rollback actions.
---

# Release Brief

Use this skill after a requirement review, research note, or solution proposal needs to become a concise and actionable release brief.

## Inputs

- Upstream review: scope, risks, dependencies, acceptance criteria, and open questions.
- Optional release date, version, owner, and rollout audience.

## Procedure

1. Extract confirmed facts; do not turn an open question into a launch commitment.
2. Follow `templates/release-brief.md` in the given order.
3. First run `python3 <files-root>/scripts/validate_release_brief.py --example` as a deterministic smoke check. For a real brief, save JSON through the file tool and run `python3 <files-root>/scripts/validate_release_brief.py --file <brief-file>`. If it cannot run, apply the same release gates manually and say so.
4. Mark unsatisfied launch conditions as `BLOCKED` or `PRE-LAUNCH CONFIRMATION`.

## Output requirements

- Write for product, engineering, QA, and operations. State the decision first, then execution actions.
- Each risk must have an owner or a concrete action. Use `Owner: TBD` when unknown.
- Output Markdown by default. Use the docx capability only when the user asks for a Word document.