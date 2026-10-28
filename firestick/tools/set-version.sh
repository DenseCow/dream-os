#!/bin/bash
# Sets Dream OS's version for the Fire Stick in its one place: `val dreamVersion = "..."` in app/build.gradle.kts.
# versionName = that version; versionCode = major*10000 + minor*100 + patch (derived there, "4.2" -> 40200).
# The iPhone app (DreamOS-iOS tools/set-version.sh) must carry the same version: dream-os-public's
# tools/release.sh refuses to publish when the two differ.
#
# Usage: tools/set-version.sh 4.2        (major.minor or major.minor.patch)
set -euo pipefail
v="${1:-}"
[[ "$v" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || { echo "Usage: $0 <major.minor[.patch]>  (e.g. 4.2)"; exit 1; }
IFS=. read -r major minor patch <<< "$v"
code=$(( major * 10000 + ${minor:-0} * 100 + ${patch:-0} ))
gradle="$(cd "$(dirname "$0")/.." && pwd)/app/build.gradle.kts"
grep -q '^val dreamVersion = "' "$gradle" || { echo "No dreamVersion line in $gradle"; exit 1; }
sed -i.bak "s|^val dreamVersion = \"[^\"]*\"|val dreamVersion = \"$v\"|" "$gradle"
rm -f "$gradle.bak"
echo "Dream OS for Fire Stick: Version $v ($code)"
grep '^val dreamVersion' "$gradle" | sed 's/^/  build.gradle.kts now: /'
