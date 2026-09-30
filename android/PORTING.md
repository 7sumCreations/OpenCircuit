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
| `S/Opcodes.swift` | `b1c2fdd` | `main/Opcodes.kt` | `test/CommandTableTest.kt`, `test/FrameTest.kt` | E1 | `Opcode`, `Command`, `Transport` ported. Each command is a getter returning a new array. `Command.vibrate` arrives with `RingVibration` (E1 slice 3). Legacy auth members not ported, see D-1. Byte parameters outside 0–255 are rejected, see D-2. |
| `T/FrameTests.swift` | `b1c2fdd` | — | `test/FrameTest.kt` | E1 | 10 of 11 tests ported. `testLiveHRDecode` arrives with `LiveHR` (E1 slice 4). `testLiveHRStartSequenceShape` is replaced by a check that `Command.liveHRStart` does not exist (D-1). |
| `T/FrameTests.swift` `hex(_:)` helper | `b1c2fdd` | — | `test/ByteHex.kt` | E1 | Test-only `hex()`, `bytes()`, `toHex()`. |
| `S/RingAuth.swift` | `b1c2fdd` | `main/RingAuth.kt` | `test/RingAuthTest.kt` | E1 | `RingAuth` and `SM3` ported whole. SM3 works on `Int` words with logical (`ushr`) right shifts. Upstream `:49-52` repeats the condition at `:46` and can never run, so it is left out; behaviour is unchanged. The challenge must be 0–255, see D-2. |
| `Sources/RingKitVerify/main.swift` `:264-276` (auth checks) + the 24 `knownAuthNonces` pairs in `S/Opcodes.swift:170-179` | `b1c2fdd` | — | `test/RingAuthTest.kt` | E1 | 12 tests: the `SM3("abc")` known answer, `macTailXor`, all 24 captured challenge→response pairs (count checked), `authCommand(0xb0)`, and each `macFromSystemID` layout. The 24 pairs are test data only (D-1). Adds two standard SM3 vectors upstream doesn't test (two-block and empty input). |

## Deviations & deferrals

| ID | What | Why | Where it goes |
|---|---|---|---|
| D-1 | **DEVIATION: legacy auth dropped.** `Command.status1`, `Command.liveHRStart` and `Command.authNonce(forChallenge:)` / `knownAuthNonces` (`S/Opcodes.swift:34`, `:119-120`, `:167-179`) are not in the Kotlin API. | They are fallbacks from before per-connection SM3 auth was cracked (`docs/PROTOCOL.md` §5.8). `status1` is only correct when the ring's challenge happens to be `0xb0`. Android reads the ring's MAC straight from `BluetoothDevice`, so `RingAuth` always computes the real response. | The 24 captured challenge→response pairs become test fixtures for `RingAuth` (E1 slice 2). `CommandTableTest` and `FrameTest` check that the members stay absent. |
| D-2 | **DEVIATION: out-of-range bytes rejected.** Kotlin `Command` builders and `RingAuth.response` / `RingAuth.authCommand` (the `challenge` parameter) take `Int` where Swift took `UInt8`. They throw `IllegalArgumentException` for a value outside 0–255 instead of truncating it. | Swift's type made an out-of-range byte impossible; silently truncating `0x100` to `0x00` would send the wrong command. | — (done) |
| D-3 | **DEVIATION: `Command.syncSince` never overflows.** Swift's `UInt32(clamping: unixSeconds - syncEpoch)` traps if the subtraction overflows. Kotlin compares against the epoch before subtracting, so any `Long` input clamps to `00000000`…`ffffffff`. | A plain Kotlin subtraction would wrap near `Long.MIN_VALUE` and produce a far-future cursor. Results are identical to upstream for every input upstream accepts. | — (done) |
