package io.github.opencircuit.ringkit

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WHEN the edited-night line appears, and WHAT IT SAYS to the character. The copy is pinned verbatim
 * because it is the one place the app tells a wearer which part of her night is her own account
 * rather than a measurement.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepEditedNightNoticeTests.swift
 * (@ b1c2fdd) — all 12 tests. Every expected sentence is typed from upstream's test source (the
 * apostrophe in "wasn’t" is U+2019, as upstream).
 */
class SleepEditedNightNoticeTest {

    /** Minutes in, as upstream's helper: both values are multiplied by 60 before the call. */
    private fun line(measured: Double, asserted: Double, health: Boolean = true): String? =
        SleepEditedNightNotice.line(measuredAsleep = measured * 60, assertedAsleep = asserted * 60, mirrorsSleepToHealth = health)

    // MARK: - Silence

    @Test
    fun silentWhenNothingIsAsserted() {
        assertNull(line(measured = 403.0, asserted = 0.0), "a fully measured night must read exactly as it does today")
    }

    @Test
    fun silentBelowOneWholeAssertedMinute() {
        assertNull(line(measured = 400.0, asserted = 0.9), "a sub-minute rounding artifact is not worth a caption")
        assertNotNull(line(measured = 400.0, asserted = 1.0), "one whole minute is the floor, inclusive")
    }

    @Test
    fun silentWhenOnlyAwakeTimeIsAsserted() {
        // The real corpus night this pins: 0.0 asserted asleep, 120.3 asserted awake. Those awake
        // segments are published like everything else (tagged user-entered), so the health store and
        // the card agree on time in bed, time asleep AND awake. A declared scope limit.
        assertNull(line(measured = 237.0, asserted = 0.0), "asserted-awake-only leaves the asleep headline in agreement with Health")
    }

    @Test
    fun silentOnTheStoreNotComputedSentinel() {
        // The store writes -1 for "not computed" — a legacy row, or one written before the provenance
        // columns existed. "We did not compute it" is not "the ring measured nothing".
        assertNull(line(measured = -1.0, asserted = 241.0), "measured -1 is the not-computed sentinel")
        assertNull(line(measured = 162.0, asserted = -1.0), "asserted -1 is the not-computed sentinel")
    }

    // MARK: - The sentence

    @Test
    fun quotesBothHalvesAndTheHealthConsequence() {
        assertEquals(
            "We kept the times you set. The ring recorded 2h 42m of the sleep above; for the other " +
                "4h 1m we have your account, not a measurement. Both reach Apple Health; your part is " +
                "marked there as entered by you.",
            line(measured = 162.0, asserted = 241.0),
        )
    }

    @Test
    fun dropsTheHealthSentenceWhenSleepIsNotBeingWrittenThere() {
        // Not reworded — DROPPED. Telling a wearer whose sleep permission is off what Apple Health
        // "gets" is an unearned claim about a surface we are not writing to.
        val text = line(measured = 162.0, asserted = 241.0, health = false)
        assertEquals(
            "We kept the times you set. The ring recorded 2h 42m of the sleep above; for the other " +
                "4h 1m we have your account, not a measurement.",
            text,
        )
        assertFalse(text?.contains("Apple Health") ?: true)
    }

    @Test
    fun nothingMeasuredGetsItsOwnSentenceRatherThanZeroMinutes() {
        assertEquals(
            "We kept the times you set. The ring wasn’t recording for any of this night, so all " +
                "4h 6m of the sleep above is your account, not a measurement. It all reaches Apple " +
                "Health, marked there as entered by you.",
            line(measured = 0.0, asserted = 246.0),
        )
        assertEquals(
            "We kept the times you set. The ring wasn’t recording for any of this night, so all " +
                "4h 6m of the sleep above is your account, not a measurement.",
            line(measured = 0.0, asserted = 246.0, health = false),
        )
    }

    @Test
    fun aSubMinuteMeasurementIsQuotedNotCalledNothing() {
        // 40 measured seconds is not "nothing". `duration` floors at a minute, so the ordinary
        // sentence is honest here and the stronger "wasn't recording for any of this night" — an
        // overstatement — is reserved for a true zero.
        assertEquals(
            "We kept the times you set. The ring recorded 1 minute of the sleep above; for the " +
                "other 4h 6m we have your account, not a measurement.",
            SleepEditedNightNotice.line(measuredAsleep = 40.0, assertedAsleep = 246.0 * 60, mirrorsSleepToHealth = false),
        )
    }

    @Test
    fun theSentenceIsGrammaticalForASubHourPluralSpan() {
        // The obvious phrasing — "the other 2 minutes IS your account" — is ungrammatical, and it is
        // reachable: any modest edit into a small hole produces a sub-hour plural. Pin both the plural
        // and the singular so a future reword cannot quietly reintroduce it.
        assertEquals(
            "We kept the times you set. The ring recorded 6h 40m of the sleep above; for the other " +
                "2 minutes we have your account, not a measurement.",
            line(measured = 400.0, asserted = 2.0, health = false),
        )
        assertEquals(
            "We kept the times you set. The ring recorded 6h 40m of the sleep above; for the other " +
                "1 minute we have your account, not a measurement.",
            line(measured = 400.0, asserted = 1.0, health = false),
        )
    }

    @Test
    fun itCreditsTheCorrectionAndNeverScolds() {
        // The wearer told us something true the ring could not see. Guard the tone against a future
        // reword that turns an acknowledgement into an accusation.
        for (text in listOf(line(measured = 162.0, asserted = 241.0)!!, line(measured = 0.0, asserted = 246.0)!!)) {
            assertTrue(text.startsWith("We kept the times you set."), text)
            assertTrue(text.contains("your account, not a measurement"), text)
            for (banned in listOf("estimate", "guess", "unverified", "invalid", "incorrect", "cannot be trusted")) {
                assertFalse(text.lowercase().contains(banned), "‘$banned’ in: $text")
            }
        }
    }

    // MARK: - Duration rendering

    @Test
    fun durationRendersAtEpochPrecisionNeverSeconds() {
        assertEquals("1 minute", SleepEditedNightNotice.duration(30.0)) // never "0 minutes"
        assertEquals("3 minutes", SleepEditedNightNotice.duration(174.0)) // 2.9 min -> 3
        assertEquals("59 minutes", SleepEditedNightNotice.duration(59.0 * 60))
        assertEquals("1 hour", SleepEditedNightNotice.duration(60.0 * 60))
        assertEquals("2 hours", SleepEditedNightNotice.duration(120.0 * 60))
        assertEquals("4h 1m", SleepEditedNightNotice.duration(241.0 * 60))
        assertEquals("2h 42m", SleepEditedNightNotice.duration(162.0 * 60))
    }

    // MARK: - Arithmetic a wearer can check against the headline

    /**
     * The card prints ONE headline; this line prints its two parts, each rounded to the minute
     * independently. A wearer who adds them must land on the headline — or at worst one minute off,
     * which is unavoidable at 150 s epoch granularity and inside the precision the copy claims. Pinned
     * so that bound is KNOWN rather than accidental: if a reword ever quotes seconds, or truncates
     * instead of rounding, this drifts past a minute and fails.
     */
    @Test
    fun theQuotedSpansReconcileWithTheHeadlineToWithinOneMinute() {
        var worst = 0L
        var measured = 0.0
        while (measured <= 8.0 * 3600) {
            for (asserted in listOf(61.0, 149.0, 150.0, 375.0, 3_600.0, 14_484.0, 14_586.0)) {
                val m = roundHalfAwayFromZero(measured / 60).toLong()
                val a = roundHalfAwayFromZero(asserted / 60).toLong()
                val headline = roundHalfAwayFromZero((measured + asserted) / 60).toLong()
                worst = max(worst, abs(m + a - headline))
            }
            measured += 37.0
        }
        assertTrue(worst <= 1, "the two printed spans drifted from the headline")

        // On both device nights it reconciles EXACTLY, which is what the tester would check
        // (162 / 241 and 3 / 243 are the measured split for those nights).
        assertEquals(403, 162 + 241, "first device night headline")
        assertEquals(246, 3 + 243, "second device night headline")
    }
}
