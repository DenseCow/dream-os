#!/bin/bash
# Builds the whole app and installs it on the iPhone.
# The phone is reached through Windows' Apple Devices service (BUILDING.md), never usbipd.
# Not `xtool dev run`: that packs MPVKit's static frameworks into the .app and its signer fails on them.
# Instead: build, strip the static frameworks (tools/strip-static.sh), then `xtool install` the .app
# (the same signing + install path `dev run` uses).
set -o pipefail
export USBMUXD_SOCKET_ADDRESS="${USBMUXD_SOCKET_ADDRESS:-$(ip route list default | awk '{print $3}'):27015}"
tools="$(cd "$(dirname "$0")" && pwd)"
src="$(cd "$(dirname "$0")/.." && pwd)/app/"
dst="$HOME/dreamos-ios/main"
mkdir -p "$dst"
rsync -a --delete --exclude .build --exclude .swiftpm --exclude xtool "$src" "$dst/"
cd "$dst" || exit 1
xtool dev build 2>&1 | tr '\r' '\n' | grep -vE "^\[[0-9]+/[0-9]+\]" | tail -20
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "install.sh: build failed"; exit 1; }
app="$dst/xtool/DreamOS.app"
bash "$tools/strip-static.sh" "$app"
xtool install "$app" 2>&1 | tr '\r' '\n' | grep -vE "^\[[0-9]+/[0-9]+\]|^\[(Verifying|Connecting|Installing|Signing|Provisioning|Packaging|Unpacking|Preparing)" | tail -40
