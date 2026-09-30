# Porting ledger

This file tracks which upstream iOS files have been ported to Kotlin, where each one lives
in this project, and every place the port deliberately differs from upstream.

Upstream is [`perezjuanj/OpenCircuit`](https://github.com/perezjuanj/OpenCircuit) (MIT,
© 2026 Juan Perez). The commit the port currently tracks is recorded in
[`UPSTREAM.md`](UPSTREAM.md); that file is the only source of the pin. The `SHA` column
below records the upstream commit each row was ported from.

## Porting rule

- Port each upstream Swift source file **together with its test file**. The ported test is
  run and shown failing before the Kotlin source exists, then passing after.
- A module is done only when its ported upstream test vectors pass.
- The protocol reference is upstream's [`docs/PROTOCOL.md`](../docs/PROTOCOL.md). Where it
  disagrees with the Swift code, the protocol document wins.
- Byte values are `Int` 0–255 read through one unsigned helper; byte sequences are
  `ByteArray`; time is `java.time.Instant`. Kotlin unsigned types are not used.

Paths: upstream `S/` = `ios/OpenCircuitKit/Sources/OpenCircuitKit/`, `T/` =
`ios/OpenCircuitKit/Tests/OpenCircuitKitTests/`. Kotlin `main/` and `test/` =
`ringkit/src/{main,test}/kotlin/io/github/opencircuit/ringkit/`.

## Ported files

| Upstream file | SHA | Kotlin source | Kotlin test | Epic | Status / notes |
|---|---|---|---|---|---|
| `S/Frame.swift` | `b1c2fdd` | `main/Frame.kt` | `test/FrameTest.kt` | E1 | Ported whole. Adds the `ByteArray.u8()` unsigned read helper. `Frame.Parsed` compares by content. |
| `S/Opcodes.swift` | `b1c2fdd` | `main/Opcodes.kt` | `test/CommandTableTest.kt`, `test/FrameTest.kt` | E1 | `Opcode`, `Command`, `Transport` ported. Each command is a getter returning a new array. `Command.vibrate(pattern)` (`:95-97`) came with `RingVibration` in E1 slice 3; its test rows in `CommandTableTest` are typed from the capture list in `S/RingVibration.swift:12-15`. Legacy auth members not ported, see D-1. Byte parameters outside 0–255 are rejected, see D-2. |
| `T/FrameTests.swift` | `b1c2fdd` | — | `test/FrameTest.kt` | E1 | 10 of 11 tests ported. `testLiveHRDecode` arrives with `LiveHR` (E1 slice 4). `testLiveHRStartSequenceShape` is replaced by a check that `Command.liveHRStart` does not exist (D-1). |
| `T/FrameTests.swift` `hex(_:)` helper | `b1c2fdd` | — | `test/ByteHex.kt` | E1 | Test-only `hex()`, `bytes()`, `toHex()`. |
| `S/RingAuth.swift` | `b1c2fdd` | `main/RingAuth.kt` | `test/RingAuthTest.kt` | E1 | `RingAuth` and `SM3` ported whole. SM3 works on `Int` words with logical (`ushr`) right shifts. Upstream `:49-52` repeats the condition at `:46` and can never run, so it is left out; behaviour is unchanged. The challenge must be 0–255, see D-2. |
| `Sources/RingKitVerify/main.swift` `:264-276` (auth checks) + the 24 `knownAuthNonces` pairs in `S/Opcodes.swift:170-179` | `b1c2fdd` | — | `test/RingAuthTest.kt` | E1 | 12 tests: the `SM3("abc")` known answer, `macTailXor`, all 24 captured challenge→response pairs (count checked), `authCommand(0xb0)`, and each `macFromSystemID` layout. The 24 pairs are test data only (D-1). Adds two standard SM3 vectors upstream doesn't test (two-block and empty input). |
| `S/FirmwareInfo.swift` | `b1c2fdd` | `main/FirmwareInfo.kt` | `test/FirmwareInfoTest.kt` | E1 | Ported whole. `RingGeneration` is an enum with the display string as `rawValue`. `FirmwareInfo` is a data class with `var` fields, like upstream's struct, and `PINNED_VERSION = "FR02.018"`. |
| `T/FirmwareInfoTests.swift` | `b1c2fdd` | — | `test/FirmwareInfoTest.kt` | E1 | All 14 tests ported. Adds two: the `RingGeneration` display strings and the pinned version, and that the prefix match is case-sensitive and anchored at the start. |
| `S/RingVibration.swift` | `b1c2fdd` | `main/RingVibration.kt` | `test/RingVibrationTest.kt`, `test/CommandTableTest.kt` | E1 | `VibrationPattern`, `RingVibration.INTENSITY_BYTE` and `isSupported` ported; display strings verbatim. The frame builder is `Command.vibrate` in `Opcodes.kt`. Swift's `Codable` conformance on `VibrationPattern` is not ported: nothing in E1 stores it, and the stored form is decided by the epic that saves the setting (same reasoning as ADR E1 D2 A). |
| `T/RingAlarmTests.swift` `:24-39` | `b1c2fdd` | — | `test/RingVibrationTest.kt` | E1 | Only the two command-byte tests (`vibrateFrameMatchesTheConfirmedCapture`, `onlyGen3ExposesTheMotor`), 2 of 18. The rest is alarm scheduling, see D-4. Adds three: pattern byte values and `INTENSITY_BYTE`, the display strings, and a new array per call. |
| `S/ChargingInference.swift` | `b1c2fdd` | `main/ChargingInference.kt` | `test/ChargingInferenceTest.kt` | E1 | Ported whole. **Pulled in from E2** (ADR E1 D4) because `DeviceStatus.isCharging` delegates to it. `inferred(trend)` is true only for ≥ 2 strictly rising readings. |
| `T/ChargingInferenceTests.swift` | `b1c2fdd` | — | `test/ChargingInferenceTest.kt` | E1 | All 13 tests ported. |
| `S/Analytics/SleepDetection.swift` `:128` | `b1c2fdd` | `main/ActivityPeriod.kt` | `test/DeviceStatusTest.kt` | E1 | **Partial, constant only** (ADR E1 D4): `ActivityPeriod.WORN_MIN_TEMPERATURE_C = 28.0`, the default wear threshold of `DeviceStatus.isWorn`. `ActivityPeriod` is an `object` holding that one constant. The rest of the file, including upstream's `struct ActivityPeriod` (`:63`), is not ported, see D-6. |
| `S/DeviceStatus.swift` | `b1c2fdd` | `main/DeviceStatus.kt` | `test/DeviceStatusTest.kt` | E1 | Ported whole: `SkinTemperature` (data class with `celsius`, `fahrenheit`), `DeviceStatus.steps`, `battery`, `skinTemperature`, `isOnCharger`, `batteryVoltageMillivolts`, `CaseBattery`, `caseBattery`, `isWorn`, `isCharging`. Every byte is read with `u8()`. The descriptor check (≥ 19 bytes, opcode `0x10` or `0x87`) is one private function instead of a guard repeated in each decoder; behaviour is unchanged. `isWorn` defaults to `ActivityPeriod.WORN_MIN_TEMPERATURE_C`; `isCharging` calls `ChargingInference.inferred`. `steps`, `battery` and `skinTemperature` are checked by the `RingKitVerify` port, as upstream does. |
| `T/DeviceStatusTests.swift` `:34-214` | `b1c2fdd` | — | `test/DeviceStatusTest.kt` | E1 | 30 of 33 tests: `isWorn` 10, `isCharging` 6, `isOnCharger` 5, battery voltage 3, `caseBattery` 6, using upstream's helpers and its real `chargingFrame` / `wornFrame` captures. The other 3 need `BulkSleep`, see D-5. |

## Deviations & deferrals

| ID | What | Why | Where it goes |
|---|---|---|---|
| D-1 | **DEVIATION: legacy auth dropped.** `Command.status1`, `Command.liveHRStart` and `Command.authNonce(forChallenge:)` / `knownAuthNonces` (`S/Opcodes.swift:34`, `:119-120`, `:167-179`) are not in the Kotlin API. | They are fallbacks from before per-connection SM3 auth was cracked (`docs/PROTOCOL.md` §5.8). `status1` is only correct when the ring's challenge happens to be `0xb0`. Android reads the ring's MAC straight from `BluetoothDevice`, so `RingAuth` always computes the real response. | The 24 captured challenge→response pairs become test fixtures for `RingAuth` (E1 slice 2). `CommandTableTest` and `FrameTest` check that the members stay absent. |
| D-2 | **DEVIATION: out-of-range bytes rejected.** Kotlin `Command` builders and `RingAuth.response` / `RingAuth.authCommand` (the `challenge` parameter) take `Int` where Swift took `UInt8`. They throw `IllegalArgumentException` for a value outside 0–255 instead of truncating it. | Swift's type made an out-of-range byte impossible; silently truncating `0x100` to `0x00` would send the wrong command. | — (done) |
| D-3 | **DEVIATION: `Command.syncSince` never overflows.** Swift's `UInt32(clamping: unixSeconds - syncEpoch)` traps if the subtraction overflows. Kotlin compares against the epoch before subtracting, so any `Long` input clamps to `00000000`…`ffffffff`. | A plain Kotlin subtraction would wrap near `Long.MIN_VALUE` and produce a far-future cursor. Results are identical to upstream for every input upstream accepts. | — (done) |
| D-4 | **DEFERRED: the rest of `T/RingAlarmTests.swift`.** 16 of its 18 tests (everything after `:39`: firing, grace window, scheduling) are not ported in E1. | They test `RingAlarm`, the alarm scheduler, which is not part of the protocol core. | E20 (vibration & smart alarm), together with `S/RingAlarm.swift`. |
| D-5 | **DEFERRED: 3 of 33 tests in `T/DeviceStatusTests.swift`** (`:217-285`): `testChargingNightNotCommittedAsSleep`, `testWornNightWithStillMotionIsKeptAsSleep`, `testNoTemperatureSamplesLeavesDetectionUnchanged`, and their `rec(_:motion:sub:)` helper. | They run the sleep wear gate through `BulkRecord` and `BulkSleep`, which are not part of the protocol core. | E3 (sleep), together with `BulkSleep`. |
| D-6 | **DEFERRED: the rest of `S/Analytics/SleepDetection.swift`.** Only the `:128` constant is ported (ADR E1 D4). | Sleep detection is E3's work. `DeviceStatus.isWorn` needs only the threshold. | E3. It grows `ActivityPeriod` into upstream's `struct ActivityPeriod` (`:63`); if that becomes a `data class`, the constant moves into its `companion object` and `ActivityPeriod.WORN_MIN_TEMPERATURE_C` keeps working. |
