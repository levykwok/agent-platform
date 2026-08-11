#!/usr/bin/env bash
# Thin wrapper around ./start.sh --stop
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$ROOT/start.sh" --stop "$@"
