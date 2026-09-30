package io.github.opencircuit.ringkit

// Step accumulation. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/StepAccumulator.swift:58-171 (@ b1c2fdd).
//
// WHAT THE RING ACTUALLY SENDS 🟢 — the `0x10`/`0x87` descriptor's step field (`[4:6]`, 16-bit
// big-endian, `DeviceStatus.steps`) is NOT a running daily total. It is a QUARTER-HOUR BUCKET: steps
// counted since the last wall-clock `:00` / `:15` / `:30` / `:45`, cleared back to 0 at each
// boundary. Upstream re-derived this over 10,327 descriptor frames from two rings (FR02.018 in New
// York, FR04.009 in Paris): 268 drops, every one bracketing a wall-clock quarter boundary; the value
// never exceeded 746 while the same days' totals were 2,611–4,566. The clear has a ring-side settling
// lag — a descriptor up to ~2 min after a boundary can still carry the previous bucket (measured max
// 108 s).
//
// WHY THE FOLD IS WHAT IT IS — do not "fix" it. Crediting the increment while the value climbs and
// crediting `newRaw` in full when it drops is exactly "sum the observed buckets". Upstream measured
// the alternatives on the same corpus: crediting in full on any wall-clock bucket change over-counts
// by 4.9 %, and the same with a 120 s lag margin by 8.2 % — both double-count the settling lag. The
// fold's own residual is 1.3 % UNDER (a boundary crossed with no visible drop), which online is
// indistinguishable from the lag. Steps have no ring-side backlog, so an over-count is as permanent
// as an under-count. Do not re-derive a wall-clock bucket-boundary credit here.
//
// WHAT THE BUCKET DOES CHANGE — [StepAccumulator.windowStart]: a delta derived from a bucket can only
// represent steps taken inside that bucket, so the timestamped sample carrying it to Health must not
// start earlier. Upstream measured 22 of 989 credits (5.3 % of all step mass) smeared across windows
// that predated their own bucket, six of them 11–22 hours long.
//
// The wall clock is a ZONE parameter on every call: the caller passes the zone the ring's quarters
// follow, and nothing here reads the system default.

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Outcome of folding one raw counter observation into the running daily total. */
data class StepUpdate(
    /**
     * Steps to add to the SAMPLE day's running total. Never negative for counter readings
     * (0…65535), so a caller can add it without re-checking for a drop.
     */
    val deltaToAdd: Int,
    /**
     * The ring's quarter-hour bucket rolled: the raw counter dropped below the last reading, so
     * `newRaw` is the NEW bucket's count and is credited in full. 🟢 The ordinary case (~24 per
     * device-day), not an alarm; a reboot or a 16-bit wrap presents identically.
     */
    val isReset: Boolean,
)

object StepAccumulator {

    /**
     * Length of the ring's step bucket 🟢 — steps in `[4:6]` are cleared every 15 wall-clock minutes.
     * Ported verbatim from upstream (`:82`, `bucketSeconds`).
     */
    val BUCKET: Duration = Duration.ofMinutes(15)

    /**
     * How long after a boundary the ring may still report the PREVIOUS bucket 🟢 — measured max 108 s
     * upstream, rounded up to 120 s. Used only to widen [windowStart] backwards, never to credit steps.
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:88`).
     */
    val CLEAR_LAG_ALLOWANCE: Duration = Duration.ofSeconds(120)

    /**
     * Fold a freshly observed raw counter against the last one recorded.
     *
     * @param previousRaw the last raw counter persisted, or null when there is no prior reading
     *   (first run / fresh pairing / reinstall). With no baseline `newRaw` is "this bucket so far"
     *   and is credited in full — crediting 0 would drop the steps already counted in that quarter.
     * @param newRaw the counter just observed (`DeviceStatus.steps`, 0…65535 — steps so far in the
     *   ring's current quarter-hour, NOT a day total).
     * @param dayChanged the sample's calendar day differs from the day [previousRaw] was observed.
     *   Across a day boundary the readings are certainly in different buckets, so `newRaw` is
     *   credited in full. It does not recover the morning: quarters never observed are gone.
     */
    fun update(previousRaw: Int?, newRaw: Int, dayChanged: Boolean): StepUpdate {
        // No baseline — credit the current bucket so far rather than dropping it.
        val previous = previousRaw ?: return StepUpdate(deltaToAdd = newRaw, isReset = false)
        // New calendar day ⇒ certainly a new bucket: credit it whole, never subtract yesterday's baseline.
        if (dayChanged) return StepUpdate(deltaToAdd = newRaw, isReset = newRaw < previous)
        // Still climbing (or a boundary passed with no visible drop — indistinguishable from the
        // settling lag): credit only the increment, the measured-cheaper error.
        if (newRaw >= previous) return StepUpdate(deltaToAdd = newRaw - previous, isReset = false)
        // Drop ⇒ the bucket rolled (or a reboot / 16-bit wrap): the new bucket's count is credited whole.
        return StepUpdate(deltaToAdd = newRaw, isReset = true)
    }

    /**
     * Start of the wall-clock quarter-hour containing [date], in [zone].
     *
     * Computed from the local minute-of-hour and subtracted from the instant — not by flooring the
     * epoch, and not by rebuilding a local time — so it holds in `:30` / `:45` offsets and on the
     * repeated hour of a daylight-saving fall-back.
     */
    fun bucketStart(date: Instant, zone: ZoneId): Instant {
        val local = date.atZone(zone)
        val intoBucket = Duration.ofMinutes((local.minute % 15).toLong())
            .plusSeconds(local.second.toLong())
            .plusNanos(local.nano.toLong())
        return date.minus(intoBucket)
    }

    /**
     * Window START to stamp on the sample carrying `update(…).deltaToAdd` — the earliest instant
     * those steps could have been taken. A descriptor delta cannot represent a step taken before the
     * current quarter began (minus [CLEAR_LAG_ALLOWANCE], because a frame just after a boundary may
     * still report the previous bucket). So the window is the previous reading's timestamp — the
     * narrow case for a steady stream — floored to that bucket start, which is what a reconnect after
     * a gap and the day's first reading deserve.
     *
     * @param sampleDate when the descriptor arrived (the window END).
     * @param previousSampleAt when the reading `deltaToAdd` was folded against arrived; null on a day
     *   rollover or a fresh baseline.
     * @param dayStart start of [sampleDate]'s calendar day in [zone]. The window never crosses it.
     * @param zone the wall clock the ring's quarters follow.
     * @return an instant in `[dayStart, sampleDate]`.
     */
    fun windowStart(sampleDate: Instant, previousSampleAt: Instant?, dayStart: Instant, zone: ZoneId): Instant {
        val lagged = sampleDate.minus(CLEAR_LAG_ALLOWANCE)
        val floor = maxOf(dayStart, minOf(bucketStart(lagged, zone), sampleDate))
        if (previousSampleAt == null || previousSampleAt < floor || previousSampleAt > sampleDate) return floor
        return previousSampleAt
    }
}
