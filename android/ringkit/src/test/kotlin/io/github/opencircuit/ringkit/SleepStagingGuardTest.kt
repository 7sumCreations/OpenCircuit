package io.github.opencircuit.ringkit

import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Kotlin-only guards for the sleep-staging port: what Swift's types guaranteed that a Kotlin
 * stand-in does not by itself, and what Kotlin adds that Swift did not have.
 *
 * - `Tuning` was a struct of `var`s: every copy was independent. Here it is an immutable data class;
 *   a `copy(...)` variant can never change `Tuning.DEFAULT`, and every shipped default equals the
 *   literal in upstream's initializer (typed from upstream, never read from the Kotlin class — the
 *   staging differential cannot pin every constant's exact value, e.g. the cadence quiet bar).
 * - Upstream's `inout [Bool]` passes mutate the CALLER's array; here the caller owns a BooleanArray
 *   that the pass changes in place (or, when it declines, leaves exactly as it was) and nothing
 *   else the caller passed is touched.
 * - Float sentinels: a night with no in-bed time has efficiency 0, never NaN; motion derived from
 *   ring bytes is always finite and far inside `Int` range, so Kotlin's saturating `Float.toInt()`
 *   never differs from Swift's trapping `Int(_:)` on wire input.
 * - Nothing in staging reads the machine's locale or time zone.
 */
class SleepStagingGuardTest {

    private val step = 150L
    private val base = 0x0c220000L

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    private fun rec(counter: Long, hr: Int, tag: Int, hrv: Int = 0, motion: IntArray = intArrayOf(1, 1, 1, 1, 1)): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[8] = tag.toByte()
        for (k in 0 until 5) b[10 + k] = motion[k].toByte()
        return BulkRecord.of(b)!!
    }

    /**
     * A night that reaches most staging passes: a moving lead-in with HR, a still HR-elevated head,
     * jittery REM-ish and flat Deep-ish sleep in the SpO2 duty cycle, then a same-template morning
     * rise and a moving get-up.
     */
    private fun night(): List<BulkRecord> {
        val out = mutableListOf<BulkRecord>()
        var c = base
        repeat(12) { out += rec(c, hr = 76, tag = 0x62, hrv = 60, motion = intArrayOf(30, 8, 31, 9, 30)); c += step }
        repeat(4) { out += rec(c, hr = 74, tag = 0x62, hrv = 55); c += step }
        for (i in 0 until 160) {
            val hr = if ((i / 20) % 2 == 0) (if (i % 2 == 0) 56 else 62) else 50
            out += if (i % 2 == 0) rec(c, hr, tag = 0x62, hrv = if (hr == 50) 70 else 45) else rec(c, hr, tag = 0x12)
            c += step
        }
        repeat(30) { out += rec(c, hr = 64, tag = 0x62, hrv = 55); c += step }
        repeat(8) { i -> out += rec(c, hr = 90, tag = 0x12, motion = intArrayOf(0x0a, 0x30, 0x58, 0x30, 0x0a).map { it + i }.toIntArray()); c += step }
        return out
    }

    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    @Test
    fun tuningDefaultsEqualUpstreamLiterals() {
        // SleepStaging.swift:398-432 (Tuning.init's default arguments), typed from upstream.
        val t = SleepStaging.Tuning.DEFAULT
        assertEquals(15, t.awakeMotion)
        assertEquals(0.42, t.deepHRPercentile)
        assertEquals(0.86, t.remHRPercentile)
        assertEquals(0.50, t.deepVarPercentile)
        assertEquals(0.84, t.remVarPercentile)
        assertEquals(2, t.variabilityHalfWindow)
        assertEquals(2.5, t.deepVarFloor)
        assertEquals(3.0, t.remVarFloor)
        assertEquals(3, t.minDeepRunEpochs)
        assertEquals(2, t.minREMRunEpochs)
        assertEquals(1, t.minAwakeRunEpochs)
        assertEquals(0.5, t.hrvVarWeight)
        assertEquals(0.0, t.rrVarWeight)
        assertEquals(0.12, t.sleepFloorPercentile)
        assertEquals(18.0, t.wakeHRMarginBPM)
        assertEquals(2, t.hrWakeHalfWindow)
        assertEquals(3, t.motionAwakeVitalsHalfWindow)
        assertEquals(6, t.onsetSustainEpochs)
        assertEquals(5, t.minHRWakeRunEpochs)
        assertEquals(true, t.protectsLeadingHRWake)
        assertEquals(25.0, t.hrWakeRescueCeilingBPM)
        assertEquals(0.5, t.hrWakeRescueVitalsFraction)
        assertEquals(0.60, t.onsetSettleFraction)
        assertEquals(10.0, t.onsetMinDescentBPM)
        assertEquals(12, t.onsetScanEpochs)
        assertEquals(48, t.onsetSearchEpochs)
        assertEquals(0.5, t.offsetNoReturnSpreadFraction)
        assertEquals(2.0, t.offsetNoReturnMinMarginBPM)
        assertEquals(16, t.minConsolidatedSleepEpochs)
        assertEquals(18.0, t.deepBaselineMarginBPM)
        assertEquals(24, t.preOnsetBedtimeReachEpochs)
        assertEquals(5, t.preOnsetBedtimeMaxGapEpochs)
        assertEquals(20, t.cadenceWakeQuietEpochs)
        assertEquals(240, t.cadenceWakeMaxQuietEpochs)
        assertEquals(true, t.stagedWearGate)
        // Upstream has 35 knobs; a knob added without a pinned default fails here.
        val knobs = SleepStaging.Tuning::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertEquals(35, knobs.size, knobs.map { it.name }.toString())
    }

    @Test
    fun aCopiedVariantNeverChangesTheDefault() {
        val pristine = SleepStaging.Tuning()
        assertEquals(pristine, SleepStaging.Tuning.DEFAULT)
        assertSame(SleepStaging.Tuning.DEFAULT, SleepStaging.Tuning.DEFAULT, "one shared default, never rebuilt")
        val d = SleepStaging.Tuning.DEFAULT
        val variants = listOf(
            d.copy(awakeMotion = 99), d.copy(protectsLeadingHRWake = false), d.copy(stagedWearGate = false),
            d.copy(cadenceWakeQuietEpochs = 0), d.copy(offsetNoReturnSpreadFraction = 0.0), d.copy(hrWakeRescueCeilingBPM = 0.0),
            d.copy(preOnsetBedtimeReachEpochs = 0), d.copy(rrVarWeight = 0.5), d.copy(deepHRPercentile = 0.3),
        )
        for (v in variants) assertNotEquals(d, v)
        assertEquals(pristine, SleepStaging.Tuning.DEFAULT, "no copy changed the default")
        for (type in listOf(
            SleepStaging.Tuning::class.java, SleepStaging.PersonalBaseline::class.java, SleepStaging.Summary::class.java,
            SleepStaging.Minutes::class.java, SleepStaging.SleepInterval::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), type.simpleName)
        }
        // Staging with variants leaves nothing behind: the default output is the same before and after.
        val recs = night()
        val before = SleepStaging.classify(recs)
        for (v in variants) SleepStaging.classify(recs, tuning = v)
        assertEquals(before, SleepStaging.classify(recs))
        assertTrue(before.isNotEmpty(), "fixture sanity: the night stages")
    }

    @Test
    fun passesChangeOnlyTheCallersArrayAsSwiftInoutDid() {
        // Erosion: the caller's own array is changed in place.
        val awake = booleanArrayOf(false, false, true, true, false, false, false, false)
        val motionAwake = MutableList(8) { false }
        SleepStaging.erodeShortHRWake(awake, motionAwake, minRun = 5, protectsLeading = true)
        assertEquals(List(8) { false }, awake.toList(), "the interior run eroded in the caller's array")
        assertEquals(List(8) { false }, motionAwake, "the motion mask is read, never written")

        // Point-of-no-return offset: a committed cut lands in the caller's array; inputs stay as passed.
        val offset = BooleanArray(60)
        val smHR = MutableList(60) { if (it >= 40) 62.0 else 52.0 }
        val smHRBefore = smHR.toList()
        val vitals = MutableList(60) { it < 40 && it % 2 == 0 }
        val vitalsBefore = vitals.toList()
        SleepStaging.markPointOfNoReturnOffset(offset, smHR, floor = 50.0, margin = 4.0, vitals = vitals, tuning = SleepStaging.Tuning.DEFAULT)
        assertEquals((40 until 60).toList(), offset.indices.filter { offset[it] }, "the cut is visible to the caller")
        assertEquals(smHRBefore, smHR)
        assertEquals(vitalsBefore, vitals)

        // A declined pass leaves the caller's array exactly as it was (its trial copy is never committed).
        val declined = BooleanArray(60) { it in 10..12 }
        val declinedBefore = declined.copyOf()
        SleepStaging.markPointOfNoReturnOffset(declined, List(60) { 62.0 }, floor = 50.0, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT)
        assertEquals(declinedBefore.toList(), declined.toList())

        // Cadence locator: same contract.
        val cadence = MutableList(120) { SleepStaging.CadenceStep.VIOLATION }
        cadence[0] = SleepStaging.CadenceStep.UNKNOWN
        for (i in 1..99) cadence[i] = SleepStaging.CadenceStep.ALTERNATING
        val cadenceBefore = cadence.toList()
        val cut = BooleanArray(120)
        SleepStaging.markCadenceWakeOffset(cut, cadence, List(120) { if (it >= 100) 62.0 else 52.0 }, floor = 50.0, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT)
        assertEquals((100 until 120).toList(), cut.indices.filter { cut[it] })
        assertEquals(cadenceBefore, cadence)

        // classify neither reorders nor otherwise changes the caller's record list.
        val recs = night().reversed().toMutableList()
        val snapshot = recs.toList()
        SleepStaging.classify(recs)
        BulkSleep.stagedSegments(recs)
        assertEquals(snapshot, recs)
    }

    @Test
    fun floatSentinelsNeverLeakIntoTheSummary() {
        val empty = SleepStaging.summary(emptyList())
        assertEquals(0.0, empty.efficiency, "no in-bed time → efficiency 0, never NaN")
        assertEquals(SleepStaging.Minutes(0, 0, 0, 0, 0, 0), empty.minutes)
        val t = Instant.ofEpochSecond(1_781_000_000L)
        val zeroBed = SleepStaging.summary(listOf(SleepSegment(t, t, SleepStage.IN_BED)))
        assertEquals(0.0, zeroBed.efficiency, "a zero-length in-bed span → efficiency 0, never NaN")
        assertFalse(zeroBed.efficiency.isNaN())
        assertEquals(Duration.ZERO, SleepStaging.totalAsleep(emptyList()))
    }

    @Test
    fun byteDerivedMotionIsAlwaysFiniteAndInsideIntRange() {
        // The largest bytes the ring can send, on each channel the staged rows can read: the per-epoch
        // magnitudes they convert with toInt() stay finite and small, so Kotlin's saturating
        // conversion and Swift's trapping `Int(_:)` agree on every ring byte.
        fun night(primary: IntArray): List<BulkRecord> = (0 until 160).map { i ->
            val b = ByteArray(BulkRecord.LENGTH)
            counterBytes(b, base + i * step)
            b[4] = 55; b[5] = 50; b[8] = if (i % 2 == 0) 0x62 else 0x12
            for (k in 0 until 5) b[10 + k] = primary[k].toByte()
            for (k in 15 until 23) b[k] = 0xFF.toByte()
            BulkRecord.of(b)!!
        }
        // Primary channel: the unsigned sum of five near-0xFF slots that differ (not a placeholder).
        val primary = night(intArrayOf(0xFF, 0xFE, 0xFF, 0xFE, 0xFF))
        assertEquals(BulkSleep.MotionSource.Primary, BulkSleep.motionSource(primary), "fixture sanity: primary channel")
        val primaryMags = BulkSleep.motionMagnitudes(primary)
        assertTrue(primaryMags.all { it.isFinite() && it >= 0f && it <= 5f * 255f }, "primary: ${primaryMags.maxOrNull()}")
        // Intensity-tail channel: five equal 0xFF slots are a placeholder, so the run reads the tail.
        val filler = night(IntArray(5) { 0xFF })
        assertTrue(BulkSleep.motionSource(filler) is BulkSleep.MotionSource.IntensityTail, "fixture sanity: tail channel")
        assertTrue(BulkSleep.motionMagnitudes(filler).all { it == 0f || it == 1f || it == 16f })
        // Decoded-magnitude channel (all twelve-bit fields at 4095): mapped onto the same 0 / 1 / 16 scale.
        assertTrue(BulkSleep.activityMagnitudeFallbackMagnitudes(primary, BulkSleep.ACTIVITY_MAGNITUDE_ACTIVE_CUT).all { it == 0f || it == 1f || it == 16f })
        SleepStaging.classify(primary) // completes; nothing traps or saturates
        SleepStaging.classify(filler)
    }

    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        val recs = night()
        fun results(): List<Any?> {
            val segs = SleepStaging.classify(recs)
            return listOf(segs, SleepStaging.summary(segs), SleepStaging.sleepWindow(segs), BulkSleep.stagedSegments(recs))
        }
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            assertTrue((reference[0] as List<*>).isNotEmpty(), "fixture sanity: the night stages")
            for ((locale, zone) in listOf(
                Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "America/St_Johns",
                Locale.forLanguageTag("tr-TR") to "Asia/Kathmandu",
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(reference, results(), "$locale / $zone")
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }
}
