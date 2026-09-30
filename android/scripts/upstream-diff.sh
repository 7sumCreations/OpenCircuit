#!/usr/bin/env bash
# upstream-diff.sh — what changed upstream since the pinned SHA (read-only).
#
# Usage (from android/):  scripts/upstream-diff.sh [--no-fetch] [extra/path ...]
#   --no-fetch     skip `git fetch upstream`; compare against the last-fetched upstream/master
#   extra/path     additional repo-root-relative paths to watch
#
# Watched by default (repo-root relative): docs/PROTOCOL.md, ios/OpenCircuitKit, LICENSE.
# Pin source: the single "Pinned SHA: <40-hex>" line in android/UPSTREAM.md (ADR E0 D2);
# override the file with UPSTREAM_MD=<file>. This script never modifies anything.
set -euo pipefail

die() { echo "upstream-diff: $1" >&2; exit 1; }

case "${BASH_SOURCE[0]}" in
  */*) script_dir=${BASH_SOURCE[0]%/*} ;;
  *) script_dir=. ;;
esac
SCRIPT_DIR=$(cd "$script_dir" && pwd)
ANDROID_DIR=${SCRIPT_DIR%/*}
PIN_FILE=${UPSTREAM_MD:-$ANDROID_DIR/UPSTREAM.md}
# Same contract as UpstreamPinTest's pinLine regex — change both together (ADR E0 D2).
PIN_RE='^Pinned SHA: [0-9a-f]{40}[[:space:]]*$'

fetch=1
paths=(docs/PROTOCOL.md ios/OpenCircuitKit LICENSE)
for arg in "$@"; do
  case "$arg" in
    --no-fetch) fetch=0 ;;
    -*) die "unknown option: $arg (usage: scripts/upstream-diff.sh [--no-fetch] [extra/path ...])" ;;
    *) paths+=("$arg") ;;
  esac
done

[ -f "$PIN_FILE" ] && [ -r "$PIN_FILE" ] || die "cannot read pin file: $PIN_FILE"
count=$(grep -cE "$PIN_RE" "$PIN_FILE" || true)
[ "$count" = "1" ] \
  || die "expected exactly one 'Pinned SHA: <40-hex>' line in $PIN_FILE, found $count"
line=$(grep -E "$PIN_RE" "$PIN_FILE")
pin=${line#Pinned SHA: }
pin=${pin%%[[:space:]]*}
short=${pin:0:7}

REPO_ROOT=$(git -C "$ANDROID_DIR" rev-parse --show-toplevel) \
  || die "$ANDROID_DIR is not inside a git repository"
git -C "$REPO_ROOT" remote get-url upstream >/dev/null 2>&1 \
  || die "no 'upstream' remote. Add it: git remote add upstream https://github.com/perezjuanj/OpenCircuit.git"

if [ "$fetch" = 1 ]; then
  git -C "$REPO_ROOT" fetch --quiet upstream \
    || die "git fetch upstream failed (offline? retry with --no-fetch)"
fi
git -C "$REPO_ROOT" rev-parse --verify --quiet "refs/remotes/upstream/master^{commit}" >/dev/null \
  || die "upstream/master is not available — run without --no-fetch first"
git -C "$REPO_ROOT" cat-file -e "${pin}^{commit}" 2>/dev/null \
  || die "pinned SHA $pin is not in this repo (fetch upstream first?)"

log=$(git -C "$REPO_ROOT" log --oneline "$pin..refs/remotes/upstream/master" -- "${paths[@]}")
if [ -z "$log" ]; then
  echo "no upstream changes since $short"
else
  echo "Upstream commits since $short touching: ${paths[*]}"
  echo "$log"
  echo
  git -C "$REPO_ROOT" diff --stat "$pin" refs/remotes/upstream/master -- "${paths[@]}"
fi
