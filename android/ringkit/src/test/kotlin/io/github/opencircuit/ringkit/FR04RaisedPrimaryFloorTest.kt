package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The raised-floor primary motion channel (RingConn Gen 2 Air / FR04 family): the `[10:15]`
 * channel never returns to the `01` baseline and holds a pedestal whose LEVEL wanders across the
 * night faster and further than the ~30-min rolling floor can track. The #184 fixed-template gate
 * correctly refuses it, so `motionSource` gains a third, floor-based reason that falls through to
 * the decoded `[15:23)` magnitudes — behind a switch that ships OFF.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/FR04RaisedPrimaryFloorTests.swift
 * (@ b1c2fdd) — all 14 tests. The three that stage a night (`:167`, `:191`, `:314`) arrived with the
 * night half of `BulkSleep`. Upstream's `latestNightRecords(from:)` reads the device's zone; here it
 * is named ([deviceZone], America/New_York). Measured on the pinned Swift build, `:314` depends on
 * that zone: it passes in New York, Chicago, Los Angeles, Buenos Aires, UTC, London and Paris, and
 * FAILS in Kolkata, Tokyo, Sydney and Honolulu, where selection keeps 354 records reaching back into
 * the awake day and nothing stages (`SleepStagingHazardTest` reproduces both outcomes).
 *
 * All data is SYNTHETIC. Every fixture number is typed from upstream. The noise generator is
 * upstream's deterministic xorshift, and the fixtures consume it in upstream's exact order (a
 * `still` record is built before the turn test chooses between them, as upstream does), so each
 * test sees the same pseudo-random night upstream sees.
 */
class FR04RaisedPrimaryFloorTest {

    private val step = BulkRecord.EPOCH_SECONDS.toLong()

    // :43 — the channel under test ships OFF, so every expectation of it asks explicitly.
    private val on = BulkSleep.MotionChannelPolicy(magnitudeChannelEnabled = true)

    /** The device zone upstream's `latestNightRecords(from:)` would read (see the class comment). */
    private val deviceZone: ZoneId = ZoneId.of("America/New_York")

    // :46-50
    private var seed: ULong = 0x2545_F491_4F6C_DD1DuL

    private fun next(bound: Int): Int {
        seed = seed xor (seed shl 13); seed = seed xor (seed shr 7); seed = seed xor (seed shl 17)
        return (seed % bound.toULong()).toInt()
    }

    // :55-73 — `magnitudes` are nibble-packed exactly as `BulkRecord.activityMagnitudes` decodes them.
    private fun record(counter: Long, hr: Int, hrv: Int, primary: List<Int>, magnitudes: List<Int>, sleepVitals: Boolean): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        b[4] = hr.toByte()
        if (sleepVitals) { b[5] = hrv.toByte(); b[7] = 120; b[8] = 96 } else { b[8] = 0x12 }
        for (i in 0 until 5) b[10 + i] = primary[i].toByte()
        val nibbles = IntArray(16)
        magnitudes.forEachIndexed { k, m ->
            val v = m.coerceIn(0, 4095)
            nibbles[k * 3] = (v shr 8) and 0x0f
            nibbles[k * 3 + 1] = (v shr 4) and 0x0f
            nibbles[k * 3 + 2] = v and 0x0f
        }
        for (i in 0 until 8) b[15 + i] = ((nibbles[i * 2] shl 4) or nibbles[i * 2 + 1]).toByte()
        return assertNotNull(BulkRecord.of(b))
    }

    // :78-84 — the FR04.011 still shape: raised, freely varying primary, all-zero magnitudes.
    private fun raisedFloorStillEpoch(c: Long, hr: Int = 52): BulkRecord {
        val levels = listOf(1, 17, 41, 45, 49, 53, 71, 84, 85, 86)
        val primary = List(5) { levels[next(levels.size)] }
        return record(c, hr, hrv = 38 + next(14), primary = primary, magnitudes = listOf(0, 0, 0, 0, 0), sleepVitals = true)
    }

    // :87-93 — a postural turn: magnitudes in the observed 100–450 band (light, not an awakening).
    private fun turnEpoch(c: Long): BulkRecord {
        val levels = listOf(41, 49, 71, 84, 86)
        val primary = List(5) { levels[next(levels.size)] }
        return record(c, hr = 58, hrv = 40 + next(10), primary = primary, magnitudes = listOf(40, 60, 30, 50, 20), sleepVitals = true)
    }

    // :96-100 — a genuine awakening: magnitudes well over 1000 in total, activity layout.
    private fun awakeEpoch(c: Long): BulkRecord {
        val primary = List(5) { 120 + next(130) }
        return record(c, hr = 84 + next(14), hrv = 0, primary = primary, magnitudes = listOf(900, 850, 1100, 700, 950), sleepVitals = false)
    }

    // :103-113 — ~9 h: an awake evening, a long still night with six turns, then the morning.
    private fun fr04011Night(): List<BulkRecord> {
        var c = 0x0c60_0000L
        val out = mutableListOf<BulkRecord>()
        repeat(12) { out += awakeEpoch(c); c += step }
        for (i in 0 until 180) { out += if (i % 30 == 17) turnEpoch(c) else raisedFloorStillEpoch(c); c += step }
        repeat(12) { out += awakeEpoch(c); c += step }
        return out
    }

    // :116-127 — the classic Gen-2 night the fix must NOT touch: an `01` baseline that reads still everywhere.
    private fun baselineNight(): List<BulkRecord> {
        var c = 0x0c60_0000L
        val out = mutableListOf<BulkRecord>()
        repeat(12) { out += awakeEpoch(c); c += step }
        for (i in 0 until 180) {
            val still = record(c, hr = 52, hrv = 45, primary = listOf(1, 1, 1, 1, 1), magnitudes = listOf(0, 0, 0, 0, 0), sleepVitals = true)
            out += if (i % 30 == 17) turnEpoch(c) else still
            c += step
        }
        repeat(12) { out += awakeEpoch(c); c += step }
        return out
    }

    /**
     * :265-287 — flat inside an epoch, wandering between epochs; optionally ~14 h of awake day first.
     * Internal (not private) so the staging guard test can replay this exact night in other zones.
     */
    internal fun wanderingPedestalNight(includeDay: Boolean): List<BulkRecord> {
        var c = 0x0c60_0000L
        val out = mutableListOf<BulkRecord>()
        if (includeDay) repeat(336) { out += awakeEpoch(c); c += step }
        repeat(12) { out += awakeEpoch(c); c += step }
        val plateaus = listOf(1, 17, 41, 45, 49, 53, 71, 84, 85, 86)
        var level = 45
        for (i in 0 until 180) {
            if (i % 4 == 0) level = plateaus[next(plateaus.size)]
            val primary = List(5) { maxOf(0, level + next(3) - 1) }
            val still = record(c, hr = 52, hrv = 38 + next(14), primary = primary, magnitudes = listOf(0, 0, 0, 0, 0), sleepVitals = true)
            out += if (i % 30 == 17) turnEpoch(c) else still
            c += step
        }
        repeat(12) { out += awakeEpoch(c); c += step }
        return out
    }

    // :367-385 — a Gen-3 drifting plateau: two values a count apart, parity rotating, 40 epochs a level.
    private fun gen3DriftingPlateauNight(): List<BulkRecord> {
        var c = 0x0c60_0000L
        val out = mutableListOf<BulkRecord>()
        repeat(12) { out += awakeEpoch(c); c += step }
        var i = 0
        for (level in listOf(16, 16, 24, 39, 39)) {
            repeat(40) {
                val primary = List(5) { k -> level + ((k + i) % 2) }
                out += record(c, hr = 52, hrv = 38 + next(14), primary = primary, magnitudes = listOf(0, 0, 0, 0, 0), sleepVitals = true)
                c += step; i += 1
            }
        }
        repeat(12) { out += awakeEpoch(c); c += step }
        return out
    }

    private fun List<BulkRecord>.worn() = filter { it.layout != BulkRecord.Layout.IDLE }

    @Test
    fun fixtureReproducesTheFieldShape() { // :131-146
        val worn = fr04011Night().worn()
        val night = worn.filter { it.activityMagnitudesAreZero }
        assertTrue(BulkSleep.medianQuietMinimum(night) >= 16, "fixture sanity: the primary floor sits off the `01` baseline")
        assertTrue(
            night.count { it.motionIsPlaceholder }.toDouble() / night.size < 0.20,
            "fixture sanity: the five sub-samples usually DIFFER, so the constant-filler branch cannot fire",
        )
        assertTrue(
            BulkSleep.slotOrderConsistency(night) < BulkSleep.DEGENERATE_MIN_SLOT_ORDER_FRACTION,
            "fixture sanity: this is NOT the #184 fixed template — the ordering is free",
        )
        assertFalse(BulkSleep.primaryMotionIsDegenerate(worn), "the #184 gate must keep refusing this shape")
    }

    @Test
    fun raisedFloorSelectsTheDecodedMagnitudeChannel() { // :148-152
        val recs = fr04011Night()
        assertTrue(BulkSleep.primaryFloorIsRaised(recs.worn()))
        assertEquals(BulkSleep.MotionSource.ActivityMagnitudes, BulkSleep.motionSource(recs, policy = on))
    }

    @Test
    fun magnitudeChannelMapsStillTurnAndWakeOntoTheSharedScale() { // :156-163
        val mags = BulkSleep.motionMagnitudes(fr04011Night(), policy = on)
        assertEquals(16f, mags[0], "the morning/evening epochs exceed the seam")
        assertEquals(1f, mags[12 + 17], "a 200-unit postural turn is light movement, not an awakening")
        assertEquals(0f, mags[12 + 18], "a still epoch is the channel's own zero")
    }

    // (b) the night stages

    @Test
    fun raisedFloorNightStagesWithPlausibleOnsetAndEfficiency() { // :167-187
        val recs = fr04011Night()

        val segments = BulkSleep.stagedSegments(BulkSleep.latestNightRecords(recs, deviceZone, motionPolicy = on), motionPolicy = on)
        assertFalse(
            segments.isEmpty(),
            "the reported failure: every drain ended `noStagedSegments` with 0 staged segments on an archive whose vitals decoded all night",
        )

        val block = assertNotNull(BulkSleep.mainSleep(recs, motionPolicy = on))
        val firstStill = recs[12].date(Command.SYNC_EPOCH)
        assertTrue(
            Duration.between(firstStill, block.start).abs() < Duration.ofMinutes(45),
            "onset lands within minutes of the still stretch, not hours into it",
        )
        assertTrue(block.duration > Duration.ofHours(5))

        val minutes = SleepStaging.summary(SleepStaging.classify(recs, motionPolicy = on)).minutes
        assertTrue(minutes.inBed > 0)
        val efficiency = minutes.asleep.toDouble() / minutes.inBed.toDouble()
        assertTrue(efficiency > 0.70, "a night of measured stillness is not mostly awake")
        assertTrue(efficiency <= 1.0)
    }

    // (c) the classic baseline night is untouched

    @Test
    fun classicBaselineNightKeepsThePrimaryChannel() { // :191-200
        val recs = baselineNight()
        assertFalse(
            BulkSleep.primaryFloorIsRaised(recs.worn()),
            "an `01` baseline resolves stillness everywhere, so the shared `degenerateMaxQuietStillFraction` conjunct rejects it before the floor test",
        )
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
        assertFalse(
            BulkSleep.stagedSegments(BulkSleep.latestNightRecords(recs, deviceZone)).isEmpty(),
            "and it still stages, off the primary channel, exactly as before",
        )
    }

    @Test
    fun raisedFloorWithoutAZeroMagnitudePopulationStaysOnPrimary() { // :205-217
        var c = 0x0c60_0000L
        val levels = listOf(41, 49, 71, 84, 86)
        val recs = List(200) {
            record(c, hr = 52, hrv = 45, primary = List(5) { levels[next(levels.size)] },
                magnitudes = listOf(7, 3, 11, 5, 9), sleepVitals = true).also { c += step }
        }
        assertFalse(BulkSleep.primaryFloorIsRaised(recs))
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
    }

    @Test
    fun cleanMagnitudesWithABaselineFloorStayOnPrimary() { // :220-238
        var c = 0x0c60_0000L
        val recs = List(200) { i ->
            // The baseline slot ROTATES, or slot 0 would be a phase-locked minimum the template gate claims first.
            val base = listOf(1) + List(4) { 1 + next(90) }
            val primary = List(5) { base[(it + i) % 5] }
            record(c, hr = 52, hrv = 45, primary = primary,
                magnitudes = if (i % 25 == 0) listOf(40, 60, 30, 50, 20) else listOf(0, 0, 0, 0, 0), sleepVitals = true)
                .also { c += step }
        }
        assertEquals(1, BulkSleep.medianQuietMinimum(recs.filter { it.activityMagnitudesAreZero }))
        assertFalse(BulkSleep.primaryFloorIsRaised(recs))
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
    }

    @Test
    fun shortRunIsNeverJudged() { // :241-249
        var c = 0x0c60_0000L
        val recs = List(20) { raisedFloorStillEpoch(c).also { c += step } }
        assertFalse(BulkSleep.primaryFloorIsRaised(recs))
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
    }

    @Test
    fun wanderingPedestalFixtureMatchesTheFieldStatistics() { // :289-299
        val worn = wanderingPedestalNight(includeDay = false).worn()
        val quiet = worn.filter { it.activityMagnitudesAreZero }
        val stillShare = quiet.count { it.motionResolvesStillness }.toDouble() / quiet.size
        assertTrue(
            stillShare > BulkSleep.DEGENERATE_MAX_QUIET_STILL_FRACTION,
            "fixture sanity: the ring's motionless epochs are FLAT inside the epoch, so the intra-epoch proxy says still",
        )
        assertTrue(BulkSleep.medianQuietMinimum(quiet) >= 16)
        assertFalse(BulkSleep.primaryMotionIsDegenerate(worn))
    }

    @Test
    fun wanderingPedestalSelectsTheMagnitudeChannelAtEveryScope() { // :301-312
        for (includeDay in listOf(false, true)) {
            val recs = wanderingPedestalNight(includeDay)
            assertTrue(
                BulkSleep.primaryFloorIsRaised(recs.worn()),
                "includeDay=$includeDay: the de-floored stillness conjunct must see through a flat-but-wandering pedestal",
            )
            assertEquals(
                BulkSleep.MotionSource.ActivityMagnitudes,
                BulkSleep.motionSource(recs, policy = on),
                "includeDay=$includeDay: the verdict must not depend on how much daytime is in the archive union",
            )
        }
    }

    @Test
    fun wanderingPedestalNightStages() { // :314-323
        val recs = wanderingPedestalNight(includeDay = true)
        val segments = BulkSleep.stagedSegments(BulkSleep.latestNightRecords(recs, deviceZone, motionPolicy = on), motionPolicy = on)
        assertFalse(
            segments.isEmpty(),
            "the reported failure: `noStagedSegments` on every drain while HR/HRV/RR/SpO2 decoded all night",
        )
        val block = assertNotNull(BulkSleep.mainSleep(recs, motionPolicy = on))
        assertTrue(block.duration > Duration.ofHours(5))
    }

    @Test
    fun magnitudeChannelIsOffByDefault() { // :332-345
        assertFalse(BulkSleep.ACTIVITY_MAGNITUDE_CHANNEL_ENABLED, "the channel must ship OFF until a LABELLED Gen 2 Air night exists")
        assertEquals(false, BulkSleep.MotionChannelPolicy.DEFAULT.magnitudeChannelEnabled)
        for (recs in listOf(fr04011Night(), wanderingPedestalNight(includeDay = true))) {
            assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs), "the raised-floor shape stays on the primary channel at the default")
            assertNull(
                BulkSleep.secondaryChannelMagnitudes(recs),
                "a null secondary is what `motionTimeline` uses to fall back to raw `[10:15]`",
            )
        }
    }

    @Test
    fun gen3DriftingPlateauStaysOnPrimaryEvenWithTheChannelEnabled() { // :387-419
        val recs = gen3DriftingPlateauNight()
        val worn = recs.worn()
        val quiet = worn.filter { it.activityMagnitudesAreZero }
        assertFalse(worn.all { it.motionIsPlaceholder }, "fixture sanity: an all-equal run never reaches the raised-floor branch")
        assertFalse(BulkSleep.primaryMotionIsDegenerate(worn))
        assertTrue(quiet.size >= BulkSleep.DEGENERATE_MIN_QUIET_EPOCHS, "fixture sanity: the quorum must be met")
        assertTrue(
            BulkSleep.medianQuietMinimum(quiet) >= BulkSleep.RAISED_FLOOR_MIN_MEDIAN_QUIET_MINIMUM,
            "fixture sanity: the floor test on its own ACCEPTS a Gen-3 plateau",
        )
        val stillAfterFloor = BulkSleep.primaryChannelIsStillAfterFloor(worn)
        val stillShare = worn.indices.count { worn[it].activityMagnitudesAreZero && stillAfterFloor[it] }.toDouble() / quiet.size
        assertTrue(
            stillShare >= BulkSleep.DEGENERATE_MAX_QUIET_STILL_FRACTION,
            "a plateau held longer than the floor window de-floors to still",
        )
        assertFalse(BulkSleep.primaryFloorIsRaised(worn))
        assertEquals(
            BulkSleep.MotionSource.Primary,
            BulkSleep.motionSource(recs, policy = on),
            "a drifting-but-trackable Gen-3 floor is a WORKING channel",
        )
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(recs))
    }

    @Test
    fun fixtureNibblePackingRoundTrips() { // :422-427
        val r = record(0x0c60_0000L, hr = 60, hrv = 45, primary = listOf(1, 1, 1, 1, 1),
            magnitudes = listOf(0, 1, 4095, 1302, 97), sleepVitals = true)
        assertEquals(listOf(0, 1, 4095, 1302, 97), r.activityMagnitudes)
        assertFalse(r.activityMagnitudesAreZero)
    }
}
