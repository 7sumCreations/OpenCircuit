package io.github.opencircuit.ringkit

// WorkoutHRGate — decide whether a live-HR snapshot is a genuinely NEW reading worth recording into a
// workout, or just the ring's last value held between polls (#45). Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/WorkoutHRGate.swift (@ b1c2fdd), whole.
//
// Why this exists: the session's live HR is a write-only-on-lock latch — it is set only when a fresh `0x15`
// frame decodes a locked HR, and is NEVER cleared while monitoring. The 0x95→0x15 path is a STATIONARY
// spot-read: under workout motion the optical PPG mostly returns warm-up / no-HR frames that don't replace
// the latch, so it freezes at the last still value (e.g. 98). A naive consumer that records the live HR
// every poll re-emits that frozen value forever — inflating the reading count and writing a flat,
// fabricated HR line to the health store, which silently breaks the "only ACTUAL decoded readings, never
// fabricate/interpolate" contract.
//
// The fix is to gate recording on the lock's TRUE capture time (`liveHRAt`), not on its mere existence.
// Pure + injectable so the "stuck at 98" regression is locked by tests.
//
// HONEST LIMIT: this distinguishes a held latch (no fresh lock) from a fresh lock. If the ring's FIRMWARE
// genuinely re-locks and re-emits the same value every poll (a stuck windowed average), each frame carries a
// fresh `liveHRAt`, so this gate admits it — that residue is a sensor-side limitation removed only by a
// continuous motion-tolerant stream (sport-mode, #90), not by app code. We deliberately do NOT value-dedupe,
// because that would also collapse a genuinely steady resting HR — hiding real data is the opposite of the
// contract.
//
// Shape notes. The freshness window is `Double` seconds (upstream's `TimeInterval`; its NaN and infinite
// values keep upstream's meaning). The lock's age is measured exactly between instants (the carried
// exact-elapsed-time rule), so a lock 6 s and 1 ns old is stale here where upstream's `Date` doubles cannot
// hold the nanosecond. `now` is required, as upstream's is.

import java.time.Instant

object WorkoutHRGate {

    /**
     * Default freshness window (seconds): a lock older than this is treated as stale (a held latch), not a
     * new reading. ~3× the ring's ~2 s poll cadence — tolerates a missed poll without admitting a
     * minutes-old value (e.g. one carried across an app-suspension gap).
     */
    const val DEFAULT_MAX_AGE: Double = 6.0

    /**
     * Should the current live-HR lock be recorded as a NEW workout sample?
     *
     * Records only when the lock is (a) present, (b) FRESH (captured within [maxAge] of [now]), (c)
     * IN-SESSION (captured at/after [sessionStart], so a pre-workout resting lock carried in via the latch
     * can't seed the session), and (d) NOT-YET-RECORDED (its capture time strictly advances past the last
     * recorded one, so a held latch re-read at the same instant is deduped). Any miss ⇒ false ⇒ the caller
     * preserves the gap (records nothing).
     *
     * @param liveHRAt capture time of the current live-HR lock (null ⇒ no lock ever).
     * @param sessionStart when the WORKOUT began (the in-session floor). Must be the workout's own start, NOT
     *   the ring's monitoring-cycle start — a workout can ride a monitoring cycle that began earlier, so
     *   flooring on the cycle start would admit a pre-workout resting lock.
     * @param lastRecordedAt capture time of the last sample the caller already recorded.
     * @param now current wall clock (injected for tests).
     * @param maxAge freshness window in seconds (default [DEFAULT_MAX_AGE]).
     */
    fun shouldRecord(
        liveHRAt: Instant?,
        sessionStart: Instant?,
        lastRecordedAt: Instant?,
        now: Instant,
        maxAge: Double = DEFAULT_MAX_AGE,
    ): Boolean {
        val at = liveHRAt ?: return false // no lock at all
        if (!(secondsBetween(at, now) <= maxAge)) return false // stale held latch
        if (sessionStart != null && at.isBefore(sessionStart)) return false // carried-in pre-workout lock
        if (lastRecordedAt != null && !at.isAfter(lastRecordedAt)) return false // already recorded this lock
        return true
    }
}
