#!/usr/bin/env bash
# regenerate.sh — rebuild a differential's inputs and goldens from upstream's Swift code.
#
# Usage (from anywhere):  android/tools/sleep-differential/regenerate.sh [sleep|vitals] [--keep]
#   sleep    the sleep pipeline (the default when no target is given) → SleepDifferentialTest's files
#   vitals   the vitals and energy maths → VitalsDifferentialTest's files
#   --keep   leave the temporary build directory in place and print its path
# A run regenerates only its own target's files: a vitals run never touches the sleep goldens, and
# a sleep run never touches the vitals goldens.
#
# What it does, in order:
#   1. reads the pinned upstream commit from the single "Pinned SHA: <40-hex>" line in
#      android/UPSTREAM.md;
#   2. extracts ios/OpenCircuitKit at that commit into a fresh temporary directory with
#      `git archive` (the working tree is never read or touched);
#   3. overlays Sources/OpenCircuitKit/RingEventLog.swift from upstream commit OVERLAY_SHA below:
#      at the pin that file does not type-check on Swift 6.3 (the compiler gives up on one long
#      expression); the later commit splits that expression and changes no behaviour, and neither
#      generator reads the file;
#   4. copies this tool next to it and builds the target's generator alone with
#      `swift build --product <generator> -j 4`, scratch space inside the temporary directory
#      (debug, so the generator may count internal branches);
#   5. runs the generator, which writes inputs.txt, goldens.txt and coverage.txt into the target's
#      directory under android/ringkit/src/test/resources/ and prints the branch counts.
#
# Requires git and Swift 6 (the Command Line Tools are enough: no Xcode, no XCTest). Gradle never
# runs this script; the Kotlin tests only read the files it writes.
set -euo pipefail

OVERLAY_SHA=fd5d4f76728b16926e6bedec8f8aa64a640a7254
OVERLAY_FILE=ios/OpenCircuitKit/Sources/OpenCircuitKit/RingEventLog.swift

die() { echo "regenerate: $1" >&2; exit 1; }

keep=0
target=""
for arg in "$@"; do
  case "$arg" in
    --keep) keep=1 ;;
    sleep|vitals)
      [ -z "$target" ] || die "more than one target given ($target, $arg)"
      target=$arg ;;
    *) die "unknown argument: $arg (expected sleep, vitals or --keep)" ;;
  esac
done
target=${target:-sleep}
case "$target" in
  sleep) GENERATOR=SleepDifferential; RESOURCE=sleep-differential ;;
  vitals) GENERATOR=VitalsDifferential; RESOURCE=vitals-differential ;;
esac

SCRIPT_DIR=$(CDPATH='' cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ANDROID_DIR=$(CDPATH='' cd -P -- "$SCRIPT_DIR/../.." && pwd)
REPO_DIR=$(git -C "$ANDROID_DIR" rev-parse --show-toplevel)
OUT_DIR="$ANDROID_DIR/ringkit/src/test/resources/$RESOURCE"

pins=$(sed -n 's/^Pinned SHA: \([0-9a-f]\{40\}\)$/\1/p' "$ANDROID_DIR/UPSTREAM.md")
[ "$(printf '%s\n' "$pins" | grep -c .)" = 1 ] || die "expected exactly one 'Pinned SHA:' line in UPSTREAM.md"
PIN=$pins
git -C "$REPO_DIR" cat-file -e "$PIN^{commit}" 2>/dev/null || die "pinned commit $PIN is not in this clone (git fetch upstream)"
git -C "$REPO_DIR" cat-file -e "$OVERLAY_SHA^{commit}" 2>/dev/null || die "overlay commit $OVERLAY_SHA is not in this clone (git fetch upstream)"

work=$(mktemp -d "${TMPDIR:-/tmp}/sleep-differential.XXXXXX")
cleanup() {
  if [ "$keep" = 1 ]; then echo "regenerate: kept $work" >&2; else rm -rf -- "$work"; fi
}
trap cleanup EXIT

echo "regenerate: $target — upstream $PIN + $OVERLAY_FILE from $OVERLAY_SHA" >&2
git -C "$REPO_DIR" archive "$PIN" ios/OpenCircuitKit | tar -x -C "$work"
mv "$work/ios/OpenCircuitKit" "$work/OpenCircuitKit"
git -C "$REPO_DIR" show "$OVERLAY_SHA:$OVERLAY_FILE" > "$work/OpenCircuitKit/Sources/OpenCircuitKit/RingEventLog.swift"

mkdir -p "$work/tool"
cp -R "$SCRIPT_DIR/Package.swift" "$SCRIPT_DIR/Sources" "$work/tool/"
swift build --package-path "$work/tool" --scratch-path "$work/scratch" --product "$GENERATOR" -j 4 >&2
bin=$(swift build --package-path "$work/tool" --scratch-path "$work/scratch" --show-bin-path)

mkdir -p "$OUT_DIR"
"$bin/$GENERATOR" "$OUT_DIR"
echo "regenerate: wrote $(cd "$OUT_DIR" && ls inputs.txt goldens.txt coverage.txt | tr '\n' ' ')" >&2
