#!/usr/bin/env bash
# upstream-diff.sh — what changed upstream since the pinned SHA (read-only).
#
# Usage (from android/):  scripts/upstream-diff.sh [--no-fetch] [extra/path ...]
#   --no-fetch     skip the fetch; compare against the last-fetched upstream default branch
#                  (upstream/HEAD; upstream/master if that was never recorded)
#   extra/path     additional repo-root-relative paths to watch
#
# Compares against upstream's default branch, re-read from the remote on every fetch
# (`git remote set-head upstream --auto`), so a master -> main rename needs no edit here.
# Watched by default (repo-root relative): docs/PROTOCOL.md, ios/OpenCircuitKit, LICENSE.
# Pin source: the single "Pinned SHA: <40-hex>" line in android/UPSTREAM.md (ADR E0 D2);
# override the file with UPSTREAM_MD=<file>. This script never modifies the working tree,
# the index or the pin; the only writes are under refs/remotes/upstream (fetch + set-head).
set -euo pipefail

die() { echo "upstream-diff: $1" >&2; exit 1; }

# Physical directory holding path $1 (dirname without dirname). CDPATH= keeps cd from
# echoing the target dir into the capture when CDPATH is set; -P resolves symlinked dirs.
dir_of() {
  case "$1" in
    */*) CDPATH='' cd -P -- "${1%/*}" && pwd ;;
    *) pwd -P ;;
  esac
}

# Follow symlinks to the real script so a linked invocation still finds android/UPSTREAM.md.
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
SCRIPT_DIR=$(dir_of "$src")
ANDROID_DIR=${SCRIPT_DIR%/*}
PIN_FILE=${UPSTREAM_MD:-$ANDROID_DIR/UPSTREAM.md}
# Same contract as UpstreamPinTest's pinLine regex — change both together (ADR E0 D2).
PIN_RE='^Pinned SHA: [0-9a-f]{40}[[:space:]]*$'

fetch=1
paths=(docs/PROTOCOL.md ios/OpenCircuitKit LICENSE)
for arg in "$@"; do
  case "$arg" in
    --no-fetch) fetch=0 ;;
    -h|--help) echo "usage: scripts/upstream-diff.sh [--no-fetch] [extra/path ...]"; exit 0 ;;
    -*) die "unknown option: $arg (usage: scripts/upstream-diff.sh [--no-fetch] [extra/path ...])" ;;
    *) paths+=("$arg") ;;
  esac
done

[ -f "$PIN_FILE" ] && [ -r "$PIN_FILE" ] || die "cannot read pin file: $PIN_FILE"
count=$(grep -cE -- "$PIN_RE" "$PIN_FILE" || true)
[ "$count" = "1" ] \
  || die "expected exactly one 'Pinned SHA: <40-hex>' line in $PIN_FILE, found $count"
line=$(grep -E -- "$PIN_RE" "$PIN_FILE")
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
  # Re-ask upstream for its default branch so a rename (master -> main) is followed.
  # Writes only refs/remotes/upstream/HEAD.
  git -C "$REPO_ROOT" remote set-head upstream --auto >/dev/null \
    || die "cannot read upstream's default branch (offline? retry with --no-fetch)"
fi
# upstream/HEAD names upstream's default branch. It can be unset with --no-fetch (a remote
# added by `git remote add` gets it only on a fetch); then fall back to upstream/master if present.
if ! upstream_ref=$(git -C "$REPO_ROOT" symbolic-ref --quiet refs/remotes/upstream/HEAD); then
  git -C "$REPO_ROOT" rev-parse --verify --quiet "refs/remotes/upstream/master^{commit}" >/dev/null \
    || die "upstream's default branch is unknown — run once without --no-fetch"
  upstream_ref=refs/remotes/upstream/master
fi
branch=${upstream_ref#refs/remotes/upstream/}
git -C "$REPO_ROOT" rev-parse --verify --quiet "${upstream_ref}^{commit}" >/dev/null \
  || die "upstream/$branch is not available — run without --no-fetch first"
git -C "$REPO_ROOT" cat-file -e "${pin}^{commit}" 2>/dev/null \
  || die "pinned SHA $pin is not in this repo (fetch upstream first?)"

log=$(git -C "$REPO_ROOT" log --oneline "$pin..$upstream_ref" -- "${paths[@]}")
if [ -z "$log" ]; then
  echo "no upstream changes since $short"
else
  echo "Upstream commits since $short on upstream/$branch touching: ${paths[*]}"
  echo "$log"
  echo
  # Three dots = changes on upstream since its merge-base with the pin, matching the log range above.
  git -C "$REPO_ROOT" diff --stat "$pin...$upstream_ref" -- "${paths[@]}"
fi
