import json
import sys

def run(args, context):
    text = args.get("text", "")
    return {"ok": True, "result": {"echo": text, "tool_id": context.get("tool_id")}}

payload = json.loads(sys.stdin.readline() or "{}")
print(json.dumps(run(payload.get("args", {}), payload.get("context", {})), ensure_ascii=False))