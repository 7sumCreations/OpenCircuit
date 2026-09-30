# OpenCircuit for Android

A native Android (Kotlin) port of **OpenCircuit**, the local-first iOS app for the
**RingConn Gen 2, Gen 2 Air and Gen 3** smart rings. It talks to the ring directly over
Bluetooth LE, decodes every metric on the phone, and writes the results to
**Health Connect**. There is no RingConn app, no account, no cloud and no server, and it
needs no Google Play Services (the target phone runs GrapheneOS).

> **Not affiliated with, authorized, or endorsed by RingConn or JZ_Tech. Not a medical device.**
>
> "RingConn" is a trademark of its respective owner. This is an independent
> interoperability project.

## Status

**Early development, no APK yet.** The Gradle project and the `:ringkit` module
(the pure-Kotlin protocol and decoding core, ported from the iOS `OpenCircuitKit`) are
being built first. BLE, storage, Health Connect and the app UI come later. Signed APKs
will be published on GitHub Releases, installable with Obtainium, once the app can
actually sync a ring.

## Where it comes from

This port follows the upstream iOS project
[`perezjuanj/OpenCircuit`](https://github.com/perezjuanj/OpenCircuit). The exact upstream
commit it tracks, and how to check for and adopt newer upstream work, are recorded in
[`UPSTREAM.md`](UPSTREAM.md). The BLE protocol reference is upstream's
[`docs/PROTOCOL.md`](../docs/PROTOCOL.md).

## Building and testing

Requires JDK 17. From this folder (`android/`):

```sh
./gradlew --no-daemon test --rerun      # run the JVM test suite
./gradlew --no-daemon build -x test     # compile every module
scripts/upstream-diff.sh                # list upstream changes since the pinned commit
```

## Privacy

Everything stays on your phone. By design the app makes no network calls and
collects no analytics.

## License

MIT © 2026 Juan Perez, see [`LICENSE`](LICENSE). This Android port is
derived from, and credits, the original iOS app
[OpenCircuit by Juan Perez](https://github.com/perezjuanj/OpenCircuit) (`perezjuanj/OpenCircuit`).
