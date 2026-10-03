#!/usr/bin/env bash
# store-gate-a.sh — run the store's migration test class on its own and prove every test in it ran.
#
# Usage (from anywhere):  scripts/store-gate-a.sh
#
# Runs   ./gradlew --no-daemon :store:jvmTest --rerun --tests "io.github.opencircuit.store.StoreMigrationTest*"
# then compares two numbers:
#   declared — the @Test annotations in StoreMigrationTest.kt: lines whose first word is @Test.
#              A KDoc or comment line that mentions @Test starts with * or // and is not counted.
#   executed — tests minus skipped in that run's JUnit report
#              (store/build/test-results/jvmTest/TEST-io.github.opencircuit.store.StoreMigrationTest.xml).
# The report is deleted before the run, so a report left by an earlier run can never pass.
#
# Why: a test can drop out without failing. JUnit 5 does not run a @Test whose function returns a
# value (for example `= runBlocking { …; someCall() }` ending in a non-Unit value), and a disabled
# test only counts as skipped. A migration test that silently stops running is how a schema change
# that loses rows ships, so the counts must match exactly.
#
# Prints exactly one verdict line and exits 0 on PASS, 1 on FAIL:
#   GATE A: declared N, executed N, failed 0 — PASS
#   GATE A: declared N, executed M, failed F — FAIL (reason)
# This script changes nothing but the build output of that run.
set -uo pipefail

die() { echo "store-gate-a: $1" >&2; exit 1; }

# Physical directory holding path $1 (dirname without dirname). CDPATH= keeps cd from
# echoing the target dir into the capture when CDPATH is set; -P resolves symlinked dirs.
dir_of() {
  case "$1" in
    */*) CDPATH='' cd -P -- "${1%/*}" && pwd ;;
    *) pwd -P ;;
  esac
}

# Follow symlinks to the real script so a linked invocation still finds android/.
# Plain `readlink` (no -f: not on older macOS); a relative target is relative to the link's dir.
src=${BASH_SOURCE[0]}
while [ -L "$src" ]; do
  link_dir=$(dir_of "$src")
  target=$(readlink -- "$src")
  case "$target" in
    /*) src=$target ;;
    *) src=$link_dir/$target ;;
  esac
done
SCRIPT_DIR=$(dir_of "$src") || die "cannot locate the script directory"
ANDROID_DIR=${SCRIPT_DIR%/*}

for arg in "$@"; do
  case "$arg" in
    -h|--help) echo "usage: scripts/store-gate-a.sh"; exit 0 ;;
    *) die "unexpected argument: $arg (usage: scripts/store-gate-a.sh)" ;;
  esac
done

CLASS=io.github.opencircuit.store.StoreMigrationTest
SOURCE=$ANDROID_DIR/store/src/jvmTest/kotlin/io/github/opencircuit/store/StoreMigrationTest.kt
REPORT=$ANDROID_DIR/store/build/test-results/jvmTest/TEST-$CLASS.xml

[ -f "$SOURCE" ] && [ -r "$SOURCE" ] || die "cannot read the migration test class: $SOURCE"
[ -x "$ANDROID_DIR/gradlew" ] || die "no executable gradlew in $ANDROID_DIR"

declared=$(grep -cE -- '^[[:space:]]*@Test([[:space:](]|$)' "$SOURCE" || true)

rm -f -- "$REPORT"
( CDPATH='' cd -P -- "$ANDROID_DIR" && ./gradlew --no-daemon :store:jvmTest --rerun --tests "$CLASS*" )
gradle_status=$?

# One attribute of the report's <testsuite> element (0 when absent).
attr() {
  local value
  value=$(grep -o -m 1 -- '<testsuite [^>]*>' "$REPORT" | sed -n -e "s/.* $1=\"\([0-9]*\)\".*/\1/p")
  echo "${value:-0}"
}

if [ -f "$REPORT" ]; then
  tests=$(attr tests)
  skipped=$(attr skipped)
  failures=$(attr failures)
  errors=$(attr errors)
  executed=$((tests - skipped))
  failed=$((failures + errors))
else
  executed=0
  skipped=0
  failed=0
fi

reason=
if [ ! -f "$REPORT" ]; then
  reason="the run wrote no test report (gradle exit $gradle_status)"
elif [ "$declared" -eq 0 ]; then
  reason="no @Test declared in $SOURCE"
elif [ "$failed" -ne 0 ]; then
  reason="$failed test(s) failed"
elif [ "$declared" -ne "$executed" ]; then
  reason="declared and executed differ ($skipped skipped; a test that returns a value is never run)"
elif [ "$gradle_status" -ne 0 ]; then
  reason="gradle exit $gradle_status"
fi

if [ -z "$reason" ]; then
  echo "GATE A: declared $declared, executed $executed, failed $failed — PASS"
  exit 0
fi
echo "GATE A: declared $declared, executed $executed, failed $failed — FAIL ($reason)"
exit 1
