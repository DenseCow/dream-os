#!/bin/bash
# Publishes a Dream OS release on this repository's Releases page (maintainers only).
#
#   1. tools/sync.sh: exports the Fire TV and iPhone apps' committed code (HEAD), scrubs it, privacy scan
#   2. builds the Fire TV APK from that exported code in a scratch folder, with NO keys inside (empty TMDB, OMDb,
#      Trakt, Premiumize), the updater pointed at this repository, signed with the existing release key (read
#      from its properties file outside every repository; the key is never copied anywhere)
#   3. checks the version, that the signer is the release key (not the debug key), and privacy-scans the APK
#   4. IPA=1: builds the unsigned iPhone Dream.ipa in WSL / Linux, removes the builder's home folder from it,
#      privacy-scans it
#   5. commits and pushes the synced code (if it changed), then creates the release v<version> with
#      DreamOS.apk, DreamOS.apk.sha256 (and Dream.ipa) as assets
#
# Every release MUST carry DreamOS.apk: the Downloader code and the in-app updater fetch
# releases/latest/download/DreamOS.apk.
#
# Usage:  tools/release.sh [--dry-run]        (dry run: everything except commit, push and publish)
#   NOTES=path/to/notes.md   release notes (Markdown; shown in the Fire TV app under View Changes)
#   IPA=1                    also build and attach the iPhone Dream.ipa
# Settings come from tools/sources.local: GRADLE, ANDROID_SDK, PYTHON311, RELEASE_SIGNING, RELEASE_CERT_SHA256,
# RELEASE_REPO (default DenseCow/dream-os), WSL (default wsl.exe).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
DRY=; [ "${1:-}" = "--dry-run" ] && DRY=1
. "$ROOT/tools/sources.local"
REPO="${RELEASE_REPO:-DenseCow/dream-os}"
SIGNING="${RELEASE_SIGNING:-$HOME/.android/dreamos-release.properties}"
PY="${PYTHON:-python}"
: "${GRADLE:?set GRADLE in tools/sources.local}" "${ANDROID_SDK:?set ANDROID_SDK}" "${RELEASE_CERT_SHA256:?set RELEASE_CERT_SHA256}"
export JAVA_HOME="${JAVA_HOME:-$(ls -d '/c/Program Files/Microsoft/'jdk-17* 2>/dev/null | head -1)}"
winpath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

[ -f "$SIGNING" ] || { echo "Release signing file not found: $SIGNING"; exit 1; }
[ -z "$(git -C "$ROOT" status --porcelain -- ':!firestick' ':!ios')" ] || { echo "Commit your changes outside firestick/ and ios/ first."; exit 1; }

echo "== 1/5 Sync and privacy scan"
bash "$ROOT/tools/sync.sh"

VERSION=$(sed -n 's/^val dreamVersion = "\([0-9.]*\)".*/\1/p' "$ROOT/firestick/app/build.gradle.kts")
[ -n "$VERSION" ] || { echo "Couldn't read dreamVersion"; exit 1; }
TAG="v$VERSION"
# One version for both apps: the iPhone's Info.plist (DreamOS-iOS tools/set-version.sh) must say the same as the
# Fire Stick's dreamVersion (fire-home tools/set-version.sh), with the same build number (major*10000+minor*100+patch).
plist_value() { grep -A1 "<key>$1</key>" "$ROOT/ios/app/Info.plist" | tr -d '\r' | sed -n 's|.*<string>\(.*\)</string>.*|\1|p' | head -1 || true; }
IFS=. read -r V1 V2 V3 <<< "$VERSION"
CODE=$(( V1 * 10000 + ${V2:-0} * 100 + ${V3:-0} ))
IOS_VERSION=$(plist_value CFBundleShortVersionString)
IOS_BUILD=$(plist_value CFBundleVersion)
echo "  Fire Stick: Version $VERSION ($CODE)"
echo "  iPhone:     Version ${IOS_VERSION:-?} (${IOS_BUILD:-?})"
if [ "$IOS_VERSION" != "$VERSION" ] || [ "$IOS_BUILD" != "$CODE" ]; then
  echo "The two apps' versions differ. Run tools/set-version.sh $VERSION in both apps (fire-home and DreamOS-iOS),"
  echo "commit them, then run this again."
  exit 1
fi
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Release $TAG already exists in $REPO. Run tools/set-version.sh <new version> in both apps first."; exit 1
fi

echo "== 2/5 Build the Fire TV APK $VERSION (no keys)"
WORK="$(cd "${TMPDIR:-/tmp}" && pwd -P)/dream-release"
case "$WORK" in */dream-release) rm -rf "$WORK" ;; *) echo "bad work folder"; exit 1 ;; esac
mkdir -p "$WORK/build" "$WORK/out"
cp -r "$ROOT/firestick/." "$WORK/build/"
{
  echo "sdk.dir=$(winpath "$ANDROID_SDK")"
  [ -n "${PYTHON311:-}" ] && echo "python.build=$PYTHON311"
  echo "tmdb.key="; echo "omdb.key="; echo "premiumize.client_id="
  echo "trakt.client_id="; echo "trakt.client_secret="
  echo "update.repo=$REPO"
  echo "build.channel=PUBLIC"   # the boot intro and Settings › About show "(PUBLIC)"; every other build is DEV
  echo "release.signing=$(winpath "$SIGNING")"
} > "$WORK/build/local.properties"
(cd "$WORK/build" && "$GRADLE" assembleRelease --console=plain -q) > "$WORK/gradle.log" 2>&1 \
  || { tail -30 "$WORK/gradle.log"; echo "APK build failed"; exit 1; }
APK="$WORK/out/DreamOS.apk"
cp "$WORK/build/app/build/outputs/apk/release/app-release.apk" "$APK"
rm -f "$WORK/build/local.properties"

echo "== 3/5 Check the APK"
BT=$(ls -d "$ANDROID_SDK"/build-tools/* | sort | tail -1)
AAPT=$(ls "$BT"/aapt.exe "$BT"/aapt 2>/dev/null | head -1)
BADGE=$("$AAPT" dump badging "$APK" | head -1)
echo "  $BADGE"
echo "$BADGE" | grep -q "versionName='$VERSION'" || { echo "APK version isn't $VERSION"; exit 1; }
CERT=$("$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" verify --print-certs "$APK")
echo "$CERT" | grep -q "SHA-256 digest: $RELEASE_CERT_SHA256" || { echo "Not signed with the release key:"; echo "$CERT"; exit 1; }
echo "  Signed with the release key ($(echo "$CERT" | grep -m1 'certificate DN' | cut -d: -f2-))"
"$PY" -I "$ROOT/tools/privacy_scan.py" apk "$APK"
(cd "$WORK/out" && sha256sum DreamOS.apk > DreamOS.apk.sha256)
ASSETS=("$APK" "$WORK/out/DreamOS.apk.sha256")

if [ -n "${IPA:-}" ]; then
  echo "== 4/5 Build the iPhone Dream.ipa (no keys)"
  bash "$ROOT/ios/tools/gen-secrets.sh" /nonexistent >/dev/null   # empty keys
  W="${WSL:-wsl.exe}"
  wslp() { MSYS_NO_PATHCONV=1 "$W" -e wslpath -a "$(winpath "$1")" | tr -d '\r'; }
  IOS_W="$(wslp "$ROOT/ios")"; OUT_W="$(wslp "$WORK/out")"; TOOL_W="$(wslp "$ROOT/tools/ipa-publish.sh")"
  [ -n "$IOS_W" ] && [ -n "$OUT_W" ] && [ -n "$TOOL_W" ] || { echo "Couldn't map paths into WSL"; exit 1; }
  MSYS_NO_PATHCONV=1 "$W" -e bash -lc "bash '$TOOL_W' '$IOS_W' '$OUT_W'"
  rm -f "$ROOT/ios/app/Sources/DreamCore/Secrets.swift"
  "$PY" -I "$ROOT/tools/ipa_scrub.py" "$WORK/out/Dream-raw.ipa" "$WORK/out/Dream.ipa"
  "$PY" -I "$ROOT/tools/privacy_scan.py" apk "$WORK/out/Dream.ipa"
  ASSETS+=("$WORK/out/Dream.ipa")
else
  echo "== 4/5 iPhone .ipa skipped (IPA=1 to build it)"
fi

echo "== 5/5 Publish $TAG to $REPO"
if [ -z "$(git -C "$ROOT" status --porcelain)" ]; then
  echo "  Code unchanged since the last push."
elif [ -z "$DRY" ]; then
  git -C "$ROOT" add -A
  git -C "$ROOT" commit -q -m "Sync Fire TV and iPhone code for Dream OS $VERSION" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
fi
if [ -n "$DRY" ]; then
  echo "  Dry run: nothing pushed or published. Assets in $WORK/out:"; ls -la "$WORK/out"; exit 0
fi
git -C "$ROOT" push -q origin HEAD
NOTE_ARGS=(--notes "Dream OS $VERSION. See the install guides in the README.")
[ -n "${NOTES:-}" ] && NOTE_ARGS=(--notes-file "$NOTES")
gh release create "$TAG" "${ASSETS[@]}" --repo "$REPO" --title "Dream OS $VERSION" --latest "${NOTE_ARGS[@]}"
echo "  Checking the download link..."
curl -sIL "https://github.com/$REPO/releases/latest/download/DreamOS.apk" | grep -iE '^(HTTP|content-length)' | tail -2
echo "Done: https://github.com/$REPO/releases/tag/$TAG"
