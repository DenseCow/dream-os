#!/bin/bash
# Builds Dream as an unsigned .ipa for SideStore (SideStore signs it with your Apple ID and re-signs it every 7
# days). Output: builds/Dream.ipa (or $OUT/Dream.ipa).
set -o pipefail
src="$(cd "$(dirname "$0")/.." && pwd)/app/"
tools="$(cd "$(dirname "$0")" && pwd)"
out="${OUT:-$(cd "$(dirname "$0")/.." && pwd)/builds}"
dst="$HOME/dreamos-ios/${BUILD_NAME:-ipa}"
mkdir -p "$dst" "$out"
rsync -a --delete --exclude .build --exclude .swiftpm --exclude xtool "$src" "$dst/"
cd "$dst" || exit 1
# Debug build: the release build's strip step can't handle the Python .so files on Linux.
xtool dev build 2>&1 | tr '\r' '\n' | grep -vE "^\[[0-9]+/[0-9]+\]" | tail -5
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "ipa.sh: build failed"; exit 1; }
app="$dst/xtool/DreamOS.app"
bash "$tools/strip-static.sh" "$app"
stage=$(mktemp -d); mkdir -p "$stage/Payload"; cp -R "$app" "$stage/Payload/"
(cd "$stage" && rm -f "$out/Dream.ipa" && zip -qry "$out/Dream.ipa" Payload)
rm -rf "$stage"
ls -la "$out/Dream.ipa"
