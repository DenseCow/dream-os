#!/bin/bash
# Builds a Dream OS release for the self-updater (DreamUpdate): the APK signed with the RELEASE key, its
# SHA-256 file, and the `gh release create` command that publishes both. It does NOT publish anything.
#
#   1. Bump dreamVersion in app/build.gradle.kts (4.0 -> 4.1; the version code follows: 40100).
#   2. Write the notes in docs/releases/<version>.md (plain Markdown; what users see under View Changes).
#   3. tools/release.sh            -> dist/dreamos-<version>.apk and dist/dreamos-<version>.apk.sha256
#   4. Run the printed gh command (repository = local.properties update.repo).
#
# Every installed Dream OS checks GitHub's "latest" release once a day: tag v<version>, one .apk asset and its
# .apk.sha256 asset. No checksum, a wrong one, or an APK signed with another key = the update is refused.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(ls -d '/c/Program Files/Microsoft/'jdk-17* 2>/dev/null | head -1)}"
GRADLE="${GRADLE:-gradle}"
SIGNING="${DREAMOS_SIGNING:-$HOME/.android/dreamos-release.properties}"

VERSION=$(sed -n 's/^val dreamVersion = "\([0-9.]*\)".*/\1/p' "$ROOT/app/build.gradle.kts")
[ -n "$VERSION" ] || { echo "Couldn't read dreamVersion from app/build.gradle.kts"; exit 1; }
REPO=$(sed -n 's/^update\.repo=\(.*\)$/\1/p' "$ROOT/local.properties" 2>/dev/null | tr -d '[:space:]')
# A release build signed with the debug key could never update anyone's Dream OS: refuse.
[ -f "$SIGNING" ] || { echo "Release signing file not found: $SIGNING (see local.properties.example)"; exit 1; }

echo "Building Dream OS $VERSION (release key)..."
(cd "$ROOT" && "$GRADLE" assembleRelease --console=plain -q)
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "No APK at $APK"; exit 1; }

# The APK must carry the version we tag (aapt from the newest build-tools, when present).
SDK=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | sed 's/\\:/:/g; s/\\\\/\//g')
AAPT=$(ls -d "$SDK"/build-tools/*/aapt* 2>/dev/null | grep -E 'aapt(\.exe)?$' | sort | tail -1 || true)
if [ -n "$AAPT" ]; then
  BADGE=$("$AAPT" dump badging "$APK" 2>/dev/null | head -1)
  echo "$BADGE" | grep -q "versionName='$VERSION'" || { echo "APK version doesn't match $VERSION: $BADGE"; exit 1; }
  echo "  $BADGE"
fi
# Signed with the release key, not the debug one (apksigner from build-tools, when present).
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner.jar "$SDK"/build-tools/*/lib/apksigner.jar 2>/dev/null | sort | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
  CERT=$("$JAVA_HOME/bin/java" -jar "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -m1 "certificate DN" || true)
  echo "  Signer: ${CERT#*: }"
  echo "$CERT" | grep -qi "Android Debug" && { echo "Signed with the DEBUG key: no install could take this update."; exit 1; }
fi

DIST="$ROOT/dist"
mkdir -p "$DIST"
NAME="dreamos-$VERSION.apk"
cp "$APK" "$DIST/$NAME"
(cd "$DIST" && sha256sum "$NAME" > "$NAME.sha256")
SIZE=$(du -h "$DIST/$NAME" | cut -f1)
NOTES="$ROOT/docs/releases/$VERSION.md"

echo
echo "Ready: dist/$NAME ($SIZE)"
echo "       dist/$NAME.sha256  ($(cut -d' ' -f1 "$DIST/$NAME.sha256"))"
[ -f "$NOTES" ] || echo "Note: write the release notes in docs/releases/$VERSION.md first (shown under View Changes)."
echo
echo "Publish (not run by this script):"
echo
echo "  gh release create v$VERSION \\"
echo "    \"dist/$NAME\" \"dist/$NAME.sha256\" \\"
echo "    --repo ${REPO:-OWNER/NAME} \\"
echo "    --title \"Dream OS $VERSION\" \\"
if [ -f "$NOTES" ]; then echo "    --notes-file \"docs/releases/$VERSION.md\" --latest"; else echo "    --notes \"...\" --latest"; fi
if [ -z "$REPO" ]; then
  echo
  echo "  (Set update.repo=owner/name in local.properties, then rebuild, so installs look for updates there.)"
fi
