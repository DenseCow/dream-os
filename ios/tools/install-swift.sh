#!/bin/bash
# Installs swiftly + the Swift toolchain xtool needs, for the current WSL user.
set -e
cd ~
curl -fsSLO "https://download.swift.org/swiftly/linux/swiftly-$(uname -m).tar.gz"
tar zxf "swiftly-$(uname -m).tar.gz"
./swiftly init --quiet-shell-followup --assume-yes --skip-install
. "${SWIFTLY_HOME_DIR:-$HOME/.local/share/swiftly}/env.sh"
swiftly install --use 6.4 --assume-yes
rm -f "swiftly-$(uname -m).tar.gz" swiftly
swift --version
