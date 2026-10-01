package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Kotlin-only checks that the sleep-detection port keeps what Swift's types guaranteed for free:
 * `ActivityPeriod` and `GravitySample` are `struct`s (values that cannot change under a holder),
 * `SIMD3<Float>` is a value, `findSleep`'s `inout` array is consumed in place, `Int(_:)` truncates
 * toward zero, and nothing reads the machine's time zone. Also pins every threshold to upstream's
 * literal, so a constant cannot drift silently — including every one the history decoder and
 * `DeviceStatus` already read, which must keep their names and types. Kept out of the
 * upstream-port classes so their counts stay exact.
 */
class SleepDetectionGuardTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)

    @Test
    fun everyPreExistingActivityPeriodConstantKeepsItsNameTypeAndValue() {
        // Typed locals: a renamed or retyped constant fails to compile here. Values from
        // S/Analytics/SleepDetection.swift :79, :80, :83, :84, :87, :128, :305, :313.
        val worn: Double = ActivityPeriod.WORN_MIN_TEMPERATURE_C
        val still: Float = ActivityPeriod.MOTION_STILL_THRESHOLD
        val minSleep: Duration = ActivityPeriod.MIN_SLEEP_DURATION
        val maxPause: Duration = ActivityPeriod.MAX_SLEEP_PAUSE
        val stillFraction: Float = ActivityPeriod.GRAVITY_STILL_FRACTION
        val maxGap: Duration = ActivityPeriod.GRAVITY_MAX_GAP
        val floorWindow: Duration = ActivityPeriod.MOTION_FLOOR_WINDOW
        val floorPercentile: Double = ActivityPeriod.MOTION_FLOOR_PERCENTILE
        assertEquals(28.0, worn)
        assertEquals(2f, still)
        assertEquals(Duration.ofSeconds(60 * 60), minSleep)
        assertEquals(Duration.ofSeconds(60 * 60), maxPause)
        assertEquals(0.70f, stillFraction)
        assertEquals(Duration.ofSeconds(20 * 60), maxGap)
        assertEquals(Duration.ofSeconds(30 * 60), floorWindow)
        assertEquals(0.10, floorPercentile)
        // The rolling floor the motion-channel selection measures against is still reachable.
        assertEquals(listOf(0f, 0f), ActivityPeriod.motionAboveLocalFloor(listOf(MotionSample(t0, 1f), MotionSample(t0.plusSeconds(30), 1f))))
    }

    @Test
    fun newThresholdsMatchUpstreamLiterals() {
        // S/Analytics/SleepDetection.swift :78, :81, :82, :139, :142, :145, :166, :170, :173, :300, :311.
        assertEquals(Duration.ofSeconds(15 * 60), ActivityPeriod.ACTIVITY_CHANGE_THRESHOLD)
        assertEquals(0.01f, ActivityPeriod.GRAVITY_STILL_THRESHOLD)
        assertEquals(15, ActivityPeriod.GRAVITY_WINDOW_MINUTES)
        assertEquals(25, ActivityPeriod.AWAKE_HR_MARGIN_BPM)
        assertEquals(0.10, ActivityPeriod.SLEEP_HR_FLOOR_PERCENTILE)
        assertEquals(3, ActivityPeriod.MIN_HR_SAMPLES_FOR_GATE)
        assertEquals(Duration.ofSeconds(15 * 60), ActivityPeriod.RESCUE_WINDOW)
        assertEquals(2, ActivityPeriod.RESCUE_MIN_HRV_IN_WINDOW)
        assertEquals(Duration.ofSeconds(150), ActivityPeriod.RESCUE_EPOCH_STEP)
        assertEquals(Duration.ofSeconds(120), ActivityPeriod.MOTION_GAP_SUB_SAMPLE_CORRECTION)
        assertEquals(Duration.ofSeconds(15 * 60), ActivityPeriod.MOTION_FLOOR_WINDOW_STAGING)
        // S/SleepWindow.swift :16, :141; S/BulkSleep.swift :1250; S/Analytics/SleepScore.swift :13.
        assertEquals(1440, SleepWindow.MINUTES_PER_DAY)
        assertEquals(Duration.ofSeconds(7 * 3600), SleepWindow.PRESUMED_TRUNCATED_NIGHT_SPAN)
        assertEquals(Duration.ofSeconds(3 * 150), BulkSleep.ONSET_CONTIGUITY_GAP)
        assertEquals(60 * 60 * 8, SleepScore.IDEAL_DURATION_SECONDS)
    }

    @Test
    fun activityPeriodIsAValue() {
        val p = ActivityPeriod(Activity.SLEEP, t0, t0.plusSeconds(7200))
        val q = ActivityPeriod(Activity.SLEEP, t0, t0.plusSeconds(7200))
        assertEquals(p, q)
        assertEquals(p.hashCode(), q.hashCode())
        val active = p.copy(activity = Activity.ACTIVE)
        assertEquals(Activity.SLEEP, p.activity, "copy() never changes the original")
        assertNotEquals(p, active)
        assertEquals(emptyList(), ActivityPeriod::class.java.methods.filter { it.name.startsWith("set") }.map { it.name }, "no setters")
        assertEquals(Duration.ofSeconds(7200), p.duration)
        assertEquals(Duration.ofSeconds(-7200), ActivityPeriod(Activity.SLEEP, t0.plusSeconds(7200), t0).duration, "a reversed period keeps its negative span, as upstream")
    }

    @Test
    fun gravityAndGravitySampleAreValues() {
        val g = Gravity(0f, 0f, 1f)
        assertEquals(Gravity(0f, 0f, 1f), g)
        assertEquals(GravitySample(t0, g), GravitySample(t0, Gravity(0f, 0f, 1f)))
        for (type in listOf(Gravity::class.java, GravitySample::class.java)) {
            assertEquals(emptyList(), type.methods.filter { it.name.startsWith("set") }.map { it.name }, type.simpleName)
        }
        // Equality is data-class equality, not Swift's IEEE `==` on SIMD3: these two differ from
        // upstream (see PORTING.md). Nothing in the detector compares vectors.
        assertEquals(Gravity(Float.NaN, 0f, 0f), Gravity(Float.NaN, 0f, 0f))
        assertNotEquals(Gravity(0f, 0f, 0f), Gravity(-0f, 0f, 0f))
    }

    @Test
    fun findSleepConsumesTheCallersListLikeSwiftInout() {
        val short = ActivityPeriod(Activity.SLEEP, t0, t0.plusSeconds(600))
        val night = ActivityPeriod(Activity.SLEEP, t0.plusSeconds(600), t0.plusSeconds(600 + 7200))
        val after = ActivityPeriod(Activity.ACTIVE, t0.plusSeconds(600 + 7200), t0.plusSeconds(600 + 9000))
        val events = mutableListOf(short, night, after)
        assertEquals(night, ActivityPeriod.findSleep(events))
        assertEquals(listOf(after), events, "everything up to and including the found period is removed from the caller's list")
    }

    @Test
    fun detectionNeitherAliasesNorMutatesItsInput() {
        val motion = (0 until 60).map { MotionSample(t0.plusSeconds(it * 300L), 1f) }.toMutableList()
        val snapshot = motion.toList()
        val out = ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList())
        assertEquals(snapshot, motion, "the caller's list is untouched")
        assertNotSame<Any>(motion, out)
        motion.clear()
        assertEquals(1, out.size, "a later edit to the input never changes the result")
    }

    @Test
    fun durationsTruncateTowardZeroLikeSwiftInt() {
        assertEquals(0L, Duration.ofMillis(-500).wholeSecondsTowardZero())
        assertEquals(-1L, Duration.ofMillis(-1500).wholeSecondsTowardZero())
        assertEquals(1L, Duration.ofMillis(1500).wholeSecondsTowardZero())
        assertEquals(0.0, SleepScore.score(t0.plusMillis(500), t0), "-0.5 s scores 0, not a negative second")
    }

    @Test
    fun nothingReadsTheMachineTimeZone() {
        // Run the zone-dependent functions with an extreme default zone; results must equal a run
        // under UTC, because every call names its zone explicitly.
        val ref = Instant.parse("2026-06-15T14:00:00Z")
        val utc = ZoneId.of("UTC")
        fun results(): List<Any?> = listOf(
            SleepWindow.interval(bedMinutes = 1350, wakeMinutes = 390, nightEndingNear = ref, zone = utc),
            SleepWindow.habitualInterval(listOf(ref, ref, ref), listOf(ref, ref, ref), nightEndingNear = ref, zone = utc),
            SleepWindow.isOvernightBlock(ref.minusSeconds(8 * 3600), ref.minusSeconds(3600), utc),
            SleepWindow.isOvernightBlock(ref.minusSeconds(3600), ref, onsetIsUnobserved = true, zone = utc),
        )
        val saved = TimeZone.getDefault()
        val underUtc = try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); results()
        } finally {
            TimeZone.setDefault(saved)
        }
        val underKiritimati = try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")); results()
        } finally {
            TimeZone.setDefault(saved)
        }
        assertEquals(underUtc, underKiritimati)
        assertTrue(underUtc[0] != null, "the window exists")
    }
}
