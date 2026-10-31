#!/usr/bin/env bash
# Fetches the Python that runs the viewer's scraper repositories (scrapers module, ScraperHost.kt):
#   third_party/python/       the official Windows "embeddable" CPython (x64), with our own python313._pth
#   third_party/python-site/  requests and what it needs (pure wheels from PyPI)
# Exact versions, each download checked against its pinned SHA-256 before it is unpacked.
# Every run starts from new, empty folders. Nothing downloaded is executed here.
#
# Python 3.13 (not the Fire Stick's 3.11): 3.11's last Windows build is 3.11.9 from April 2024, with no
# security fixes since (old OpenSSL, while scrapers fetch from arbitrary sites). The iPhone already runs
# CocoScrapers on 3.13 (BeeWare 3.13.18); 3.13.17 is the newest 3.13 python.org ships for Windows.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"

PY_VERSION=3.13.17
PY_URL="https://www.python.org/ftp/python/$PY_VERSION/python-$PY_VERSION-embed-amd64.zip"
PY_SHA256=9e35319bffedab8b36ad44d8f377cb4f1733bcec257a0b6d04d94f01486ddb38   # python.org release file API

# name|version|url|sha256 (PyPI)
WHEELS=(
  "requests|2.34.2|https://files.pythonhosted.org/packages/a0/f4/c67b0b3f1b9245e8d266f0f112c500d50e5b4e83cb6f3b71b6528104182a/requests-2.34.2-py3-none-any.whl|2a0d60c172f83ac6ab31e4554906c0f3b3588d37b5cb939b1c061f4907e278e0"
  "urllib3|2.8.0|https://files.pythonhosted.org/packages/92/9d/c4e665119135114480843e7ab388fa94d8480650450e6f8e26b70d323a4c/urllib3-2.8.0-py3-none-any.whl|0cf3cae568d36aa9576b28dfb35f11328f1cb974ca7647d9475ebb86c75ac6e3"
  "idna|3.20|https://files.pythonhosted.org/packages/58/a2/bb081bab032533a855d44de1d56f8e8426114ff1ba5d1f07a438a0a654f8/idna-3.20-py3-none-any.whl|ab7ae7122974553370f0bdb919e1a960b2cd1bc1ef0276416d896db81c14582c"
  "charset-normalizer|3.5.2|https://files.pythonhosted.org/packages/fc/ad/d07d7862a62ffa6d79d68074d14823243dd235a77c45262acbf6adeb28bf/charset_normalizer-3.5.2-py3-none-any.whl|b6b751274acb69d77b3323d6b7dbaa3c7fdfc1eb829b7eb61d262f32e1af9685"
  "certifi|2026.10.23|https://files.pythonhosted.org/packages/32/d1/a3652fe2c19134d0d15922177821d863cbd7e842d53a21bbe7e8e3a1b5ac/certifi-2026.10.23-py3-none-any.whl|e9b54de76e530bb6cf0ccd036787103acb58fd42ba930ea8bf948ddbaa2f61d1"
)

STAMP="python $PY_VERSION; $(printf '%s ' "${WHEELS[@]%%|http*}")"
if [[ "${1:-}" != "--force" && -f "$TP/python/.dream-fetched" && "$(cat "$TP/python/.dream-fetched")" == "$STAMP" \
      && -f "$TP/python-site/.dream-fetched" ]]; then
  echo "Python $PY_VERSION already in third_party (use --force to fetch again)"
  exit 0
fi

fetch() { # url sha256 dest
  curl -fsSL --proto '=https' --tlsv1.2 --retry 3 -o "$3" "$1"
  local got
  got="$(sha256sum "$3" | cut -d' ' -f1)"
  if [[ "$got" != "$2" ]]; then
    echo "checksum mismatch for $1: expected $2, got $got" >&2
    rm -f "$3"
    exit 1
  fi
}

mkdir -p "$TP"
DL="$(mktemp -d "$TP/.download-XXXXXX")"
trap 'rm -rf "$DL"' EXIT

echo "Fetching Python $PY_VERSION (embeddable, x64)"
fetch "$PY_URL" "$PY_SHA256" "$DL/python.zip"
rm -rf "$TP/python"
mkdir "$TP/python"
unzip -q "$DL/python.zip" -d "$TP/python"

# Our own path file replaces the one in the download: the stdlib zip and the interpreter's own folder only.
# ScraperHost starts it with -I (isolated: no PYTHON* variables, no user site, no script folder); the host
# script adds its own folder and python-site itself.
PTH="$(ls "$TP/python"/python3*._pth)"
printf 'python313.zip\n.\n' > "$PTH"

rm -rf "$TP/python-site"
mkdir "$TP/python-site"
for w in "${WHEELS[@]}"; do
  IFS='|' read -r name version url sha <<< "$w"
  echo "Fetching $name $version"
  fetch "$url" "$sha" "$DL/$name.whl"
  unzip -q -o "$DL/$name.whl" -d "$TP/python-site"
done
# Wheels' metadata folders are not needed to import the packages.
rm -rf "$TP/python-site"/*.dist-info

echo "$STAMP" > "$TP/python/.dream-fetched"
echo "$STAMP" > "$TP/python-site/.dream-fetched"
du -sh "$TP/python" "$TP/python-site"
