#!/bin/bash
# Puts the Downloader code into every page that shows it (between the <!--dl-code--> markers).
# Usage: tools/set-downloader-code.sh 12345
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CODE="${1:?usage: tools/set-downloader-code.sh <code>}"
[[ "$CODE" =~ ^[0-9]{3,10}$ ]] || { echo "A Downloader code is digits only (got: $CODE)"; exit 1; }
n=0
for f in "$ROOT"/*.md; do
  if grep -q '<!--dl-code-->' "$f"; then
    sed -i -E "s#<!--dl-code-->[^<]*<!--/dl-code-->#<!--dl-code-->$CODE<!--/dl-code-->#g" "$f"
    echo "  $(basename "$f")"; n=$((n + 1))
  fi
done
echo "Downloader code $CODE set in $n file(s). Commit and push to publish it."
