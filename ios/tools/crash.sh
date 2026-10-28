#!/bin/bash
# Pulls Dream's newest crash report off the iPhone and prints the exception and the crashing thread's frames.
export USBMUXD_SOCKET_ADDRESS="${USBMUXD_SOCKET_ADDRESS:-$(ip route list default | awk '{print $3}'):27015}"
d=/tmp/crash; mkdir -p "$d"; cd "$d" || exit 1
idevicecrashreport -k -f DreamOS . >/dev/null 2>&1
f=$(ls | grep -i "^DreamOS-.*.ips$" | sort | tail -1)
[ -n "$f" ] || { echo "no Dream crash report found"; exit 1; }
echo "== $f"
python3 - "$f" <<'EOF'
import sys, json
t = open(sys.argv[1]).read()
i = t.find("\n")
b = json.loads(t[i + 1:])
print("exception:", b.get("exception"), "| termination:", (b.get("termination") or {}).get("indicator"))
for k in ("asi", "lastExceptionBacktrace"):
    if k in b: print(k, str(b[k])[:600])
th = b["threads"][b.get("faultingThread", 0)]
imgs = b["usedImages"]
for fr in th["frames"][:25]:
    print(fr.get("symbol", "?"), "+", fr.get("symbolLocation", ""), "|", imgs[fr["imageIndex"]].get("name"))
EOF
