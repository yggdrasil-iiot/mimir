#!/usr/bin/env bash
# CHUNK-3 ACCEPTANCE GATE: prove the northbound Mímir modeler derives a canonical UdtDefinition
# from a LIVE OPC-UA type browse, and that bifrost's ① schema gate governs it end-to-end —
# a faithful derive is ADMITTED (initial registration + promote), and a breaking re-derive
# (a member removed) is REJECTED under FORWARD compatibility.
#
# This exercises three separately-built artifacts with ZERO shared code:
#   mimir/target/mimir.jar          — the modeler (this repo): live browse -> UdtDefinition JSON
#   ../bifrost/sim/target/bifrost-sim.jar     — the embedded OPC-UA MixerType server
#   ../bifrost/gates/target/bifrost-gates.jar — bifrost's schema-compat gate
#
# Run from anywhere:
#   bash scripts/run-mimir-gate.sh
#   # expect: [GATE] PASS run-mimir-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

WORK="build/gate"
SIM_LOG="$WORK/sim.log"

MIMIR_JAR="target/mimir.jar"
SIM_JAR="../bifrost/sim/target/bifrost-sim.jar"
GATES_JAR="../bifrost/gates/target/bifrost-gates.jar"

ENDPOINT="opc.tcp://localhost:48400"
NS_URI="urn:bifrost:opcua:sim"
TYPE="MixerType"
REF="Line1-Mixer"

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- sim log tail ---"; tail -60 "$SIM_LOG" 2>/dev/null || true
  exit 1
}

kill_by_mainclass() {  # $1=substring of the jps -lm main-class/jar line
  { jps -lm 2>/dev/null | grep -i "$1" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}

SIM_PID=""
cleanup() {
  [ -n "$SIM_PID" ] && taskkill //F //T //PID "$SIM_PID" >/dev/null 2>&1 || true
  # `$!` from an MSYS-backgrounded native `java -jar` does not reliably match the real Win32 PID
  # (a known MSYS fork/exec quirk); jps -lm on a `-jar` launch reports the JAR PATH not the main
  # class, so match on the jar filename instead.
  kill_by_mainclass "bifrost-sim.jar" || true
}
trap cleanup EXIT

# Mop up orphan sims from a prior aborted run BEFORE we start anything new.
kill_by_mainclass "bifrost-sim.jar" || true

mkdir -p "$WORK"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: build jars if missing"
mvn -q package
if [ ! -f "$SIM_JAR" ] || [ ! -f "$GATES_JAR" ]; then
  ( cd ../bifrost && mvn -q -pl core,sim,gates install )
fi
[ -f "$MIMIR_JAR" ] || fail "$MIMIR_JAR missing after build"
[ -f "$SIM_JAR" ]   || fail "$SIM_JAR missing after build"
[ -f "$GATES_JAR" ] || fail "$GATES_JAR missing after build"

MIMIR_JAR_WIN="$(cygpath -m "$(pwd)/$MIMIR_JAR")"
SIM_JAR_WIN="$(cygpath -m "$(pwd)/$SIM_JAR")"
GATES_JAR_WIN="$(cygpath -m "$(pwd)/$GATES_JAR")"
echo "[GATE] mimir=$MIMIR_JAR_WIN"
echo "[GATE] sim=$SIM_JAR_WIN"
echo "[GATE] gates=$GATES_JAR_WIN"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: start the embedded OPC-UA sim + wait for 'OPC-UA sim listening'"
: > "$SIM_LOG"
java -jar "$SIM_JAR_WIN" > "$SIM_LOG" 2>&1 &
SIM_PID=$!
ok=0
for i in $(seq 1 30); do
  grep -q "OPC-UA sim listening" "$SIM_LOG" 2>/dev/null && { ok=1; break; }
  sleep 1
done
[ "$ok" = "1" ] || fail "OPC-UA sim did not start (pid $SIM_PID)"
echo "[GATE] OPC-UA sim listening (pid $SIM_PID)"

# ---------------------------------------------------------------------------
echo "[GATE] step 2: fresh registry (policy mode=FORWARD)"
rm -rf "$WORK/registry"
mkdir -p "$WORK/registry"
echo '{"mode":"FORWARD"}' > "$WORK/registry/policy.json"

REGISTRY_WIN="$(cygpath -m "$(pwd)/$WORK/registry")"
DEF_WIN="$(cygpath -m "$(pwd)/$WORK/def.json")"
DEF_BREAKING_WIN="$(cygpath -m "$(pwd)/$WORK/def-breaking.json")"

# ---------------------------------------------------------------------------
echo "[GATE] step 3: ACCEPT — mimir derives live MixerType -> schema admits + promotes"
set +e
java -jar "$MIMIR_JAR_WIN" derive "$ENDPOINT" "$NS_URI" "$TYPE" "$REF" 1.0.0 "$DEF_WIN"
code=$?
set -e
[ "$code" -eq 0 ] || fail "mimir derive (accept) returned $code — expected 0"
[ -f "$WORK/def.json" ] || fail "mimir did not write $WORK/def.json"
# mimir emits COMPACT single-line JSON, so count occurrences (grep -o), not matching lines (grep -c).
mcount="$(grep -o '"name"' "$WORK/def.json" | wc -l | tr -d '[:space:]')"
[ "$mcount" -eq 4 ] || fail "expected 4 members in def.json, got $mcount"
grep -Eq '"high"[[:space:]]*:[[:space:]]*3000(\.0)?' "$WORK/def.json" \
  || fail "def.json missing Rpm EURange high=3000 (EURange decode failed?)"
echo "[GATE] derive OK: 4 members, Rpm high=3000 present"

set +e
java -jar "$GATES_JAR_WIN" schema "$REGISTRY_WIN" "$DEF_WIN" --promote
code=$?
set -e
[ "$code" -eq 0 ] || fail "schema gate rejected the faithful derive (exit $code) — expected accept"
[ -f "$WORK/registry/udt/$REF/1.0.0.json" ] \
  || fail "schema gate did not promote to registry/udt/$REF/1.0.0.json"
echo "[GATE] accept OK: schema admitted + promoted $REF@1.0.0"

# ---------------------------------------------------------------------------
echo "[GATE] step 4: REJECT — breaking re-derive (Running removed) must FAIL under FORWARD"
set +e
java -jar "$MIMIR_JAR_WIN" derive "$ENDPOINT" "$NS_URI" "$TYPE" "$REF" 1.1.0 "$DEF_BREAKING_WIN" --omit Running
code=$?
set -e
[ "$code" -eq 0 ] || fail "mimir derive (breaking) returned $code — expected 0 (deriving succeeds)"
[ -f "$WORK/def-breaking.json" ] || fail "mimir did not write $WORK/def-breaking.json"

set +e
java -jar "$GATES_JAR_WIN" schema "$REGISTRY_WIN" "$DEF_BREAKING_WIN"
code=$?
set -e
[ "$code" -ne 0 ] || fail "schema gate ACCEPTED a breaking re-derive (exit 0) — expected reject (member.removed)"
echo "[GATE] reject OK: schema rejected the breaking re-derive (exit $code, non-zero)"

echo ""
echo "[GATE] PASS run-mimir-gate.sh"
exit 0
