#!/bin/bash
# Removes static-library frameworks from a built .app (usage: strip-static.sh path/to/DreamOS.app).
# MPVKit's xcframeworks are static archives inside a universal (fat) header. They are already linked into the
# app binary, but xtool 1.24 still copies them into Frameworks/ (its static check only knows bare "!<arch>"),
# and its signer then fails ("Invalid arch file in fat mach-o file!"). Dynamic frameworks (Python) stay.
app="${1:?path to .app}"
[ -d "$app/Frameworks" ] || exit 0
removed=0
for f in "$app"/Frameworks/*.framework; do
  [ -d "$f" ] || continue
  n=$(basename "$f" .framework)
  bin="$f/$n"
  if [ -f "$bin" ] && file -b "$bin" | grep -q "ar archive"; then
    rm -rf "$f"
    removed=$((removed + 1))
  fi
done
rmdir "$app/Frameworks" 2>/dev/null
echo "strip-static: removed $removed static framework(s); left: $(ls "$app/Frameworks" 2>/dev/null | tr '\n' ' ')"
