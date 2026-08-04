#!/usr/bin/env python3
"""Validate a release brief before it is circulated."""
import argparse
import json
from pathlib import Path

REQUIRED = ("release_goal", "scope", "acceptance", "risks", "rollback")
EXAMPLE = {
    "release_goal": "Ship the review workflow",
    "scope": "Product review",
    "acceptance": "QA verifies the output",
    "risks": "P1 integration",
    "rollback": "Disable the workflow",
}

def main():
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--json", help="Release brief JSON")
    source.add_argument("--file", help="Path to a release brief JSON file")
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
    missing = [key for key in REQUIRED if not brief.get(key)]
    print(json.dumps({"ok": not missing, "missing_fields": missing, "release_ready": not missing}, ensure_ascii=False, indent=2))
    return 0 if not missing else 2

if __name__ == "__main__":
    raise SystemExit(main())