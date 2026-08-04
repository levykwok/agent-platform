---
name: platform-research-kit
description: Research workflow skill with executable scripts for JSON profiling, text digest, and arithmetic checks.
---

# Platform Research Kit

Use this skill when the user asks to validate structured data, inspect a text note, summarize research material, or prove that skill scripts can be loaded and executed.

This skill is intentionally script-backed. Do not only describe the skill. When the user asks to "test skill", "call skill", "run research kit", "analyze json", "digest text", or "calculate/check numbers", use the relevant script below and report the command and result.

## Available scripts

### 1. JSON profile

Purpose: validate JSON, list top-level fields, infer field types, and count nested structures.

Command:

```bash
python workspace/skills/platform-research-kit/scripts/json_profile.py --json '{"library":"g6","sample":30}'
```

For file input:

```bash
python workspace/skills/platform-research-kit/scripts/json_profile.py --file workspace/skills/platform-research-kit/examples/sample-config.json
```

### 2. Text digest

Purpose: produce text metrics, top keywords, and a short bullet digest.

Command:

```bash
python workspace/skills/platform-research-kit/scripts/text_digest.py --file workspace/skills/platform-research-kit/examples/research-note.md
```

For inline text:

```bash
python workspace/skills/platform-research-kit/scripts/text_digest.py --text "AgentScope integrates model, tool, memory, and runtime capabilities."
```

### 3. Calculation check

Purpose: calculate one or more arithmetic expressions with a safe AST evaluator.

Command:

```bash
python workspace/skills/platform-research-kit/scripts/calc_check.py "1294*239" "12+23"
```

## Recommended workflow

1. Identify the user intent: JSON profile, text digest, calculation, or full demo.
2. Load this skill's instructions if needed.
3. Run the relevant script with the execute/shell tool.
4. Return a concise result with:
   - skill name: `platform-research-kit`
   - script used
   - command arguments
   - parsed result
   - any validation warnings

## Full demo command

If the user asks for a full skill demo, run:

```bash
python workspace/skills/platform-research-kit/scripts/json_profile.py --file workspace/skills/platform-research-kit/examples/sample-config.json
python workspace/skills/platform-research-kit/scripts/text_digest.py --file workspace/skills/platform-research-kit/examples/research-note.md
python workspace/skills/platform-research-kit/scripts/calc_check.py "1294*239" "30/5"
```

Then summarize all three outputs.
