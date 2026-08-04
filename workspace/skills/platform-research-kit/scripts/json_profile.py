#!/usr/bin/env python3
import argparse
import json
from collections import Counter


def type_name(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int) and not isinstance(value, bool):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


def walk(value, counter):
    counter[type_name(value)] += 1
    if isinstance(value, dict):
        for child in value.values():
            walk(child, counter)
    elif isinstance(value, list):
        for child in value:
            walk(child, counter)


def main():
    parser = argparse.ArgumentParser(description="Profile a JSON document.")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--json", help="JSON document string")
    source.add_argument("--file", help="Path to JSON file")
    args = parser.parse_args()

    raw = args.json
    if args.file:
        with open(args.file, "r", encoding="utf-8") as handle:
            raw = handle.read()

    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(json.dumps({
            "ok": False,
            "error": "invalid_json",
            "message": str(exc),
            "line": exc.lineno,
            "column": exc.colno,
        }, ensure_ascii=False, indent=2))
        return 1

    counter = Counter()
    walk(data, counter)
    result = {
        "ok": True,
        "root_type": type_name(data),
        "top_level_keys": list(data.keys()) if isinstance(data, dict) else [],
        "top_level_field_types": {key: type_name(value) for key, value in data.items()} if isinstance(data, dict) else {},
        "array_length": len(data) if isinstance(data, list) else None,
        "node_type_counts": dict(counter),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
