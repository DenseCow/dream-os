#!/bin/bash
# Live timing check of the trailer look-up's direct path, on Linux (WSL): compiles TrailerDirect.swift with
# tools/trailer-check/main.swift and runs it. Usage: trailer-check.sh [video keys...] (default: three trailers).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp -d)"
trap 'rm -f "$out/trailer-check"; rmdir "$out"' EXIT
swiftc -O -o "$out/trailer-check" \
  "$here/../app/Sources/DreamPlayer/Session/TrailerDirect.swift" "$here/trailer-check/main.swift" 2>&1 | grep -v "^$" || true
"$out/trailer-check" "$@"
