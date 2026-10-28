#!/bin/bash
# Sets Dream's version for iPhone in its one place, app/Info.plist:
#   CFBundleShortVersionString = <version>            (what Settings > About shows, "4.2")
#   CFBundleVersion            = major*10000 + minor*100 + patch   (the build number, "40200"; always increasing)
# The same formula as the Fire Stick's versionCode (fire-home app/build.gradle.kts), so both apps carry the same
# version and code. A release needs both apps on the same version: fire-home has tools/set-version.sh too, and
# dream-os-public's tools/release.sh refuses to publish when they differ.
#
# Usage: tools/set-version.sh 4.2        (major.minor or major.minor.patch)
set -euo pipefail
v="${1:-}"
[[ "$v" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || { echo "Usage: $0 <major.minor[.patch]>  (e.g. 4.2)"; exit 1; }
IFS=. read -r major minor patch <<< "$v"
code=$(( major * 10000 + ${minor:-0} * 100 + ${patch:-0} ))
plist="$(cd "$(dirname "$0")/.." && pwd)/app/Info.plist"
[ -f "$plist" ] || { echo "Not found: $plist"; exit 1; }
grep -q '<key>CFBundleShortVersionString</key>' "$plist" || { echo "No CFBundleShortVersionString in $plist"; exit 1; }
grep -q '<key>CFBundleVersion</key>' "$plist" || { echo "No CFBundleVersion in $plist"; exit 1; }
# The <string> line right after each key.
sed -i.bak \
  -e "/<key>CFBundleShortVersionString<\/key>/{n;s|<string>[^<]*</string>|<string>$v</string>|;}" \
  -e "/<key>CFBundleVersion<\/key>/{n;s|<string>[^<]*</string>|<string>$code</string>|;}" \
  "$plist"
rm -f "$plist.bak"
echo "Dream for iPhone: Version $v ($code)"
grep -A1 -E '<key>CFBundle(ShortVersionString|Version)</key>' "$plist" | grep -o '<string>[^<]*</string>' | sed 's/<[^>]*>//g' | paste -sd' ' | sed 's/^/  Info.plist now: /'
