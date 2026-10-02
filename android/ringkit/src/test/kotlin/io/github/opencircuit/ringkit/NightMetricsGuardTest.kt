package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SleepDifferentialFixtures.bits
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin night-metrics port adds or could lose relative to Swift: the calibrated
 * constants and raw names (typed from upstream's sources), immutable value types, copies where Swift
 * copied (`[[UInt8]]` frames), the Swift sort the medians depend on, the fdlibm `cos`/`sin` that
 * keep the OSA series bit-identical to upstream, a deterministic factor order, and no read of the
 * machine's locale or time zone.
 */
class NightMetricsGuardTest {

    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }

    @Test
    fun constantsEqualUpstreamLiterals() {
        // SleepScore.swift :13, :94-97, :45-49
        assertEquals(28_800, SleepScore.IDEAL_DURATION_SECONDS)
        assertEquals(
            mapOf(
                SleepScore.Composite.Factor.TIME_ASLEEP to 0.30, SleepScore.Composite.Factor.STAGES to 0.20,
                SleepScore.Composite.Factor.EFFICIENCY to 0.20, SleepScore.Composite.Factor.HEART_RATE to 0.10,
                SleepScore.Composite.Factor.TEMPERATURE to 0.10, SleepScore.Composite.Factor.TIME_AWAKE to 0.10,
            ),
            SleepScore.FACTOR_WEIGHTS,
        )
        assertEquals(listOf(SleepScore.Tier.NEEDS_IMPROVEMENT, SleepScore.Tier.GOOD, SleepScore.Tier.EXCELLENT), listOf(69, 70, 85).map { SleepScore.Tier.of(it) })
        // SkinTempBaseline.swift :31
        assertEquals(1.0, SkinTempBaseline.NORMAL_DEVIATION_C)
        // SleepStress.swift :54-59
        assertEquals(listOf(70.0, 15.0, 15.0, 90.0), listOf(SleepStress.RESTED_RMSSD, SleepStress.STRESSED_RMSSD, SleepStress.LOW_SCORE, SleepStress.HIGH_SCORE))
        // NapDetection.swift :17, :21, :29
        assertEquals(java.time.Duration.ofMinutes(15), NapDetection.MIN_NAP_DURATION)
        assertEquals(java.time.Duration.ofHours(3), NapDetection.LONG_NAP_DURATION)
        assertEquals(0.35, NapDetection.MIN_NAP_SLEEP_VITALS_SHARE)
        // SleepDetailMetrics.swift :58
        assertEquals(0.80, SleepDetailMetrics.ACTIVE_PERCENTILE)
        // OSASpO2.swift :25-29, :88-109
        assertEquals(listOf(197, 0x48, 20), listOf(OSAWaveform.FRAME_LENGTH, OSAWaveform.OPCODE, OSAWaveform.SAMPLES_PER_CHANNEL_PER_FRAME))
        assertEquals(
            listOf(104.91, 15.18, 4.15, 0.10, 0.40, 5.0, 4.0, 0.15),
            listOf(OSASpO2.CAL_A, OSASpO2.CAL_B, OSASpO2.SAMPLE_RATE_HZ, OSASpO2.F_MIN, OSASpO2.F_MAX, OSASpO2.SNR_IR_FLOOR, OSASpO2.SNR_OTHER_FLOOR, OSASpO2.PI_IR_FLOOR_PERCENT),
        )
        assertEquals(listOf(60, 128, 64), listOf(OSASpO2.FREQ_COUNT, OSASpO2.WINDOW_LENGTH, OSASpO2.WINDOW_STEP))
        assertEquals(60, OSASpO2.FREQS.size)
        assertEquals(0.10, OSASpO2.FREQS.first())
        assertEquals(0.40, OSASpO2.FREQS.last())
    }

    @Test
    fun rawNamesAndLabelsEqualUpstream() {
        assertEquals(listOf("excellent", "good", "needsImprovement"), SleepScore.Tier.entries.map { it.rawValue })
        assertEquals(
            listOf("timeAsleep", "stages", "efficiency", "heartRate", "temperature", "timeAwake"),
            SleepScore.Composite.Factor.entries.map { it.rawValue },
        )
        assertEquals(listOf("relaxed", "normal", "medium", "high"), SleepStress.Band.entries.map { it.rawValue })
        assertEquals(listOf("Relaxed", "Normal", "Medium", "High"), SleepStress.Band.entries.map { it.label })
        assertEquals(listOf(0, 1, 2), SleepDetailMetrics.MovementLevel.entries.map { it.rawValue })
    }

    @Test
    fun valueTypesHaveNoSetters() {
        for (type in listOf(
            SleepScore.CompositeInput::class.java, SleepScore.Composite::class.java, SleepDetailMetrics.MovementEpoch::class.java,
            SleepDetailMetrics.MovementSummary::class.java, NapDetection.Nap::class.java, OSASpO2.NightSummary::class.java,
            OvernightAverages.Point::class.java,
        )) {
            assertEquals(emptyList(), noSetters(type), type.simpleName)
        }
    }

    /** Swift's `[[UInt8]]` is a value: the frames kept for a night are copies, so neither side can change the other. */
    @Test
    fun keptFramesAreCopies() {
        val frame = ByteArray(OSAWaveform.FRAME_LENGTH).also { it[0] = 0x48; it[9] = 7; it[17] = 5 }
        val kept = OSAWaveform.dominantSessionFrames(listOf(frame)).single()
        assertNotSame(frame, kept)
        kept[17] = 99
        assertEquals(5, OSAWaveform.channels(listOf(frame))[0][0], "changing a kept frame leaves the caller's frame alone")
        frame[17] = 42
        assertEquals(99, OSAWaveform.channels(listOf(kept))[0][0], "and the other way round")
    }

    /** The factors come back in declaration order — upstream's Swift Dictionary order is seeded per process. */
    @Test
    fun compositeFactorsAreInDeclarationOrder() {
        val c = SleepScore.composite(
            SleepScore.CompositeInput(totalAsleep = 7.0 * 3600, timeAwake = 1200.0, efficiency = 0.9, deep = 3600.0, light = 14_400.0, rem = 7200.0, restingHR = 50.0, tempOffsetC = -0.3),
        )
        assertEquals(SleepScore.Composite.Factor.entries, c.factors.keys.toList())
    }

    /**
     * Swift's structs copy their arrays and dictionaries; a Kotlin value that kept the caller's list
     * would change under its holder. Each list-holding result copies in and is read-only out.
     */
    @Test
    fun listHoldingResultsCopyTheCallersCollection() {
        val t0 = java.time.Instant.ofEpochSecond(1_700_000_000)
        val seg = SleepSegment(t0, t0.plusSeconds(600), SleepStage.ASLEEP_CORE)
        val segs = mutableListOf(seg)
        val nap = NapDetection.Nap(t0, t0.plusSeconds(600), segs)
        segs += seg
        assertEquals(listOf(seg), nap.segments, "a nap keeps the segments it was built with")
        assertTrue(runCatching { (nap.segments as MutableList<SleepSegment>).add(seg) }.isFailure, "and they are read-only")
        assertEquals(NapDetection.Nap(t0, t0.plusSeconds(600), listOf(seg)), nap, "equality stays structural")

        val levels = mutableListOf(0, 1, 2)
        val summary = SleepDetailMetrics.MovementSummary(levels, 1, 1, 1)
        levels[0] = 2
        assertEquals(listOf(0, 1, 2), summary.levels)
        assertEquals(SleepDetailMetrics.MovementSummary(listOf(0, 1, 2), 1, 1, 1), summary)

        val factors = linkedMapOf(SleepScore.Composite.Factor.STAGES to 0.5, SleepScore.Composite.Factor.TIME_ASLEEP to 1.0)
        val composite = SleepScore.Composite(75, SleepScore.Tier.GOOD, factors)
        factors.clear()
        assertEquals(
            listOf(SleepScore.Composite.Factor.STAGES, SleepScore.Composite.Factor.TIME_ASLEEP),
            composite.factors.keys.toList(),
            "copied, order kept",
        )
        assertTrue(runCatching { (composite.factors as MutableMap<SleepScore.Composite.Factor, Double>).clear() }.isFailure)

        assertTrue(runCatching { (OSASpO2.FREQS as MutableList<Double>)[0] = 0.0 }.isFailure, "the shared frequency grid is read-only")
    }

    /**
     * Swift's `Int` is 64-bit: a pairwise difference of two 32-bit values never wraps, and neither
     * does the sum of the two middle differences. A Kotlin `Int` subtraction or sum would.
     */
    @Test
    fun hrvShiftDifferencesDoNotWrap() {
        assertEquals(2_147_483_648.0, BulkSleep.hrvShift(listOf(Int.MAX_VALUE), listOf(-1)), "one difference past Int.MAX_VALUE")
        assertEquals(-4_294_967_295.0, BulkSleep.hrvShift(listOf(Int.MIN_VALUE), listOf(Int.MAX_VALUE)))
        // diffs 2^30+1 and 2^30+3: each fits 32 bits, their sum (2^31+4) does not.
        assertEquals(1_073_741_826.0, BulkSleep.hrvShift(listOf(1_073_741_825, 1_073_741_827), listOf(0)), "the midpoint sum")
    }

    /**
     * For finite values the Swift sort is a stable ascending sort in which -0.0 and 0.0 tie — checked
     * against Kotlin's stable `sortedWith` on seeded arrays: every size from 0 to 200 of random values,
     * and 400 arrays of up to 600 values built from ascending and descending stretches of uneven
     * length, so runs of different sizes meet and both merge directions run. Ties abound (small
     * integers, 0.0 and -0.0), so a merge that took the wrong side on a tie would reorder ±0.
     */
    @Test
    fun swiftSortMatchesAStableSortOnFiniteValues() {
        val rng = Random(20_261_001)
        val swiftLess = Comparator<Double> { a, b -> if (a < b) -1 else if (b < a) 1 else 0 }
        fun check(xs: List<Double>) =
            assertEquals(xs.sortedWith(swiftLess).map { bits(it) }, swiftSorted(xs).map { bits(it) }, "size ${xs.size}: $xs")
        fun value(v: Int): Double = if (v == 0) (if (rng.nextBoolean()) 0.0 else -0.0) else v.toDouble()
        for (size in 0..200) {
            repeat(3) { check(List(size) { listOf(0.0, -0.0, rng.nextInt(-20, 20).toDouble(), rng.nextDouble(-5.0, 5.0))[rng.nextInt(4)] }) }
        }
        repeat(400) {
            val n = rng.nextInt(0, 601)
            val xs = mutableListOf<Double>()
            while (xs.size < n) {
                val len = rng.nextInt(1, 90)
                val base = rng.nextInt(-6, 7)
                val descending = rng.nextBoolean()
                for (k in 0 until len) xs += value(if (descending) base - k / 3 else base + k / 3)
            }
            check(xs.take(n))
        }
    }

    /**
     * The OSA series of one differential night is bit-identical to upstream's Swift output. With the
     * JVM's intrinsic `Math.cos`/`Math.sin` this night carried two values one ulp off; fdlibm
     * (`StrictMath`) matches. The tolerance-checked differential would hide that drift; this does not.
     */
    @Test
    fun osaSeriesIsBitIdenticalToUpstream() {
        val night = SleepDifferentialFixtures.inputs().single { it.id == "osa-000" }
        val golden = SleepDifferentialFixtures.goldens().getValue("osa-000").single { it.startsWith("osaraw") }.split(' ').drop(1)
        val ch = OSAWaveform.channels(OSAWaveform.dominantSessionFrames(night.frames))
        val series = OSASpO2.spo2Series(ch[0], ch[1], ch[2]).map { bits(it) }
        assertTrue(golden.size > 10, "fixture sanity: the night has a series")
        assertContentEquals(golden, series)
    }

    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        val zone = ZoneId.of("America/New_York")
        val epoch = LocalDate.of(2026, 6, 15).atStartOfDay(zone).plusHours(14).toEpochSecond()
        val day = buildList {
            var c = 0L
            fun rec(motion: Int, tag: Int, hr: Int): BulkRecord {
                val b = ByteArray(23)
                b[0] = (c shr 24).toByte(); b[1] = (c shr 16).toByte(); b[2] = (c shr 8).toByte(); b[3] = c.toByte()
                b[4] = hr.toByte(); b[5] = 40; b[8] = tag.toByte()
                for (k in 10 until 15) b[k] = (if (motion == 0) 1 else motion + (k * 7) % 11).toByte()
                c += 150
                return BulkRecord.of(b)!!
            }
            repeat(10) { add(rec(20, 0x12, 80)) }
            repeat(30) { add(rec(0, 0x62, 55)) }
            repeat(10) { add(rec(20, 0x12, 80)) }
        }
        fun results(): List<Any?> {
            val segs = SleepStaging.classify(day, epoch = epoch)
            return listOf(
                NapDetection.naps(day, mainSleep = null, zone = zone, epoch = epoch),
                SleepDetailMetrics.movementSummary(day, epoch = epoch),
                SleepDetailMetrics.averageHRByStage(day, segs, epoch = epoch),
                SleepStress.overnightScore(day),
                SleepStress.stateDurations(day),
                SleepScore.composite(SleepScore.CompositeInput(25_000.0, 900.0, 0.91, 5000.0, 14_000.0, 6000.0, 52.0, 0.4)),
                OSASpO2.medianFilter(listOf(97.0, 85.0, -0.0, 0.0, 96.0), 3).map { bits(it) },
            )
        }
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            assertTrue((reference[0] as List<*>).isNotEmpty(), "fixture sanity: the day has a nap")
            for ((locale, tz) in listOf(
                Locale.forLanguageTag("ar-EG") to "Pacific/Kiritimati",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "America/St_Johns",
                Locale.forLanguageTag("tr-TR") to "Asia/Kathmandu",
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(tz))
                assertEquals(reference, results(), "$locale / $tz")
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }
}
