# Upstream pin

This Android port follows the iOS app **OpenCircuit**. This file records exactly which upstream commit the port currently tracks, and how to check for and adopt newer upstream work.

| | |
|---|---|
| Upstream repo | [`perezjuanj/OpenCircuit`](https://github.com/perezjuanj/OpenCircuit) — MIT, © 2026 Juan Perez |
| This fork | [`cpw7776/OpenCircuit`](https://github.com/cpw7776/OpenCircuit) |
| Android work | branch `android`, Gradle root `android/` |
| Git remotes | `origin` = the fork (push) · `upstream` = `perezjuanj/OpenCircuit` (fetch-only) |

## The pin

The port matches upstream at this commit (full 40-character SHA):

Pinned SHA: b1c2fdd604d8a6bbd317d85f9d60d3414158d7f3

That line is machine-read. Two tools depend on it:

- `ringkit/src/test/kotlin/io/github/opencircuit/ringkit/UpstreamPinTest.kt` fails the build if it does not equal `RingKit.UPSTREAM_SHA`.
- `scripts/upstream-diff.sh` reads it to know where to start the upstream comparison.

Keep it as the **only** line in this file that begins with the pin label: a second one (even inside an example) makes both tools refuse to run. Use lowercase hex and the full 40 characters.

## How to check for upstream changes

Run this from `android/` at the start of every port epic:

```sh
scripts/upstream-diff.sh                    # fetch upstream, then list changes since the pin
scripts/upstream-diff.sh --no-fetch         # offline: compare against the last-fetched upstream/master
scripts/upstream-diff.sh ios/OpenCircuit    # also watch extra paths (repo-root relative)
```

By default it watches `docs/PROTOCOL.md`, `ios/OpenCircuitKit` and `LICENSE`. It prints the upstream commits and a diffstat, or `no upstream changes since <short-sha>`. It never modifies your files or the pin; the only thing it updates is the fetched `upstream/*` refs.

If it reports that there is no `upstream` remote, add one:

```sh
git remote add upstream https://github.com/perezjuanj/OpenCircuit.git
```

## How to bump the pin

1. Run `scripts/upstream-diff.sh` and read every listed commit. Protocol fixes and new confirmed (🟢) claims in `docs/PROTOCOL.md` matter most.
2. Port what applies to the Android code (each upstream source file together with its test file).
3. In **one commit**, change both:
   - the pin line above, to the new full SHA;
   - `RingKit.UPSTREAM_SHA` in `ringkit/src/main/kotlin/io/github/opencircuit/ringkit/RingKit.kt`.
4. Run `./gradlew --no-daemon test --rerun` from `android/`. `UpstreamPinTest` passes only when the two agree.
