#!/bin/bash
# Stands in for Inno Setup's ISCC.exe while tools/release.sh builds the Windows setup (WIN=1): windows/tools/
# build-installer.sh calls it with /DAppImage=<the app image>. It privacy-scans that folder (tools/privacy_scan.py
# image) and runs the real ISCC.exe ($DREAM_REAL_ISCC) only when the scan is clean, so a hit stops the build before
# anything is packed.
set -euo pipefail
real="${DREAM_REAL_ISCC:?set DREAM_REAL_ISCC to the real ISCC.exe}"
image=""
for a in "$@"; do
  case "$a" in /DAppImage=*) image="${a#/DAppImage=}" ;; esac
done
[ -n "$image" ] || { echo "iscc-scan: no /DAppImage= argument" >&2; exit 1; }
# build-installer.sh sets MSYS2_ARG_CONV_EXCL='*', so Git Bash converts no argument here: the image path is already a
# Windows path (C:\...), and the scanner's path is converted by hand.
"${PYTHON:-python}" -I "$(cygpath -w "$(cd "$(dirname "$0")" && pwd -P)/privacy_scan.py")" image "$image"
exec "$real" "$@"
