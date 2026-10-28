#!/bin/sh
# Offline contract tests for Dream OS's AllDebrid / Premiumize / TorBox / Real-Debrid backends.
# Compiles the app's REAL DebridApi.kt + DebridOthers.kt (straight from the project) with the stubs and
# tests here, then runs them on the desktop JVM against a local mock HTTP server. Never touches a device.
# Usage: sh run.sh [pm|tb|rd ...]      (DEBRID_TEST_LOG=1 echoes the app's log lines, DEBRID_TEST_VERBOSE=1 lists every request)
set -e
D="$(cd "$(dirname "$0")" && pwd)"
L="$(cd "$D/../.." && pwd)/app/src/main/java/com/firehome/launcher"
C="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
JSON="$D/lib/json.jar"
STD="$C/org.jetbrains.kotlin/kotlin-stdlib/1.9.24/9928532f12c66ad816a625b3f9984f8368ca6d2b/kotlin-stdlib-1.9.24.jar"
KC="$C/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.24/78dab849090e6c5e2eadb6e59a11cdc28fb67a08/kotlin-compiler-embeddable-1.9.24.jar;$STD;$C/org.jetbrains.kotlin/kotlin-script-runtime/1.9.24/96771497da90fbc5af1c90fce148db2595a62502/kotlin-script-runtime-1.9.24.jar;$C/org.jetbrains.kotlin/kotlin-reflect/1.9.22/761ab33cf85f03a3a24595e1b92639d82e7a9564/kotlin-reflect-1.9.22.jar;$C/org.jetbrains.kotlin/kotlin-daemon-embeddable/1.9.24/86685b9c5e8d170925bd9ddbdc263374a6a73281/kotlin-daemon-embeddable-1.9.24.jar;$C/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar;$C/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.5.0/d8cebccdcddd029022aa8646a5a953ff88b13ac8/kotlinx-coroutines-core-jvm-1.5.0.jar;$C/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar"
W() { echo "$1" | sed 's#/c/#C:/#g'; }
JAVA="$(ls -d '/c/Program Files/Microsoft/'jdk-17* | head -1)/bin/java"
rm -rf "$D/out"
SRCS="$(W $L/DebridApi.kt) $(W $L/DebridOthers.kt) $(W $L/DebridAllDebrid.kt)"
for f in "$D"/stubs/*.kt "$D"/src/*.kt; do SRCS="$SRCS $(W $f)"; done
echo "compiling (Kotlin 1.9.24)..."
"$JAVA" -cp "$(W "$KC")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn -cp "$(W "$STD;$JSON")" \
    -jvm-target 1.8 -module-name app -d "$(W $D/out)" $SRCS 2>&1 | grep -v "^warning" || true
[ -d "$D/out/debridtest" ] || { echo "compile failed"; exit 2; }
"$JAVA" -cp "$(W "$D/out;$STD;$JSON")" debridtest.MainKt "$@"
