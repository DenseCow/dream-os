#!/usr/bin/env bash
# Builds the Windows setup in app/build/release/:
#   DreamOS-Windows-Setup.exe          the setup wizard (Inno Setup 6, installer/DreamOS.iss), also what the in-app
#                                      updater downloads and runs silently
#   DreamOS-Windows-Setup.exe.sha256   "<sha256>  DreamOS-Windows-Setup.exe", the format UpdateCheck reads
# Steps: libmpv + Python (tools/fetch-third-party.sh), the app image (jpackage through :app:createDistributable),
# SelfCheck of a scratch copy of that image with no window (fails the build if any check fails), then ISCC.
#
# Usage: tools/build-installer.sh                 release build (About shows PUBLIC)
#        tools/build-installer.sh --dev           test build (About shows DEV)
#        tools/build-installer.sh --test <dir>    a setup for testing only (DreamOS-Windows-Setup-Test.exe): its own
#                                                 AppId, shortcuts and "settings" inside <dir>, and it looks for the
#                                                 old MSI under $TEST_MSI_UPGRADE_CODE instead of the real one
# Inno Setup 6.7+: ISCC.exe from $ISCC, else the usual install folders (winget install JRSoftware.InnoSetup).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
channel=PUBLIC
test_dir=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dev) channel=DEV ;;
    --test) test_dir="${2:?--test needs a folder}"; channel=DEV; shift ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

iscc="${ISCC:-}"
if [[ -z "$iscc" ]]; then
  for c in "${LOCALAPPDATA:-}/Programs/Inno Setup 6/ISCC.exe" "/c/Program Files (x86)/Inno Setup 6/ISCC.exe" \
           "/c/Program Files/Inno Setup 6/ISCC.exe"; do
    [[ -f "$c" ]] && { iscc="$c"; break; }
  done
fi
[[ -n "$iscc" && -f "$iscc" ]] || { echo "Inno Setup 6 not found. Install it (winget install --id JRSoftware.InnoSetup -e) or set ISCC." >&2; exit 1; }

version=$(sed -n 's/^extra\["dreamVersion"\] = "\([0-9.]*\)".*/\1/p' "$root/build.gradle.kts")
[[ -n "$version" ]] || { echo "No dreamVersion in build.gradle.kts" >&2; exit 1; }

echo "== Third-party binaries"
bash "$root/tools/fetch-third-party.sh"

echo "== App image $version ($channel)"
(cd "$root" && ./gradlew :app:createDistributable "-Pdream.channel=$channel" --console=plain -q)
image="$root/app/build/compose/binaries/main/app/Dream OS"
[[ -f "$image/Dream OS.exe" && -d "$image/runtime" ]] || { echo "No app image in $image" >&2; exit 1; }

echo "== SelfCheck of the app image"
# On a copy: the check runs the bundled Python, which writes __pycache__ folders that mustn't end up in the setup.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -r "$image" "$work/image"
report="$work/selfcheck.txt"
status=0
"$work/image/Dream OS.exe" --selfcheck "$(cygpath -w "$report")" > /dev/null 2>&1 || status=$?
if [[ -f "$report" ]]; then sed 's/^/  /' "$report"; else echo "  SelfCheck wrote no report (exit $status)"; fi
[[ $status -eq 0 ]] || { echo "SelfCheck failed" >&2; exit 1; }
echo "  Installed size: $(du -sh "$image" | cut -f1)"

echo "== Setup (Inno Setup)"
out="$root/app/build/release"
rm -rf "$out"
mkdir -p "$out"
defs=("/DAppVersion=$version" "/DAppImage=$(cygpath -w "$image")" "/DOutputDir=$(cygpath -w "$out")")
name=DreamOS-Windows-Setup.exe
if [[ -n "$test_dir" ]]; then
  defs+=("/DTestBuild=$(cygpath -w "$test_dir")")
  [[ -n "${TEST_MSI_UPGRADE_CODE:-}" ]] && defs+=("/DOldMsiUpgradeCode=$TEST_MSI_UPGRADE_CODE")
  name=DreamOS-Windows-Setup-Test.exe
fi
MSYS2_ARG_CONV_EXCL='*' "$iscc" /Q "${defs[@]}" "$(cygpath -w "$root/installer/DreamOS.iss")"
setup="$out/$name"
[[ -f "$setup" ]] || { echo "ISCC made no $name" >&2; exit 1; }
# "<hex>  <name>": Git for Windows' sha256sum would write "*<name>".
printf '%s  %s\n' "$(sha256sum "$setup" | cut -d' ' -f1)" "$name" > "$setup.sha256"
printf '  %s  %s MB\n' "$(cygpath -w "$setup")" "$(( $(stat -c %s "$setup") / 1048576 ))"
