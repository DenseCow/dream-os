#!/bin/bash
# Build, install and fully set up Dream OS on the Fire Stick.
# Usage: [NO_START=1] [SKIP_BUILD=1] tools/deploy.sh <fire-stick-ip>   (Settings > My Fire TV > About > Network)
set -e
S="${1:?usage: tools/deploy.sh <fire-stick-ip>}:5555"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
export JAVA_HOME="$(ls -d '/c/Program Files/Microsoft/'jdk-17* | head -1)"

# SKIP_BUILD=1 installs the APK already built (tools/migrate-signing.sh builds it first).
[ -n "$SKIP_BUILD" ] || (cd "$ROOT" && "${GRADLE:-gradle}" assembleRelease --console=plain -q)
"$ADB" connect "$S" >/dev/null
# Release builds are signed with the Dream OS release key (~/.android/dreamos-release.properties). A stick
# still running a debug-key build can't take it as an update: tools/migrate-signing.sh switches it once,
# keeping Dream OS's data.
if ! out=$("$ADB" -s "$S" install -r "$ROOT/app/build/outputs/apk/release/app-release.apk" 2>&1); then
  echo "$out"
  echo "$out" | grep -q "UPDATE_INCOMPATIBLE" && echo "The stick has Dream OS signed with another key: run tools/migrate-signing.sh once (keeps its data)."
  exit 1
fi
echo "$out" | tail -1
# Location permission is required by Android 9 for Bluetooth LE scans (AirPods battery).
"$ADB" -s "$S" shell pm grant com.firehome.launcher android.permission.ACCESS_FINE_LOCATION || true
# Lets the Home-button service turn itself back on after a force stop (never after the user turns it off
# in Settings), Ad Block's Private DNS switch and turning ADB debugging off from System Health. Optional:
# without it Dream OS opens the matching Settings page instead.
"$ADB" -s "$S" shell pm grant com.firehome.launcher android.permission.WRITE_SECURE_SETTINGS || true
# Self-updater: lets Dream OS hand a verified update to the installer ("Install unknown apps").
"$ADB" -s "$S" shell appops set com.firehome.launcher REQUEST_INSTALL_PACKAGES allow || true
# Continue Watching: lets Dream OS read streaming apps' media sessions (what's playing, progress).
"$ADB" -s "$S" shell cmd notification allow_listener com.firehome.launcher/com.firehome.launcher.AppWatchService || true
# System page: usage access lets it show app sizes including data & cache (else APK size only).
"$ADB" -s "$S" shell appops set com.firehome.launcher GET_USAGE_STATS allow || true
# Assistant slot: exempts Dream OS from the ~5 s launch freeze after Home (instant redirect).
"$ADB" -s "$S" shell settings put secure voice_interaction_service com.firehome.launcher/.FireVoiceService
"$ADB" -s "$S" shell settings put secure assistant com.firehome.launcher/.FireVoiceService
# Use the Dream OS screensaver for Fire OS idle (Amazon's screensaver app is disabled).
"$ADB" -s "$S" shell settings put secure screensaver_components com.firehome.launcher/com.firehome.launcher.DreamSaver
"$ADB" -s "$S" shell settings put secure screensaver_enabled 1
# Earlier versions relaxed hidden-API enforcement for EVERY app on the stick (hidden_api_policy_p_apps 1).
# Not needed: Hwui.kt reaches its one hidden method by meta-reflection, which Android 9 allows. Put it back.
"$ADB" -s "$S" shell settings delete global hidden_api_policy_p_apps >/dev/null 2>&1 || true
# Ahead-of-time compile so the UI is fast immediately (a fresh install starts out interpreted).
"$ADB" -s "$S" shell cmd package compile -m speed -f com.firehome.launcher
# Reinstalling (and compiling, which restarts the app) unbinds the Home-button service; toggle it last.
# Toggling too fast can leave a dead binding in system_server, so pause between steps.
A11Y=com.firehome.launcher/com.firehome.launcher.HomeRedirectService
"$ADB" -s "$S" shell settings put secure enabled_accessibility_services null
sleep 2
"$ADB" -s "$S" shell settings put secure enabled_accessibility_services $A11Y
"$ADB" -s "$S" shell settings put secure accessibility_enabled 1
sleep 3
if ! "$ADB" -s "$S" shell dumpsys accessibility | grep -q "services:{Service"; then
  # Re-toggle slowly instead of rebooting (a reboot interrupts whoever is watching).
  echo "Home-button service didn't rebind; re-toggling slowly"
  bound=
  for t in 1 2 3; do
    "$ADB" -s "$S" shell settings put secure enabled_accessibility_services null; sleep 4
    "$ADB" -s "$S" shell settings put secure enabled_accessibility_services $A11Y
    "$ADB" -s "$S" shell settings put secure accessibility_enabled 1; sleep 5
    "$ADB" -s "$S" shell dumpsys accessibility | grep -q "services:{Service" && { echo "rebound on retry $t"; bound=1; break; }
  done
  # A dead binding in system_server only clears on a reboot: leave that to a person, at a good moment.
  [ -n "$bound" ] || echo "WARNING: still not bound; reboot the stick when nobody is watching"
fi
# NO_START=1 updates quietly (e.g. while someone is watching TV) instead of bringing Dream OS to the front.
[ -n "$NO_START" ] || "$ADB" -s "$S" shell am start -n com.firehome.launcher/.MainActivity >/dev/null
echo "Deployed to $S"
