#!/usr/bin/env python3
"""Validate the minimum evidence needed for a product review."""
import argparse
import json
from pathlib import Path

REQUIRED = ("goal", "users", "scope", "acceptance_criteria")
EXAMPLE = {
    "goal": "Reduce review handoff time",
    "users": "Product and engineering",
    "scope": "Model management filters",
    "acceptance_criteria": "Given a filter, show matching models",
}

def main():
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--json", help="Product brief JSON")
    source.add_argument("--file", help="Path to a product brief JSON file")
    source.add_argument("--example", action="store_true", help="Validate a built-in example")
    args = parser.parse_args()
    raw = json.dumps(EXAMPLE) if args.example else args.json
    if args.file:
        raw = Path(args.file).read_text(encoding="utf-8")
    try:
        brief = json.loads(raw)
    except (OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"ok": False, "error": "invalid_json", "message": str(exc)}, ensure_ascii=False))
        return 1
    missing = [key for key in REQUIRED if not str(brief.get(key, "")).strip()]
    result = {"ok": not missing, "required_fields": list(REQUIRED), "missing_fields": missing, "review_ready": not missing}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not missing else 2

if __name__ == "__main__":
    raise SystemExit(main())