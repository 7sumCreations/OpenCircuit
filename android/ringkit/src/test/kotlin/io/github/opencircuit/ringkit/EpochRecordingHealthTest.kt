package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.EpochRecordingHealth.Status
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stranded-sport-mode detector (NOT wired upstream — see the source header). Every case is the
 * proven 2026-08-16 incident or a benign shape that must NOT warn; the two known false-alarm modes
 * are pinned as they behave today.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/EpochRecordingHealthTests.swift
 * (@ b1c2fdd), all 12 tests. `now` is always an explicit instant.
 */
class EpochRecordingHealthTest {

    // :7-8
    private val now = Instant.ofEpochSecond(1_786_900_000L)
    private fun ago(seconds: Long): Instant = now.minusSeconds(seconds)
    private fun ago(d: Duration): Instant = now.minus(d)
    private val h = 3600L

    // :17 — the measured incident: descriptor seconds-fresh, newest epoch ~20 h old.
    @Test
    fun connectedRingWithNoEpochsForTwentyHoursIsStalled() {
        val s = EpochRecordingHealth.classify(newestEpochAt = ago(20 * h), newestDescriptorAt = ago(30), now = now)
        assertTrue(s.isStalled)
        assertEquals(Status.Stalled(since = ago(20 * h)), s)
        assertEquals(20, EpochRecordingHealth.stalledHours(s, now = now))
    }

    // :26
    @Test
    fun firesOncePastTheThreshold() {
        val just = EpochRecordingHealth.classify(
            newestEpochAt = ago(EpochRecordingHealth.STALE_AFTER.plusSeconds(1)),
            newestDescriptorAt = ago(30),
            now = now,
        )
        assertTrue(just.isStalled)
        val notYet = EpochRecordingHealth.classify(
            newestEpochAt = ago(EpochRecordingHealth.STALE_AFTER.minusSeconds(60)),
            newestDescriptorAt = ago(30),
            now = now,
        )
        assertEquals(Status.Recording, notYet)
    }

    // :38 — one fresh epoch clears it immediately.
    @Test
    fun resumesTheMomentAnEpochArrives() {
        assertEquals(
            Status.Recording,
            EpochRecordingHealth.classify(newestEpochAt = ago(120), newestDescriptorAt = ago(10), now = now),
        )
    }

    // :47 — a ring in a drawer must never warn; the descriptor is the discriminator.
    @Test
    fun ringLeftInADrawerIsNotStalled() {
        assertEquals(
            Status.Unknown,
            EpochRecordingHealth.classify(newestEpochAt = ago(30 * h), newestDescriptorAt = ago(29 * h), now = now),
        )
    }

    // :56 — descriptor older than the epoch ⇒ no evidence of a live-but-silent ring.
    @Test
    fun ringThatStoppedTalkingAndRecordingTogetherIsNotStalled() {
        val stopped = ago(20 * h)
        assertEquals(
            Status.Unknown,
            EpochRecordingHealth.classify(newestEpochAt = stopped, newestDescriptorAt = stopped.minusSeconds(60), now = now),
            "descriptor older than the epoch ⇒ no evidence of a live-but-silent ring",
        )
    }

    // :65 — a fresh install has no history; that is not a fault.
    @Test
    fun missingInputsAreUnknownNeverStalled() {
        assertEquals(Status.Unknown, EpochRecordingHealth.classify(newestEpochAt = null, newestDescriptorAt = ago(10), now = now))
        assertEquals(Status.Unknown, EpochRecordingHealth.classify(newestEpochAt = ago(20 * h), newestDescriptorAt = null, now = now))
        assertEquals(Status.Unknown, EpochRecordingHealth.classify(newestEpochAt = null, newestDescriptorAt = null, now = now))
    }

    // :76 — a short undrained gap is silent.
    @Test
    fun aShortUndrainedGapDoesNotWarn() {
        assertEquals(
            Status.Recording,
            EpochRecordingHealth.classify(newestEpochAt = ago(9000), newestDescriptorAt = ago(30), now = now), // 2.5 h
        )
    }

    // :84 — ring clock ahead of the phone: neither future input is infinitely stale or fresh.
    @Test
    fun futureDatedInputsDoNotWarn() {
        assertEquals(
            Status.Recording,
            EpochRecordingHealth.classify(newestEpochAt = now.plusSeconds(600), newestDescriptorAt = ago(30), now = now),
        )
        val s = EpochRecordingHealth.classify(newestEpochAt = ago(20 * h), newestDescriptorAt = now.plusSeconds(600), now = now)
        assertTrue(s.isStalled, "a future-dated descriptor is still a present ring")
    }

    // :96
    @Test
    fun stalledHoursIsNullWhenRecording() {
        assertNull(EpochRecordingHealth.stalledHours(Status.Recording, now = now))
        assertNull(EpochRecordingHealth.stalledHours(Status.Unknown, now = now))
    }

    // :110 — KNOWN DEFECT pinned as today's behaviour: an unworn ring beside the phone reads as stalled.
    @Test
    fun knownDefectUnwornRingBesideThePhoneReadsAsStalled() {
        val s = EpochRecordingHealth.classify(newestEpochAt = ago(5 * h), newestDescriptorAt = ago(30), now = now)
        assertTrue(s.isStalled, "DEFECT pinned: an unworn ring in range must NOT read as stalled")
    }

    // :120 — KNOWN DEFECT pinned as today's behaviour: a normal night reads as stalled on waking.
    @Test
    fun knownDefectNormalNightReadsAsStalledOnWaking() {
        val s = EpochRecordingHealth.classify(newestEpochAt = ago(8 * h), newestDescriptorAt = ago(30), now = now)
        assertTrue(s.isStalled, "DEFECT pinned: a healthy night must NOT read as stalled")
    }

    // :127 — never reads "0 hours".
    @Test
    fun stalledHoursNeverReadsZero() {
        val s = Status.Stalled(since = ago(EpochRecordingHealth.STALE_AFTER))
        assertTrue((EpochRecordingHealth.stalledHours(s, now = now) ?: 0) >= 1)
    }
}
