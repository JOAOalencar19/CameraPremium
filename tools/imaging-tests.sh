#!/usr/bin/env bash
# Compiles :imaging + :imaging-cli with the standalone Kotlin compiler and runs the test suite.
# Usage: tools/imaging-tests.sh [cli args...]   (default: "test")
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-$(command -v kotlinc || echo /root/android-deps/kotlinc/bin/kotlinc)}"
KHOME="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
OUT="$ROOT/build/imaging-cli"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
"$KOTLINC" "$ROOT/imaging/src/main/kotlin" "$ROOT/imaging-cli/src/main/kotlin" \
  -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline \
  -d "$OUT/classes" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
[ $# -eq 0 ] && set -- test
exec java -Xmx6g -Dcp.testOut="$ROOT/build/test-output" -cp "$OUT/classes:$KHOME/lib/kotlin-stdlib.jar" com.camerapremium.tools.CliKt "$@"
