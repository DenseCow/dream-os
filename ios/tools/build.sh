#!/bin/bash
# Compile check in WSL. Usage (from Windows: wsl -e bash -lc '...'):
#   build.sh <worker> [target]   copies app/ to ~/dreamos-ios/<worker>/ and builds one module (default: whole app)
# Each worker uses its own <worker> name so builds never share a .build folder.
# Source of truth stays in app/; never edit the WSL copy.
set -o pipefail
worker="${1:?worker name}"; target="${2:-}"
src="$(cd "$(dirname "$0")/.." && pwd)/app/"
dst="$HOME/dreamos-ios/$worker"
mkdir -p "$dst"
rsync -a --delete --exclude .build --exclude .swiftpm --exclude xtool "$src" "$dst/"
cd "$dst"
if [ -n "$target" ]; then
  swift build --build-system native --swift-sdk darwin --triple arm64-apple-ios --target "$target" 2>&1 \
    | grep -vE "deprecated and will be removed|^\[[0-9]+/[0-9]+\] (Compiling|Emitting|Write|Planning)" | tail -60
else
  xtool dev build 2>&1 | grep -vE "^\[[0-9]+/[0-9]+\]" | tail -60
  # MPVKit's static frameworks out of the .app (xtool would try to sign them; see strip-static.sh).
  [ -d "$dst/xtool/DreamOS.app" ] && bash "$(dirname "$0")/strip-static.sh" "$dst/xtool/DreamOS.app"
fi
