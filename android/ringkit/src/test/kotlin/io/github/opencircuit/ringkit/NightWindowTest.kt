package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The night the automatic history drains stay away from: which window, and when its quiet ends.
 * Upstream `refreshNightWindowIfNeeded` / `isInSleepWindow` (`ios/OpenCircuit/BLE/RingSession.swift:1497-1619`
 * @ b1c2fdd) with the E9 rules: learned from ≥ 3 of the last 14 stored nights, else 21:30→10:00;
 * past the learned wake, quiet until a descriptor shows steps or 6 h. Every expected instant below is
 * typed from the rule, not computed by the code under test.
 */
class NightWindowTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val london: ZoneId = ZoneId.of("Europe/London")

    private fun t(iso: String): Instant = Instant.parse(iso)

    /** A stored night keyed at UTC midnight of its wake day, asleep [onset] → [wake]. */
    private fun night(onset: String, wake: String): NightWindow.StoredNight {
        val w = t(wake)
        return NightWindow.StoredNight(night = w.atZone(utc).toLocalDate().atStartOfDay(utc).toInstant(), onset = t(onset), wake = w)
    }

    /** Three nights asleep 23:00 → 07:00 UTC, ending 2026-10-05..07. */
    private val threeNights = listOf(
        night("2026-10-04T23:00:00Z", "2026-10-05T07:00:00Z"),
        night("2026-10-05T23:00:00Z", "2026-10-06T07:00:00Z"),
        night("2026-10-06T23:00:00Z", "2026-10-07T07:00:00Z"),
    )

    // ---- Which window ----

    @Test
    fun withNoStoredNightTheWindowIsTheFallback2130To1000() {
        val w = assertNotNull(NightWindow.resolve(emptyList(), t("2026-10-08T02:00:00Z"), utc))
        assertFalse(w.learned)
        assertEquals(t("2026-10-07T21:30:00Z"), w.window.start)
        assertEquals(t("2026-10-08T10:00:00Z"), w.window.end)
    }

    @Test
    fun twoNightsAreNotEnoughToLearnThreeAre() {
        val now = t("2026-10-08T02:00:00Z")
        assertFalse(assertNotNull(NightWindow.resolve(threeNights.take(2), now, utc)).learned)
        val learned = assertNotNull(NightWindow.resolve(threeNights, now, utc))
        assertTrue(learned.learned)
        // Median onset 23:00 − 1 h, median wake 07:00 + 90 min.
        assertEquals(t("2026-10-07T22:00:00Z"), learned.window.start)
        assertEquals(t("2026-10-08T08:30:00Z"), learned.window.end)
        assertEquals(t("2026-10-08T07:00:00Z"), learned.earliestWake)
        assertEquals(t("2026-10-08T13:00:00Z"), learned.quietCeiling)
    }

    @Test
    fun nightsKeyedMoreThan14DaysBeforeNowDoNotTeach() {
        val old = listOf(
            night("2026-09-20T23:00:00Z", "2026-09-21T07:00:00Z"),
            night("2026-09-21T23:00:00Z", "2026-09-22T07:00:00Z"),
            night("2026-09-22T23:00:00Z", "2026-09-23T07:00:00Z"),
        )
        assertFalse(assertNotNull(NightWindow.resolve(old, t("2026-10-08T02:00:00Z"), utc)).learned)
        // Two recent nights and one keyed 2026-09-24T00:00Z: exactly 14 days before 2026-10-08T00:00Z counts, 1 ms later it does not.
        val edge = threeNights.take(2) + night("2026-09-23T23:00:00Z", "2026-09-24T07:00:00Z")
        assertTrue(assertNotNull(NightWindow.resolve(edge, t("2026-10-08T00:00:00Z"), utc)).learned)
        assertFalse(assertNotNull(NightWindow.resolve(edge, t("2026-10-08T00:00:00.001Z"), utc)).learned)
    }

    @Test
    fun nightsWithNoOnsetOrAWakeBeforeTheOnsetDoNotTeach() {
        val broken = listOf(
            NightWindow.StoredNight(t("2026-10-05T00:00:00Z"), SleepEdit.DISTANT_PAST, t("2026-10-05T07:00:00Z")),
            NightWindow.StoredNight(t("2026-10-06T00:00:00Z"), t("2026-10-06T07:00:00Z"), t("2026-10-05T23:00:00Z")),
        )
        assertFalse(assertNotNull(NightWindow.resolve(threeNights.take(1) + broken, t("2026-10-08T02:00:00Z"), utc)).learned)
    }

    // ---- Learned window: edges ----

    @Test
    fun learnedQuietStartsAtTheWindowStart() {
        assertFalse(quiet(threeNights, "2026-10-07T21:59:59.999Z"))
        assertTrue(quiet(threeNights, "2026-10-07T22:00:00Z"))
        assertTrue(quiet(threeNights, "2026-10-08T02:00:00Z"))
    }

    @Test
    fun pastTheLearnedWakeItStaysQuietUntilTheSixHourCeiling() {
        assertTrue(quiet(threeNights, "2026-10-08T06:59:59.999Z"))
        assertTrue(quiet(threeNights, "2026-10-08T07:00:00Z"))
        assertTrue(quiet(threeNights, "2026-10-08T12:59:59.999Z"))
        assertFalse(quiet(threeNights, "2026-10-08T13:00:00Z"))
        assertFalse(quiet(threeNights, "2026-10-08T18:00:00Z"))
    }

    @Test
    fun aDescriptorWithStepsPastTheLearnedWakeEndsTheQuietAtThatInstant() {
        val w = assertNotNull(NightWindow.resolve(threeNights, t("2026-10-08T07:10:00Z"), utc))
        assertTrue(w.confirmsWake(t("2026-10-08T07:10:00Z"), stepBucket = 1))
        val latch = t("2026-10-08T07:10:00Z")
        assertTrue(w.isQuiet(t("2026-10-08T07:09:59.999Z"), latch), "a latch later than now does not count yet")
        assertFalse(w.isQuiet(t("2026-10-08T07:10:00Z"), latch))
        assertFalse(w.isQuiet(t("2026-10-08T09:00:00Z"), latch))
    }

    @Test
    fun aZeroStepBucketOrStepsBeforeTheLearnedWakeConfirmNothing() {
        val w = assertNotNull(NightWindow.resolve(threeNights, t("2026-10-08T07:10:00Z"), utc))
        assertFalse(w.confirmsWake(t("2026-10-08T07:10:00Z"), stepBucket = 0))
        assertFalse(w.confirmsWake(t("2026-10-08T06:59:59.999Z"), stepBucket = 40), "a walk to the bathroom at 06:59 is still the night")
        assertTrue(w.confirmsWake(t("2026-10-08T07:00:00Z"), stepBucket = 40))
    }

    @Test
    fun yesterdaysWakeLatchDoesNotOpenTonight() {
        val yesterdayLatch = t("2026-10-07T07:30:00Z")
        val w = assertNotNull(NightWindow.resolve(threeNights, t("2026-10-08T07:30:00Z"), utc))
        assertTrue(w.isQuiet(t("2026-10-08T07:30:00Z"), yesterdayLatch))
    }

    @Test
    fun afterTheCeilingTheNextNightIsResolved() {
        val w = assertNotNull(NightWindow.resolve(threeNights, t("2026-10-08T13:00:00Z"), utc))
        assertEquals(t("2026-10-08T22:00:00Z"), w.window.start)
        assertFalse(quiet(threeNights, "2026-10-08T21:59:59.999Z"))
        assertTrue(quiet(threeNights, "2026-10-08T22:00:00Z"))
    }

    // ---- Fallback window: edges, and the night 21:30–22:00 belongs to ----

    @Test
    fun fallbackIsQuietFrom2130UntilExactly1000() {
        assertFalse(quiet(emptyList(), "2026-10-07T21:29:59.999Z"))
        assertTrue(quiet(emptyList(), "2026-10-07T21:30:00Z"))
        assertTrue(quiet(emptyList(), "2026-10-08T09:59:59.999Z"))
        assertFalse(quiet(emptyList(), "2026-10-08T10:00:00Z"))
        assertFalse(quiet(emptyList(), "2026-10-08T14:00:00Z"))
    }

    @Test
    fun fallbackBetween2130And2200IsTonightNotLastNight() {
        // The wake nearest 21:45 is this morning's 10:00 (11 h 45 min) rather than tomorrow's
        // (12 h 15 min): upstream's window there is last night's, and it reads 21:45 as not quiet.
        assertTrue(quiet(emptyList(), "2026-10-07T21:45:00Z"))
        val w = assertNotNull(NightWindow.resolve(emptyList(), t("2026-10-07T21:45:00Z"), utc))
        assertEquals(t("2026-10-08T10:00:00Z"), w.window.end)
    }

    @Test
    fun fallbackStepsDoNotEndItsQuiet() {
        val w = assertNotNull(NightWindow.resolve(emptyList(), t("2026-10-08T08:00:00Z"), utc))
        assertFalse(w.confirmsWake(t("2026-10-08T08:00:00Z"), stepBucket = 200))
        assertTrue(w.isQuiet(t("2026-10-08T08:30:00Z"), t("2026-10-08T08:00:00Z")))
    }

    // ---- DST, explicit zone ----

    @Test
    fun springForwardFallbackNightEndsTenHoursAfterLocalMidnight() {
        // Europe/London, 2026-03-29: clocks go 01:00 GMT → 02:00 BST. SleepWindow adds the wake's
        // minutes in absolute seconds to local midnight (00:00 GMT), so the wake is 10:00Z = 11:00 BST.
        val w = assertNotNull(NightWindow.resolve(emptyList(), t("2026-03-29T03:00:00Z"), london))
        assertEquals(t("2026-03-28T21:30:00Z"), w.window.start) // 21:30 GMT
        assertEquals(t("2026-03-29T10:00:00Z"), w.window.end) // 11:00 BST
        assertTrue(w.isQuiet(t("2026-03-29T09:59:59.999Z"), null))
        assertFalse(w.isQuiet(t("2026-03-29T10:00:00Z"), null))
    }

    @Test
    fun fallBackFallbackNightEndsTenHoursAfterLocalMidnight() {
        // Europe/London, 2026-10-25: clocks go 02:00 BST → 01:00 GMT. Local midnight is 23:00Z the
        // day before; ten hours on is 09:00Z = 09:00 GMT.
        val w = assertNotNull(NightWindow.resolve(emptyList(), t("2026-10-25T03:00:00Z"), london))
        assertEquals(t("2026-10-24T20:30:00Z"), w.window.start) // 21:30 BST
        assertEquals(t("2026-10-25T09:00:00Z"), w.window.end) // 09:00 GMT
        assertTrue(w.isQuiet(t("2026-10-25T08:59:59.999Z"), null))
        assertFalse(w.isQuiet(t("2026-10-25T09:00:00Z"), null))
    }

    @Test
    fun aLearnedNightAcrossSpringForwardKeepsTheSixHourCeilingInAbsoluteTime() {
        // Three GMT nights asleep 23:00 → 07:00 local (= UTC) before the change.
        val nights = listOf(
            NightWindow.StoredNight(t("2026-03-26T00:00:00Z"), t("2026-03-25T23:00:00Z"), t("2026-03-26T07:00:00Z")),
            NightWindow.StoredNight(t("2026-03-27T00:00:00Z"), t("2026-03-26T23:00:00Z"), t("2026-03-27T07:00:00Z")),
            NightWindow.StoredNight(t("2026-03-28T00:00:00Z"), t("2026-03-27T23:00:00Z"), t("2026-03-28T07:00:00Z")),
        )
        val w = assertNotNull(NightWindow.resolve(nights, t("2026-03-29T03:00:00Z"), london))
        assertTrue(w.learned)
        // Wake 08:30 local minutes added to 00:00 GMT → 08:30Z (09:30 BST); 10 h 30 min earlier.
        assertEquals(t("2026-03-29T08:30:00Z"), w.window.end)
        assertEquals(t("2026-03-28T22:00:00Z"), w.window.start)
        assertEquals(t("2026-03-29T07:00:00Z"), w.earliestWake)
        assertEquals(t("2026-03-29T13:00:00Z"), w.quietCeiling)
        assertEquals(Duration.ofHours(6), Duration.between(w.earliestWake, w.quietCeiling))
    }

    // ---- Constants, typed from the approved rule, not read from the code ----

    @Test
    fun theRulesNumbersAreTheApprovedOnes() {
        assertEquals(21 * 60 + 30, NightWindow.FALLBACK_BED_MINUTES)
        assertEquals(10 * 60, NightWindow.FALLBACK_WAKE_MINUTES)
        assertEquals(Duration.ofMinutes(90), NightWindow.WAKE_MARGIN_TRIM)
        assertEquals(Duration.ofHours(6), NightWindow.MAX_QUIET_PAST_LEARNED_WAKE)
        assertEquals(3, NightWindow.MIN_NIGHTS)
        assertEquals(14, NightWindow.LEARNING_NIGHTS)
    }

    @Test
    fun anInstantTheZoneCannotPlaceHasNoWindow() {
        assertNull(NightWindow.resolve(emptyList(), Instant.MAX, utc))
    }

    private fun quiet(nights: List<NightWindow.StoredNight>, at: String): Boolean {
        val now = t(at)
        return assertNotNull(NightWindow.resolve(nights, now, utc)).isQuiet(now, wakeConfirmedAt = null)
    }
}
