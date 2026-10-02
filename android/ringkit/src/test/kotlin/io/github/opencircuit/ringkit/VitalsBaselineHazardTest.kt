package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.VitalsBaseline.Config
import io.github.opencircuit.ringkit.VitalsBaseline.Direction
import io.github.opencircuit.ringkit.VitalsBaseline.Severity
import io.github.opencircuit.ringkit.VitalsBaseline.Status
import io.github.opencircuit.ringkit.VitalsBaseline.Vital
import io.github.opencircuit.ringkit.VitalsBaseline.VitalInput
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the vitals baseline, classification, fever and status report:
 * what the upstream vectors never feed in. Today's value and the prior days are STORED daily values
 * (resting HR, overnight SpO2 and HRV means) and the skin-temperature offset is computed from stored
 * nights, so here they arrive NaN or infinite; the policy `Config` arrives with zero or negative
 * windows and NaN thresholds; values sit exactly on every threshold. Kept out of the upstream-port
 * class so its count stays exact.
 *
 * The rule under test: an unreadable (NaN or infinite) reading is a missing reading — never a day
 * judged "normal" with a NaN baseline behind it, and never an alarm raised by an infinity — and no
 * policy value crashes. Every upstream outcome quoted below was measured on the pinned Swift build
 * (Swift 6.3.2); where Kotlin deliberately differs the test says so, and `PORTING.md` records why.
 */
class VitalsBaselineHazardTest {

    private val nan = Double.NaN
    private val inf = Double.POSITIVE_INFINITY
    private val prior = listOf(58.0, 60.0, 62.0, 58.0, 60.0, 62.0, 60.0) // mean 60, sd 1.5118578920369088
    private val flat = List(7) { 60.0 }
    private val alternating = listOf(-1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0) // mean 0, sd 1

    private fun assertStats(mean: Double, sd: Double, n: Int, s: VitalsBaseline.Stats?, why: String) {
        val stats = assertNotNull(s, "$why: a baseline exists")
        assertEquals(mean, stats.mean, "$why: mean")
        assertEquals(sd, stats.sd, "$why: sd")
        assertEquals(n, stats.n, "$why: n")
    }

    /** The answer upstream gives when it cannot judge (no baseline): normal, no baseline, no delta. */
    private fun assertNotJudged(c: VitalsBaseline.Classification, why: String) {
        assertEquals(Severity.NORMAL, c.severity, why)
        assertNull(c.baseline, "$why: no baseline behind the answer")
        assertEquals(0.0, c.delta, why)
        assertEquals(Direction.RISE, c.direction, why)
    }

    @Test
    fun statsTreatAnUnreadableDayAsAMissingDay() {
        // Upstream averages a NaN or infinite day in (measured: mean NaN / +Inf / -Inf, sd NaN), and a
        // NaN baseline then judges every day "normal".
        assertNull(VitalsBaseline.stats(listOf(60.0, 61.0, 62.0, 63.0, 64.0, 65.0, nan)), "six readable days are not a baseline")
        assertNull(VitalsBaseline.stats(listOf(inf, -inf, 60.0, 60.0, 60.0, 60.0, 60.0)), "five readable days are not a baseline")
        assertStats(60.0, 1.5118578920369088, 7, VitalsBaseline.stats(listOf(58.0, nan, 60.0, 62.0, inf, 58.0, 60.0, -inf, 62.0, 60.0)), "seven readable")
        // The window is the newest 30 READABLE days.
        assertStats(15.5, assertNotNull(VitalsBaseline.stats((1..30).map { it.toDouble() })).sd, 30,
            VitalsBaseline.stats((1..30).map { it.toDouble() } + List(5) { nan }), "unreadable days at the end push nothing out")
        // Property: inserting unreadable days anywhere never changes the answer (same bits).
        val rng = Random(0x5642_4831)
        repeat(500) { k ->
            val finite = List(rng.nextInt(7, 60)) { rng.nextInt(160, 400) / 4.0 }
            val dirty = finite.toMutableList()
            repeat(rng.nextInt(1, 10)) { dirty.add(rng.nextInt(0, dirty.size + 1), listOf(nan, inf, -inf)[rng.nextInt(3)]) }
            val clean = assertNotNull(VitalsBaseline.stats(finite), "case $k")
            val withHoles = assertNotNull(VitalsBaseline.stats(dirty), "case $k with unreadable days")
            assertEquals(clean.mean.toRawBits(), withHoles.mean.toRawBits(), "case $k mean")
            assertEquals(clean.sd.toRawBits(), withHoles.sd.toRawBits(), "case $k sd")
            assertEquals(clean.n, withHoles.n, "case $k n")
            assertTrue(clean.mean.isFinite() && clean.sd.isFinite() && clean.sd >= 0.0, "case $k")
        }
    }

    @Test
    fun aWindowOfZeroOrNegativeDaysHasNoBaseline() {
        // A negative maximum traps upstream ("Can't take a suffix of negative length from a
        // collection", measured); an empty window with a minimum of 0 or below averages nothing into
        // a NaN baseline of n = 0 (measured). The port answers "no baseline" for both.
        assertNull(VitalsBaseline.stats(listOf(60.0, 60.0, 60.0), Config(minBaselineDays = 0, maxBaselineDays = -1)))
        assertNull(VitalsBaseline.stats(listOf(60.0), Config(minBaselineDays = 1, maxBaselineDays = Int.MIN_VALUE)))
        assertNull(VitalsBaseline.stats(flat, Config(minBaselineDays = 0, maxBaselineDays = 0)))
        assertNull(VitalsBaseline.stats(emptyList(), Config(minBaselineDays = 0)))
        assertNull(VitalsBaseline.stats(emptyList(), Config(minBaselineDays = -3)))
        // Unchanged (measured): a minimum of 0 over two days, a minimum above the maximum, a zero maximum.
        assertStats(62.0, 1.0, 2, VitalsBaseline.stats(listOf(61.0, 63.0), Config(minBaselineDays = 0)), "two days, no minimum")
        assertNull(VitalsBaseline.stats(flat, Config(minBaselineDays = 7, maxBaselineDays = 3)))
        assertNull(VitalsBaseline.stats(flat, Config(maxBaselineDays = 0)))
        // A finite mean that overflows stays upstream's arithmetic (measured: mean +Inf, sd +Inf).
        assertStats(inf, inf, 7, VitalsBaseline.stats(List(7) { 1e308 }), "seven days of 1e308")
    }

    @Test
    fun classifyNeverJudgesAnUnreadableToday() {
        // Upstream: NaN today -> "normal" with the baseline behind it (a judged answer); +Inf -> a
        // significant resting-HR rise; -Inf -> normal for resting HR, significant for SpO2 (measured).
        for (vital in Vital.entries) {
            for (today in listOf(nan, inf, -inf)) assertNotJudged(VitalsBaseline.classify(today, prior, vital), "$vital today $today")
        }
        // An unreadable PRIOR day is missing: upstream's NaN baseline judged this 15 bpm rise "normal".
        val c = VitalsBaseline.classify(75.0, prior + nan, Vital.RESTING_HR)
        assertEquals(Severity.SIGNIFICANT, c.severity)
        assertEquals(60.0, assertNotNull(c.baseline).mean)
        assertEquals(15.0, c.delta)
        // An infinite prior day no longer turns a healthy SpO2 RISE into a "significant drop" (upstream:
        // mean +Inf, delta -Inf, significant drop).
        val spo2 = VitalsBaseline.classify(90.0, prior + inf, Vital.OVERNIGHT_SPO2)
        assertEquals(Severity.NORMAL, spo2.severity)
        assertEquals(Direction.RISE, spo2.direction)
        assertEquals(30.0, spo2.delta)
    }

    @Test
    fun classifyAtItsExactThresholds() {
        // Exactly the absolute floor counts (>=); with a zero sd the z is the largest finite double.
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.classify(65.0, flat, Vital.RESTING_HR).severity)
        assertEquals(Severity.NORMAL, VitalsBaseline.classify(64.999, flat, Vital.RESTING_HR).severity)
        // Exactly minorZ and exactly significantZ (sd 1, floor 0), measured minor and significant.
        assertEquals(Severity.MINOR, VitalsBaseline.classify(1.5, alternating, Vital.RESTING_HR, Config(minDeltaRestingHR = 0.0)).severity)
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.classify(2.5, alternating, Vital.RESTING_HR, Config(minDeltaRestingHR = 0.0)).severity)
        // Exactly the minimum history; one day short is not judged.
        assertNotNull(VitalsBaseline.classify(60.0, flat, Vital.RESTING_HR).baseline)
        assertNotJudged(VitalsBaseline.classify(75.0, flat.drop(1), Vital.RESTING_HR), "six days")
        // -0.0 today against zeros: delta -0.0, which is >= 0, so the direction is a rise (measured).
        val z = VitalsBaseline.classify(-0.0, List(7) { 0.0 }, Vital.OVERNIGHT_HRV)
        assertEquals(Severity.NORMAL, z.severity)
        assertEquals((-0.0).toRawBits(), z.delta.toRawBits())
        assertEquals(Direction.RISE, z.direction)
    }

    @Test
    fun policyValuesOutsideTheirRangeBehaveAsUpstream() {
        // A Config is caller policy, not data: NaN thresholds never flag; a negative minorZ flags any
        // concerning change past the floor (all measured).
        assertEquals(Severity.NORMAL, VitalsBaseline.classify(75.0, prior, Vital.RESTING_HR, Config(minorZ = nan, significantZ = nan)).severity)
        assertEquals(Severity.NORMAL, VitalsBaseline.classify(75.0, prior, Vital.RESTING_HR, Config(minDeltaRestingHR = nan)).severity)
        assertEquals(Severity.MINOR, VitalsBaseline.classify(61.0, alternating.map { it + 60 }, Vital.RESTING_HR, Config(minorZ = -1.0, minDeltaRestingHR = 0.0)).severity)
    }

    @Test
    fun tempSeverityPlacesNoNaNOffset() {
        // Upstream bands a NaN offset "normal" (measured). The port has no band for it; an infinite
        // offset is beyond every band, as upstream.
        assertNull(VitalsBaseline.tempSeverity(nan))
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.tempSeverity(inf))
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.tempSeverity(-inf))
        assertEquals(Severity.NORMAL, VitalsBaseline.tempSeverity(-0.0))
        assertEquals(Severity.NORMAL, VitalsBaseline.tempSeverity(Math.nextDown(0.5)))
        assertEquals(Severity.MINOR, VitalsBaseline.tempSeverity(0.5), "exactly the minor band")
        assertEquals(Severity.MINOR, VitalsBaseline.tempSeverity(-0.5))
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.tempSeverity(1.0), "exactly the significant band")
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.tempSeverity(-1.0))
    }

    @Test
    fun suspectedFeverNeedsReadableReadings() {
        // Upstream raises a fever on an infinite temperature offset or an infinite resting HR (measured
        // true for both) and misses one when a prior day is NaN (measured false).
        assertFalse(VitalsBaseline.suspectedFever(75.0, flat, skinTempOffsetC = nan))
        assertFalse(VitalsBaseline.suspectedFever(75.0, flat, skinTempOffsetC = inf))
        assertFalse(VitalsBaseline.suspectedFever(nan, flat, skinTempOffsetC = 1.2))
        assertFalse(VitalsBaseline.suspectedFever(inf, flat, skinTempOffsetC = 1.2))
        assertFalse(VitalsBaseline.suspectedFever(null, flat, skinTempOffsetC = 1.2))
        assertFalse(VitalsBaseline.suspectedFever(75.0, flat, skinTempOffsetC = null))
        assertTrue(VitalsBaseline.suspectedFever(75.0, flat + nan, skinTempOffsetC = 1.2), "the NaN day is missing; seven readable days remain")
        assertTrue(VitalsBaseline.suspectedFever(75.0, flat + -inf, skinTempOffsetC = 1.2))
        // Exactly both thresholds (>=), and a hair below either (measured).
        assertTrue(VitalsBaseline.suspectedFever(68.0, flat, skinTempOffsetC = 1.0))
        assertFalse(VitalsBaseline.suspectedFever(68.0, flat, skinTempOffsetC = Math.nextDown(1.0)))
        assertFalse(VitalsBaseline.suspectedFever(Math.nextDown(68.0), flat, skinTempOffsetC = 1.0))
    }

    @Test
    fun reportTreatsAnUnreadableReadingAsMissing() {
        fun signals(r: VitalsBaseline.Report) = r.signals.map { listOf(it.vital, it.isTemperature, it.severity, it.delta, it.direction, it.baselineMean) }
        // A NaN today contributes no signal; the temperature alone still escalates (same as upstream).
        val nanToday = VitalsBaseline.report(listOf(VitalInput(Vital.RESTING_HR, nan, flat)), skinTempOffsetC = 1.3)
        assertEquals(Status.ANOMALY, nanToday.status)
        assertEquals(listOf(listOf(null, true, Severity.SIGNIFICANT, 1.3, Direction.RISE, null)), signals(nanToday))
        assertFalse(nanToday.feverSuspected)
        // A NaN offset is no temperature (same as upstream).
        val nanOffset = VitalsBaseline.report(listOf(VitalInput(Vital.RESTING_HR, 75.0, flat)), skinTempOffsetC = nan)
        assertEquals(listOf(listOf<Any?>(Vital.RESTING_HR, false, Severity.SIGNIFICANT, 15.0, Direction.RISE, 60.0)), signals(nanOffset))
        assertFalse(nanOffset.feverSuspected)
        // An infinite offset is no temperature either: upstream raised a significant temperature
        // signal and an anomaly status from it (measured).
        val infOffset = VitalsBaseline.report(listOf(VitalInput(Vital.RESTING_HR, 60.0, flat)), skinTempOffsetC = inf)
        assertEquals(Status.NORMAL, infOffset.status)
        assertTrue(infOffset.signals.isEmpty())
        assertEquals(Status.NORMAL, VitalsBaseline.report(emptyList(), skinTempOffsetC = -inf).status)
        // An unreadable prior day no longer hides a significant SpO2 drop behind a NaN baseline.
        val spo2 = VitalsBaseline.report(listOf(VitalInput(Vital.OVERNIGHT_SPO2, 91.0, listOf(98.0, 97.0, 98.0, nan, 99.0, 97.0, 98.0, 98.0))))
        assertEquals(Status.ANOMALY, spo2.status)
        // Two resting-HR inputs: the fever check reads the first, as upstream (measured fever false).
        val two = VitalsBaseline.report(listOf(VitalInput(Vital.RESTING_HR, 60.0, flat), VitalInput(Vital.RESTING_HR, 75.0, flat)), skinTempOffsetC = 1.3)
        assertFalse(two.feverSuspected)
        assertEquals(Status.ANOMALY, two.status)
        assertEquals(2, two.signals.size)
        val empty = VitalsBaseline.report(emptyList())
        assertEquals(Status.NORMAL, empty.status)
        assertTrue(empty.signals.isEmpty() && !empty.feverSuspected)
    }
}
