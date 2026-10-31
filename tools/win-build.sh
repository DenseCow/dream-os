#!/bin/bash
# Builds the Windows setup for a release (run by tools/release.sh with WIN=1; can run on its own after tools/sync.sh):
#
#   tools/win-build.sh <scratch folder> <version>
#
# Builds from the exported, scrubbed windows/ folder (never the Windows repo's own checkout), copied to
# <scratch folder>/windows so downloads and build output stay out of this repository. windows/tools/build-installer.sh
# does the work: libmpv and Python through its pinned, checksum-checked fetch scripts, the app image on the PUBLIC
# channel, SelfCheck with no window, then Inno Setup. tools/iscc-scan.sh stands in for ISCC.exe and privacy-scans the
# app image before anything is packed; the setup .exe is scanned after. Output in <scratch folder>/out:
# DreamOS-Windows-Setup.exe and DreamOS-Windows-Setup.exe.sha256.
#
# No keys: the export has no local.properties, and the only build step that reads a key (the startup archive's
# training launch, app/build.gradle.kts startupArchive) also takes DREAM_TMDB_KEY from the environment, which is
# removed here. Without a key that launch stops at the welcome page, so the class list covers fewer classes.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
WORK="${1:?usage: tools/win-build.sh <scratch folder> <version>}"
VERSION="${2:?usage: tools/win-build.sh <scratch folder> <version>}"
PY="${PYTHON:-python}"
case "$WORK" in */dream-release) ;; *) echo "win-build: the scratch folder must end in /dream-release"; exit 1 ;; esac

WIN_VERSION=$(sed -n 's/^extra\["dreamVersion"\] = "\([0-9.]*\)".*/\1/p' "$ROOT/windows/build.gradle.kts")
[ "$WIN_VERSION" = "$VERSION" ] || { echo "Windows dreamVersion is ${WIN_VERSION:-?}, not $VERSION"; exit 1; }

WINB="$WORK/windows"
rm -rf "$WINB"
mkdir -p "$WINB" "$WORK/out"
cp -r "$ROOT/windows/." "$WINB/"
[ ! -e "$WINB/local.properties" ] || { echo "windows/ has a local.properties; it must not"; exit 1; }
unset DREAM_TMDB_KEY

REAL_ISCC="${ISCC:-}"
if [ -z "$REAL_ISCC" ]; then
  for c in "${LOCALAPPDATA:-}/Programs/Inno Setup 6/ISCC.exe" "/c/Program Files (x86)/Inno Setup 6/ISCC.exe" \
           "/c/Program Files/Inno Setup 6/ISCC.exe"; do
    [ -f "$c" ] && { REAL_ISCC="$c"; break; }
  done
fi
[ -n "$REAL_ISCC" ] && [ -f "$REAL_ISCC" ] \
  || { echo "Inno Setup 6 not found (winget install --id JRSoftware.InnoSetup -e, or set ISCC)"; exit 1; }

LOG="$WORK/windows.log"
status=0
(cd "$WINB" && DREAM_REAL_ISCC="$REAL_ISCC" ISCC="$ROOT/tools/iscc-scan.sh" bash tools/build-installer.sh) \
  > "$LOG" 2>&1 || status=$?
# The log without scan hits and curl's progress meter.
grep -v -E '^  HIT |^Downloading|^Fetching|% Total|Dload|^ +[0-9]+ +[0-9.]+[kMG]? +[0-9]+ ' "$LOG" | sed 's/^/  /' | tail -30
if [ "$status" -ne 0 ]; then
  grep '^  HIT ' "$LOG" | head -60
  echo "Windows build failed (log: $LOG)"; exit 1
fi
# Keyless: no local.properties anywhere in the build folder (the training launch and the app look for one there).
[ -z "$(find "$WINB" -name local.properties -print -quit)" ] || { echo "A local.properties appeared in $WINB"; exit 1; }
IMAGE="$WINB/app/build/compose/binaries/main/app/Dream OS"
if [ -f "$IMAGE/app/dream.classlist" ]; then
  echo "  Startup archive: class list of $(grep -vc '^#' "$IMAGE/app/dream.classlist") classes (trained with no TMDB key)"
else
  echo "  Startup archive: no class list (the training launch failed; the app starts without it)"
fi
[ -f "$IMAGE/app/dream.jsa" ] && echo "  Startup archive: Java part $(( $(stat -c %s "$IMAGE/app/dream.jsa") / 1048576 )) MB"

SETUP="$WINB/app/build/release/DreamOS-Windows-Setup.exe"
[ -f "$SETUP" ] && [ -f "$SETUP.sha256" ] || { echo "No DreamOS-Windows-Setup.exe in $WINB/app/build/release"; exit 1; }
"$PY" -I "$ROOT/tools/privacy_scan.py" apk "$SETUP"
cp "$SETUP" "$SETUP.sha256" "$WORK/out/"
(cd "$WORK/out" && sha256sum -c --status DreamOS-Windows-Setup.exe.sha256) || { echo "Setup checksum file doesn't match"; exit 1; }
