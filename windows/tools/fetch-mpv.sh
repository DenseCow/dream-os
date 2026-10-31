#!/usr/bin/env bash
# Fetches libmpv (libmpv-2.dll) for Dream Player into third_party/mpv/ (git-ignored).
# Pinned build and SHA-256: a download that doesn't match is deleted and nothing is installed.
# Source: shinchiro/mpv-winbuild-cmake (GPL build of mpv + FFmpeg + libplacebo with d3d11, Vulkan and libdovi; Dream OS is GPL-3.0).
# To move to a newer build: change MPV_TAG / MPV_FILE / MPV_SHA256 (GitHub shows each asset's sha256 digest).
set -euo pipefail

MPV_TAG="20261021"
MPV_FILE="mpv-dev-x86_64-20261021-git-e471496c56.7z"
MPV_SHA256="efc719eff3dc5954b4a5efc800632808bcec01cc9034940dd14e5eb7fe9ea633"
MPV_URL="https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/${MPV_TAG}/${MPV_FILE}"

root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/third_party/mpv"
stamp="$dest/BUILD"

if [[ -f "$stamp" && "$(cat "$stamp")" == "$MPV_FILE" && -f "$dest/libmpv-2.dll" ]]; then
  echo "libmpv already here: $MPV_FILE"
  exit 0
fi

# The archive is untrusted until its checksum matches: it goes in its own empty folder.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
echo "Downloading $MPV_FILE"
curl -fL --retry 3 -o "$work/$MPV_FILE" "$MPV_URL"

got="$(sha256sum "$work/$MPV_FILE" | cut -d' ' -f1)"
if [[ "$got" != "$MPV_SHA256" ]]; then
  echo "Checksum mismatch for $MPV_FILE: expected $MPV_SHA256, got $got" >&2
  exit 1
fi

# Windows' own tar (bsdtar/libarchive) reads 7z; elsewhere use 7z if it is installed.
mkdir -p "$work/x"
if [[ -x /c/Windows/System32/tar.exe ]]; then
  /c/Windows/System32/tar.exe -xf "$(cygpath -w "$work/$MPV_FILE")" -C "$(cygpath -w "$work/x")"
elif command -v 7z >/dev/null; then
  7z x -o"$work/x" "$work/$MPV_FILE" >/dev/null
else
  echo "Need Windows tar.exe or 7z to unpack $MPV_FILE" >&2
  exit 1
fi

[[ -f "$work/x/libmpv-2.dll" ]] || { echo "libmpv-2.dll missing from $MPV_FILE" >&2; exit 1; }
rm -rf "$dest"
mkdir -p "$dest"
cp "$work/x/libmpv-2.dll" "$dest/"
[[ -d "$work/x/include" ]] && cp -r "$work/x/include" "$dest/"
echo "$MPV_FILE" > "$stamp"
echo "libmpv installed in third_party/mpv ($(du -h "$dest/libmpv-2.dll" | cut -f1))"
