#!/usr/bin/env bash
# Fills third_party/ (git-ignored) with everything the app bundles: libmpv (tools/fetch-mpv.sh) and the Python
# that runs scraper repositories (tools/fetch-python.sh). Each is a pinned version checked against its SHA-256,
# and each script skips the download when the right version is already there.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
bash "$here/fetch-mpv.sh"
bash "$here/fetch-python.sh" "$@"
