#!/usr/bin/env python3
import argparse
import json
import re
from collections import Counter


STOPWORDS = {
    "the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
    "into", "about", "when", "then", "than", "have", "has", "agent", "agents",
}


def load_text(args):
    if args.file:
        with open(args.file, "r", encoding="utf-8") as handle:
            return handle.read()
    return args.text or ""


def main():
    parser = argparse.ArgumentParser(description="Digest text into metrics and bullets.")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--text", help="Inline text")
    source.add_argument("--file", help="Text file path")
    args = parser.parse_args()

    text = load_text(args).strip()
    words = re.findall(r"[A-Za-z0-9_\u4e00-\u9fff]+", text.lower())
    keyword_counts = Counter(w for w in words if len(w) > 1 and w not in STOPWORDS)
    sentences = [s.strip() for s in re.split(r"(?<=[.!?。！？])\s+", text) if s.strip()]
    bullets = sentences[:5] if sentences else [text[:160]] if text else []
    result = {
        "ok": True,
        "chars": len(text),
        "words": len(words),
        "lines": len(text.splitlines()) if text else 0,
        "top_keywords": keyword_counts.most_common(8),
        "digest_bullets": bullets,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
