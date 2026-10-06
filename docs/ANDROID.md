# OpenCircuit for Android — developer notes

The Android port lives in [`android/`](../android). Start with its
[`README.md`](../android/README.md) (what it is, how to build and test) and
[`PORTING.md`](../android/PORTING.md) (which upstream file each Kotlin file comes from, and every
deliberate difference). This page collects the notes about the Android code that the rest of the
project should know.

## The on-device store (`:store`)

Everything the app keeps lives in one SQLite database on the phone, opened through Room with the
bundled SQLite driver (the same SQLite build on the phone and in the JVM tests). It never leaves
the phone.

**What is stored.** Schema version 1 has eleven tables:

- the ring's raw samples (heart rate, HRV, SpO₂, temperature, steps and the rest), kept for 30
  days, and the per-metric sync position, which is written in the same transaction as the samples
  so a sync that fails part-way stores nothing and is simply repeated;
- daily step totals and their step samples, and daytime skin-temperature readings;
- sleep summaries and naps, including the edits a person makes to a night or a nap and naps they
  add by hand. A night is filed under the day it ends; a later sync replaces it only with a fuller
  staging and never overwrites a person's edit (only the edit's allowed window widens). An edit
  writes the night, its undo history and its edited sleep onset in one transaction, so a failure
  leaves all three as they were. A nap the ring detects never replaces one a person added or
  edited, and the automatic naps a saved night covers are removed in the night's own transaction;
- what a person logs: periods and headaches (an edit that only changes the notes keeps the entry's
  health-store state; a moved entry keeps its health-store sample ids), and the frozen daily
  headache-risk rows, written once per day;
- small stored values in one key-value table: the sync and alert ledgers, the ring alarm, workout
  recovery state, the energy ledger and the sleep epoch archive. A value that cannot be read loads
  as empty or unknown, never as a wrong value; an alarm that cannot be read is kept as stored until
  the person saves a new one.

The store deletes only raw ring data on its own: samples, step samples and daytime readings older
than 30 days, plus a one-time clean-up of raw samples that fail the import checks (heart rates
outside 30–220 bpm, and times before the ring's clock epoch or more than a day ahead). Nothing a
person entered is pruned. At launch the store also repairs sleep history: once, it moves nights
an older build filed under the day they started onto the day they ended — with everything kept
under the night's key, in one transaction (two nights that would land on one day stop the move and
change nothing); it fills in the measured-versus-estimated breakdown of unedited nights that lack
it (a night with no timeline stays unknown); and it restores sleep scores an edit had withheld.
Each step's outcome is reported, and a failing step does not stop the next.

**No destructive fallback, ever.** The store is never deleted or recreated to get past a problem.
If the database cannot be opened (a damaged file, a schema with no migration path, a newer schema
than the app knows), the open fails in front of the caller and the file is left exactly as it was.
No source file or build script in `android/` may use Room's destructive-migration fallback; a test
scans the whole tree for it.

**Changing the schema.** A change to what is stored is a new schema version with its own exported
JSON under `android/store/schemas/`, a migration, and a test in `StoreMigrationTest` named after
the version (`version2…`) that creates the old version from its JSON, fills it with realistic and
hand-made rows, reopens it through the app's own open path and checks that every row is still
there with every value unchanged. Guard tests fail when a committed version has no such test, when
the database version is not the highest committed one, and when a released schema file changes:
each released file is pinned by its SHA-256 in `android/store/schemas/RELEASED.sha256`. Until the
first GitHub Release, version 1 may still be re-pinned.

**Gate A.** Before shipping any schema change, run the migration test class on its own and check
that every test it declares actually ran:

```sh
cd android
scripts/store-gate-a.sh
```

It must end with `GATE A: declared N, executed N, failed 0 — PASS`. Any other line (a failure, a
skipped test, or a test that never ran) is a `FAIL` with exit status 1, and the change does not
ship. The count matters because a test can drop out without failing: JUnit 5 silently ignores a
test function that returns a value, and Gradle reports a skipped test as a success.

## Bluetooth link (`:ble`)

`:ble` is the conversation with the ring over Bluetooth LE: it finds the ring, bonds with it,
proves who it is on every connection, acknowledges the ring's data pages and reconnects when the
link drops. It uses Android's own Bluetooth APIs only (no third-party BLE library) and runs its
whole state machine on the JVM in tests, against a scripted ring. The protocol itself (frames,
auth, decoding) is `:ringkit`'s; `:ble` routes frames by their first byte and passes them on
unchanged.

**The link.** The app builds one `RingLink` per remembered ring with `RingLink(context, ring)` and
keeps it for every connection to that ring. `connect()` and `disconnect()` start and stop it;
`state` (a `LinkState`) and `info` (a `LinkInfo`) are `StateFlow`s; `frames` delivers every frame
the ring sends except the auth challenge the link answers itself; `send(command)` writes a
command. A `RememberedRing` is the ring's upper-case address, its address type and its advertised
name; the app stores it. When the app is done with a link for good (the ring is forgotten or
replaced), it ends it with `(link as? AutoCloseable)?.close()`, which closes the connection and
unregisters the link's Bluetooth receivers; a connection open at the time publishes one
`LinkTeardown` (`USER_DISCONNECTED`, with the frames nobody took counted); a closed link shows
`Idle`, answers every `send()` with `Failed(LINK_LOST)` and ignores `connect()`. A link dropped without `close()` keeps its
receivers registered for the life of the process.

**Bring-up order.** Each connection goes connect → service discovery → bond → ATT MTU exchange
(517 asked for) → notifications on, waiting until the descriptor write is confirmed → Device
Information reads (System ID, firmware, manufacturer, hardware) → `01 00 00` → the answer to the
ring's `81 00 <challenge>`, computed from the ring's MAC → the first frame other than `0x81`. The
link shows `Connecting`, `Discovering`, `PairingNeeded` (while a bond is being made), `Preparing`,
`Authenticating` and then `Authenticated`. Auth starts only once notifications are confirmed, and
a challenge that arrives before the MAC is known waits for it. Android runs one GATT operation at
a time, so every operation waits for the previous one's answer, each with its own timeout (35 s
for a direct connect, 10 s for discovery, 40 s for the bond, 5 s for the rest).

**Bonding.** The ring ignores data from a phone it is not bonded to, so the link bonds after
discovery: an already bonded ring goes on, a ring already bonding is waited for, and otherwise the
link calls `createBond()` once and waits while Android shows its pairing prompt. A bond that is
refused, falls back to "not bonded" or is not made in 40 s shows `PairingFailed(reason)` and is
not retried until the next `connect()`; at 40 s the link reads the bond state once more first, so
a bond made while its broadcast was lost lets the bring-up go on (if that read fails too, the
bond counts as not made). If Android cannot report the bond state when the link first checks it,
the connection fails and reconnects as below; a bond broadcast with a missing or unrecognised
state is ignored. Bond changes on a live connection keep `LinkInfo.bonded` current. Ten seconds with only `0x81` frames after notifications are on shows `NotStreaming` (the
ring has not accepted this phone); the first data frame clears it. Three connections in a row to
a bonded ring that Android drops within 2 s or before discovery is done show
`BondLostSuspected`: the user should forget the ring in Settings and pair again. Only drops that
Android reports count, never the link's own timeouts, and the link keeps reconnecting meanwhile.
The app never removes a bond.

**Sending.** `send()` never throws; it answers `Sent`, `Failed(reason)` or `Refused(reason)` and
writes nothing when it refuses: before `Authenticated` (`NOT_AUTHENTICATED`); the link's own auth
commands `01 00 00` and `01 01 …` (`AUTH_COMMAND_RESERVED`); any command outside the `0x01` status
family without a bond (`NOT_BONDED`); a history sync open while the ATT MTU is below 246, too small
for a whole history frame (`HISTORY_UNSAFE`, the MTU gate; `LinkInfo.historySafe`).
Acknowledgements of the ring's pages (`0x47`, `0x4C`, `0x4D`) and heartbeats (`0x11`) are the
link's job: each is written once, in arrival order, ahead of any waiting `send()`. If the
coroutine calling `send()` is cancelled while its write still waits its turn, the write is dropped
and never reaches the ring; a write already under way completes.

**One collector at a time.** `frames` and `teardowns` each take one collector at a time: a second
collection while one runs throws `IllegalStateException`. Frames wait in order while nobody
collects, and a collector that stops or is cancelled leaves what it did not take for the next.
The app should collect `frames` once and hand each frame on from there. When a connection is torn
down, the frames still waiting are dropped and counted in the `LinkTeardown` it publishes.

**Reconnecting.** A failure (a timeout, an error status, a refused call, or an unexpected error
inside the link itself) or a drop closes the connection and tries again by the ring's address, never by scanning: `Reconnecting(attempt,
delay)` after 1 s, 5 s, then 30 s, and after three failed attempts (or once the ring is plainly out
of reach) a standing connection that waits for the ring to come back (`WaitingForRing`). The count
resets once a connection has stayed up 6 s and delivered a frame. Bluetooth turning off shows
`BluetoothOff` and closes everything; turning on connects again if a connection was wanted.
Callbacks from a closed connection change nothing. An unexpected error never stops the link or
crashes the app: it fails the current connection, which then reconnects as above.

**Scanning.** `RingScanner(context).scan()` is a cold flow of `ScanUpdate`s: each collection starts
one scan and cancelling it stops the scan (Android throttles apps that start more than five scans
in 30 s). A ring matches by its name prefix or by advertising its data service. Each new ring is
reported with `Found(rings)`; 2.5 s after the latest new ring, one ring gives `Selected(ring)` and
the scan ends, several give `Choose(rings)` and the scan goes on until the app cancels it; 15 s
with no ring gives `NoRingFound`; a scan error gives `Failed(errorCode)`, and `-1` means Android
refused to start the scan. The address type comes from the scan result on Android 15 and later,
else from the address's top two bits.

**Availability.** `BTAvailability.of(permission, adapterState)` tells the connect screen what to
offer: `READY`, `POWERED_OFF` ("Turn on Bluetooth"), `DENIED` ("Allow in Settings") or
`NOT_DETERMINED` (ask for the permission). The app maps Android's permission state to
`BluetoothPermission` and the adapter's state to `AdapterState` (`null` when not read yet).

**Permissions.** The `:ble` library declares none. The app declares `BLUETOOTH_SCAN` (with
`neverForLocation`) and `BLUETOOTH_CONNECT` and holds them before scanning or connecting; a
missing or revoked permission fails the operation and never crashes the link. The link listens
for Bluetooth power and bond changes with receivers registered at run time and not exported.

**Diagnostics.** The phone is never on a cable, so a connection-details screen reads what the link
and the scanner record: `(link as? LinkDiagnostics)?.diagnostics` (the last 64 steps of the link,
with times) and `(scanner as? ScanDiagnostics)?.lastMatch` (the last matched advertisement's bytes,
address type and signal strength). Neither holds the ring's address or frame contents; the
advertisement's bytes do carry the ring's name, which ends with two bytes of its MAC, so they are
shown on the user's own screen and never logged or stored. The text form of a `RememberedRing`
or a `LinkInfo` shows neither the address nor the MAC.
They are outside the `RingLink` and `RingScanner` interfaces and may grow.

**Tests.** `:ble` publishes its test doubles as test fixtures: `FakeRingLink`, a link the test
drives by hand (states, info, frames, teardowns, scripted `send()` answers and diagnostics, with
the same one-collector rule), and `FakeGatt`, a scripted ring that fails a test the moment a
second GATT operation is started before the first one's answer. Another module's tests use them
with:

```kotlin
testImplementation(testFixtures(project(":ble")))
```
