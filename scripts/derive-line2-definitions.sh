#!/usr/bin/env bash
# Reproduce the committed Line2 definition candidates in fixtures/line2/ by deriving them live
# from the Bifrost sim's StationType and ToolType.
#
#   mimir/target/mimir.jar                  - the modeler (this repo)
#   $BIFROST_DIR/sim/target/bifrost-sim.jar - the embedded OPC-UA sim (default BIFROST_DIR=../bifrost)
#
# Run from anywhere:
#   bash scripts/derive-line2-definitions.sh           # derive and overwrite fixtures/line2/*.json
#   bash scripts/derive-line2-definitions.sh --check   # derive and compare byte for byte; exit 1 on difference
#
# The committed bytes were derived from Bifrost commit 11fdad9 (see fixtures/line2/README.md).
set -euo pipefail
cd "$(dirname "$0")/.."

# cygpath exists only under Git Bash / Cygwin. Elsewhere a POSIX path is already what the JVM
# wants, so shim it to identity (drop the -m, keep the last argument).
command -v cygpath >/dev/null 2>&1 || cygpath() { printf '%s\n' "${@: -1}"; }

CHECK=0
case "${1:-}" in
  "") ;;
  --check) CHECK=1 ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

BIFROST_DIR="${BIFROST_DIR:-../bifrost}"
WORK="build/line2"
SIM_LOG="$WORK/sim.log"
FIXTURES="fixtures/line2"

MIMIR_JAR="target/mimir.jar"
SIM_JAR="$BIFROST_DIR/sim/target/bifrost-sim.jar"

ENDPOINT="opc.tcp://localhost:48400"
NS_URI="urn:bifrost:opcua:sim"
VERSION="1.0.0"
# typeBrowseName templateRef
DEFINITIONS="StationType station-model
ToolType tool-model"

fail() {
  echo "[LINE2] FAIL: $*"
  echo "--- sim log tail ---"; tail -60 "$SIM_LOG" 2>/dev/null || true
  exit 1
}

kill_by_mainclass() {  # $1=substring of the jps -lm main-class/jar line
  { jps -lm 2>/dev/null | grep -i "$1" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || kill "$p" >/dev/null 2>&1 || true
  done
}

SIM_PID=""
cleanup() {
  [ -n "$SIM_PID" ] && { taskkill //F //T //PID "$SIM_PID" >/dev/null 2>&1 || kill "$SIM_PID" >/dev/null 2>&1 || true; }
  # `$!` from an MSYS-backgrounded native `java -jar` does not reliably match the real Win32 PID;
  # jps -lm on a `-jar` launch reports the jar path, so match on the jar filename.
  kill_by_mainclass "bifrost-sim.jar" || true
}
trap cleanup EXIT

kill_by_mainclass "bifrost-sim.jar" || true

mkdir -p "$WORK"

echo "[LINE2] step 0: build jars if missing"
mvn -q package -DskipTests
if [ ! -f "$SIM_JAR" ]; then
  ( cd "$BIFROST_DIR" && mvn -q -pl sim -am package -DskipTests )
fi
[ -f "$MIMIR_JAR" ] || fail "$MIMIR_JAR missing after build"
[ -f "$SIM_JAR" ]   || fail "$SIM_JAR missing after build"

MIMIR_JAR_WIN="$(cygpath -m "$(pwd)/$MIMIR_JAR")"
SIM_JAR_WIN="$(cygpath -m "$(cd "$(dirname "$SIM_JAR")" && pwd)/$(basename "$SIM_JAR")")"
echo "[LINE2] mimir=$MIMIR_JAR_WIN"
echo "[LINE2] sim=$SIM_JAR_WIN"
echo "[LINE2] bifrost commit=$(git -C "$BIFROST_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo "[LINE2] step 1: start the embedded OPC-UA sim + wait for 'OPC-UA sim listening'"
: > "$SIM_LOG"
java -jar "$SIM_JAR_WIN" > "$SIM_LOG" 2>&1 &
SIM_PID=$!
ok=0
for i in $(seq 1 30); do
  grep -q "OPC-UA sim listening" "$SIM_LOG" 2>/dev/null && { ok=1; break; }
  sleep 1
done
[ "$ok" = "1" ] || fail "OPC-UA sim did not start (pid $SIM_PID)"
echo "[LINE2] OPC-UA sim listening (pid $SIM_PID)"

echo "[LINE2] step 2: derive"
diffs=0
while read -r type ref; do
  out="$WORK/$ref-$VERSION.json"
  rm -f "$out"
  set +e
  java -jar "$MIMIR_JAR_WIN" derive "$ENDPOINT" "$NS_URI" "$type" "$ref" "$VERSION" \
    "$(cygpath -m "$(pwd)/$out")" < /dev/null
  code=$?
  set -e
  [ "$code" -eq 0 ] || fail "mimir derive $type returned $code - expected 0"
  [ -f "$out" ] || fail "mimir did not write $out"

  committed="$FIXTURES/$ref-$VERSION.json"
  if [ "$CHECK" = "1" ]; then
    if cmp -s "$out" "$committed"; then
      echo "[LINE2] match: $committed"
    else
      echo "[LINE2] DIFFERENT: $committed"
      echo "  derived:   $(cat "$out")"
      echo "  committed: $(cat "$committed" 2>/dev/null || echo '<missing>')"
      diffs=$((diffs + 1))
    fi
  else
    mkdir -p "$FIXTURES"
    cp "$out" "$committed"
    echo "[LINE2] wrote $committed"
  fi
done <<< "$DEFINITIONS"

if [ "$diffs" -ne 0 ]; then
  echo "[LINE2] FAIL: $diffs definition(s) differ from the committed bytes"
  exit 1
fi
echo "[LINE2] PASS derive-line2-definitions.sh"
exit 0
