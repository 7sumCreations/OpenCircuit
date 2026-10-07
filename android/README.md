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

**Early development.** The protocol and decoding core (`:ringkit`, ported from the iOS
`OpenCircuitKit`), the on-device store (`:store`), the Bluetooth link (`:ble`) and a first
app (`:app`) exist. The app onboards, finds and pairs the ring, shows its connection and
battery, and takes a live heart-rate or SpO₂ reading. History sync and Health Connect come
later. Signed APKs are published as pre-releases on GitHub Releases, installable with
Obtainium: see [`../docs/ANDROID.md`](../docs/ANDROID.md), "Installing the app".

## Where it comes from

This port follows the upstream iOS project
[`perezjuanj/OpenCircuit`](https://github.com/perezjuanj/OpenCircuit). The exact upstream
commit it tracks, and how to check for and adopt newer upstream work, are recorded in
[`UPSTREAM.md`](UPSTREAM.md). The BLE protocol reference is upstream's
[`docs/PROTOCOL.md`](../docs/PROTOCOL.md).

## Building and testing

Requires JDK 17 and the Android SDK (platform 37 for the app, 36 for the libraries). Tell Gradle where the SDK is, either
with the `ANDROID_HOME` environment variable or with a `local.properties` file in this folder
holding one line, `sdk.dir=<path to your Android SDK>`. That file is machine-specific and
ignored by git; never commit it. From this folder (`android/`):

```sh
./gradlew --no-daemon test --rerun      # run the JVM test suite (one summary line per module)
./gradlew --no-daemon assembleDebug     # build the debug APK
./gradlew --no-daemon build -x test -x assembleRelease   # compile and check every module
scripts/upstream-diff.sh                # list upstream changes since the pinned commit
```

The `:store` module (the on-device database) is Kotlin Multiplatform: its tests run on the JVM
as `:store:jvmTest` (`--tests` filters go on that task), and its on-device smoke test runs on an
emulator as `:store:connectedAndroidDeviceTest`.

## Signing a release

A release APK is signed with the project's release key, which never enters git. Two things
must be in place before `./gradlew --no-daemon assembleRelease` will build:

1. A file `keystore.properties` in this folder (`android/`; it is git-ignored) with two lines:

   ```properties
   storeFile=/absolute/path/outside/the/repository/opencircuit-release.p12
   keyAlias=opencircuit-release
   ```

   Keep the keystore itself outside the repository.
2. The keystore's password in the environment variable `OPENCIRCUIT_KEYSTORE_PASSWORD` (it is
   used for both the store and the key). Fill it from a password manager for the one command,
   for example from the macOS Keychain:

   ```sh
   OPENCIRCUIT_KEYSTORE_PASSWORD=$(security find-generic-password -s opencircuit-release -w) \
     ./gradlew --no-daemon assembleRelease
   ```

If either is missing, the release build stops with "Release signing is not set up: …", naming
what is missing, before it packages anything. It never writes an unsigned release APK. Debug
builds need neither. A plain `build` or `assemble` without the key skips the release APK (it
says so in one line) and succeeds; only a release task named explicitly, such as
`assembleRelease` or `bundleRelease`, fails loud. The release APK is signed with APK Signature Schemes v2 and v3 (no v1,
no v4) and is not minified.

Check a signed APK with the Android SDK's `apksigner`:

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

At the APK's own minimum Android version (14) `apksigner` checks only the strongest scheme it
needs, so it prints `v3 … true` and `v2 … false`. Adding `--min-sdk-version 24` makes it check
the v2 signature too, and both print `true`.

## Release signing certificate

Every release APK is signed with the same certificate. Its SHA-256 digest, as `apksigner`
prints it ("Signer #1 certificate SHA-256 digest"), is:

```text
(filled in at the first release)
```

Android refuses an update signed with a different key, and Obtainium can block one too (see
[`../docs/ANDROID.md`](../docs/ANDROID.md), "Installing the app"). If an APK's digest differs
from this one, it was not built by this project: don't install it.

## Privacy

Everything stays on your phone. By design the app makes no network calls and
collects no analytics.

## License

MIT © 2026 Juan Perez, see [`LICENSE`](LICENSE). This Android port is
derived from, and credits, the original iOS app
[OpenCircuit by Juan Perez](https://github.com/perezjuanj/OpenCircuit) (`perezjuanj/OpenCircuit`).
