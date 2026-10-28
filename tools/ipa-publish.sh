#!/bin/bash
# Builds the unsigned Dream.ipa for a release, inside WSL / Linux (called by tools/release.sh).
# Builds in a fixed folder outside the home folder, so no user name lands in the app's debug paths.
# Usage: ipa-publish.sh <repo>/ios <output folder>
set -euo pipefail
SRC="${1:?ios folder}"; OUT="${2:?output folder}"
[ -f "$SRC/app/Package.swift" ] && [ -f "$SRC/app/Sources/DreamCore/Secrets.swift" ] || { echo "ipa: $SRC is not a prepared ios/ folder"; exit 1; }
[ -d "$OUT" ] || { echo "ipa: no output folder $OUT"; exit 1; }
dst=/tmp/dream-publish
mkdir -p "$dst"
rsync -a --delete --exclude .build --exclude .swiftpm --exclude xtool "$SRC/app/" "$dst/"
cd "$dst"
set +e
xtool dev build 2>&1 | tr '\r' '\n' | grep -vE "^\[[0-9]+/[0-9]+\]" | tail -8
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ] || { echo "ipa: build failed"; exit 1; }
app="$dst/xtool/DreamOS.app"
bash "$SRC/tools/strip-static.sh" "$app"
stage=$(mktemp -d); mkdir -p "$stage/Payload"; cp -R "$app" "$stage/Payload/"
(cd "$stage" && rm -f "$OUT/Dream-raw.ipa" && zip -qry "$OUT/Dream-raw.ipa" Payload)
rm -rf "$stage"
ls -la "$OUT/Dream-raw.ipa"
