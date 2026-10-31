#!/bin/bash
# Re-exports the COMMITTED code (HEAD) of the Fire Stick, iPhone and Windows projects into firestick/, ios/ and
# windows/, removes
# private and internal material, rewrites machine-specific paths (tools/scrub.py), then runs the privacy scan
# (tools/privacy_scan.py). The sync FAILS if the scan finds anything that isn't on the reviewed allow-list.
# Working trees are never read and the source projects are never written to.
#
#   tools/sync.sh            export + scrub + scan (nothing is committed or pushed)
#
# Paths come from tools/sources.local (git-ignored): FIREHOME_SRC, IOS_SRC, WIN_SRC, SECRET_FILES, PERSONAL_WORDS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
[ -f "$ROOT/tools/sources.local" ] && . "$ROOT/tools/sources.local"
: "${STAGING_ROOT:?set STAGING_ROOT in tools/sources.local (the absolute path of this repository)}"
: "${FIREHOME_SRC:?set FIREHOME_SRC in tools/sources.local (the git repository of the Fire Stick app)}"
: "${IOS_SRC:?set IOS_SRC in tools/sources.local (the git repository of the iPhone app)}"
: "${WIN_SRC:?set WIN_SRC in tools/sources.local (the git repository of the Windows app)}"
PY="${PYTHON:-python}"

# Safety: this script deletes and rewrites firestick/, ios/ and windows/. Refuse unless ROOT is exactly this repository.
fail() { echo "sync: REFUSING: $*" >&2; exit 1; }
[ -n "$ROOT" ] && [ "$ROOT" != "/" ] || fail "empty or root path"
[ "$ROOT" = "$(cd "$STAGING_ROOT" 2>/dev/null && pwd -P)" ] || fail "$ROOT is not STAGING_ROOT"
[ -d "$ROOT/.git" ] && [ -f "$ROOT/tools/privacy_scan.py" ] && [ -f "$ROOT/LICENSE" ] || fail "$ROOT doesn't look like the repository"
TOP="$(git -C "$ROOT" rev-parse --show-toplevel 2>/dev/null)" || fail "$ROOT is not a git repository"
[ "$(cd "$TOP" && pwd -P)" = "$ROOT" ] || fail "$ROOT is not the top of its own git repository"
for s in "$FIREHOME_SRC" "$IOS_SRC" "$WIN_SRC"; do
  git -C "$s" rev-parse --verify -q HEAD >/dev/null || fail "$s is not a git repository with commits"
done

export_head() { # <source repo> <destination folder: $ROOT/firestick, $ROOT/ios or $ROOT/windows only>
  case "$2" in "$ROOT/firestick"|"$ROOT/ios"|"$ROOT/windows") ;; *) fail "won't delete $2" ;; esac
  rm -rf "$2"; mkdir -p "$2"
  git -C "$1" archive --format=tar HEAD | tar -x -C "$2"
  echo "  $(basename "$2")/ <- $(git -C "$1" rev-parse --short HEAD) ($(git -C "$1" log -1 --format=%cs))"
}

echo "== Export (HEAD only)"
export_head "$FIREHOME_SRC" "$ROOT/firestick"
export_head "$IOS_SRC" "$ROOT/ios"
export_head "$WIN_SRC" "$ROOT/windows"

echo "== Remove internal and private material"
# Fire Stick: agent notes, bug logs, backlog, the internal security log, artwork templates, the one-time signing
# migration, scraped third-party API docs and compiled test output. Docs: only the public rulebook and release notes.
( cd "$ROOT/firestick"
  rm -rf CLAUDE.md artwork tools/migrate-signing.sh tools/debrid-tests/docs tools/debrid-tests/out
  find docs -mindepth 1 -maxdepth 1 ! -name STANDARDS.md ! -name releases -exec rm -rf {} +
)
# iPhone: agent notes, port plans and to-do lists, the Xcode download helper.
( cd "$ROOT/ios"
  rm -rf CLAUDE.md docs tools/copy-xip.sh builds sdk
)
# Windows: the port plan and every per-area note in docs/ (worker notes, requests between workers, parity checklists,
# test logs from the maintainer's PC). windows/BUILDING.md below replaces them. Downloaded binaries (third_party/) are
# git-ignored upstream and never exported; the find below removes them too in case that ever changes.
( cd "$ROOT/windows"
  rm -rf CLAUDE.md docs third_party
)
# Anywhere: keys, signing files, build output, downloaded binaries, compiled Python, assistant folders.
for d in "$ROOT/firestick" "$ROOT/ios" "$ROOT/windows"; do
  find "$d" \( -name CLAUDE.md -o -name .claude -o -name local.properties -o -name secrets.properties \
      -o -name Secrets.swift -o -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.mobileprovision' \
      -o -name 'dreamos-release.properties' -o -name '.env' -o -name '.env.*' -o -name '*.apk' -o -name '*.ipa' \
      -o -name __pycache__ -o -name '*.pyc' -o -name .gradle -o -name build -o -name .build -o -name .swiftpm \
      -o -name third_party -o -name .idea -o -name '*.msi' -o -name 'DreamOS-Windows-Setup*.exe' \) \
      -prune -exec rm -rf {} +
done

echo "== Public extras"
cat > "$ROOT/ios/secrets.properties.example" <<'EOF'
# Copy to secrets.properties (never commit it), then run tools/gen-secrets.sh to write
# app/Sources/DreamCore/Secrets.swift. Every key is optional; leave them empty for builds you share.
# Empty TMDB key = Dream asks for the viewer's own key during setup.
tmdb.key=
# OMDb (Rotten Tomatoes and IMDb scores). Empty = scores stay hidden until a key is entered in Settings.
omdb.key=
# Trakt sync: your own app from https://trakt.tv/oauth/applications. Empty = viewers can enter their own.
trakt.client_id=
trakt.client_secret=
EOF
grep -q '^secrets.properties$' "$ROOT/ios/.gitignore" || printf '\n# Build-time keys (tools/gen-secrets.sh)\nsecrets.properties\n' >> "$ROOT/ios/.gitignore"
# Windows: a public build note in place of the internal docs/ folder (source: tools/extras/windows/).
cp -r "$ROOT/tools/extras/windows/." "$ROOT/windows/"

echo "== Scrub machine-specific paths"
"$PY" -I "$ROOT/tools/scrub.py" "$ROOT"

echo "== Privacy scan"
"$PY" -I "$ROOT/tools/privacy_scan.py" repo "$ROOT"
