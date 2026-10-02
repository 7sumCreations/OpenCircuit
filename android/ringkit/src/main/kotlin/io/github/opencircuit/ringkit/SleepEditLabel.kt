package io.github.opencircuit.ringkit

// Supervised labels harvested from the user's own sleep edits. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepEditLabel.swift (@ b1c2fdd).
//
// WHY: sleep staging has no ground truth here, but every corrected bedtime or wake time is the sleeper
// stating ground truth about their OWN night, next to what the detector said. That pair is a label.
//
// It is a BIASED sample, and every consumer must treat it as one: people correct nights that look
// wrong and leave nights that look right. Good for "how wrong are we when we are wrong" and for
// fitting a knob that must not make those cases worse; useless as an estimate of overall accuracy.

import java.time.Duration
import java.time.Instant

/** One night where the detector's answer and the sleeper's answer are both known. */
data class SleepEditLabel(
    /** Local night key the pair belongs to. */
    val night: Instant,
    /** What the detector produced before the correction. */
    val recordedOnset: Instant? = null,
    val recordedWake: Instant? = null,
    /** What the person who slept the night says actually happened. */
    val trueOnset: Instant? = null,
    val trueWake: Instant? = null,
) {
    /** Signed onset error in MINUTES, detector minus truth (positive = onset called LATE). Null when a side is missing. */
    val onsetErrorMinutes: Double?
        get() = if (recordedOnset == null || trueOnset == null) null else minutesBetween(trueOnset, recordedOnset)

    /** Signed wake error in MINUTES, detector minus truth (positive = night held open too long). */
    val wakeErrorMinutes: Double?
        get() = if (recordedWake == null || trueWake == null) null else minutesBetween(trueWake, recordedWake)

    /** True when this label carries at least one usable edge. */
    val isUsable: Boolean get() = onsetErrorMinutes != null || wakeErrorMinutes != null

    private fun minutesBetween(from: Instant, to: Instant): Double {
        val d = Duration.between(from, to)
        return (d.seconds + d.nano / 1e9) / 60
    }
}

/** Filtering, accuracy and the fit gate over [SleepEditLabel]s. */
object SleepEditLabels {

    /**
     * Minimum correction, in minutes, for an edit to count as a label. Below it the "correction" is
     * picker friction (the editor works in whole minutes); the errors this measures are tens of minutes.
     */
    const val MINIMUM_CORRECTION_MINUTES: Double = 3.0

    /** Keep only labels that carry a real correction on at least one edge. */
    fun usable(labels: List<SleepEditLabel>, minimumMinutes: Double = MINIMUM_CORRECTION_MINUTES): List<SleepEditLabel> =
        labels.filter { label ->
            val onset = label.onsetErrorMinutes?.let { Math.abs(it) >= minimumMinutes } ?: false
            val wake = label.wakeErrorMinutes?.let { Math.abs(it) >= minimumMinutes } ?: false
            onset || wake
        }

    /**
     * How the detector is doing on the labelled nights. Every field is null when no label carries that
     * edge, rather than 0 — "no evidence" and "no error" must not read the same.
     */
    data class Accuracy(
        val count: Int,
        /** Mean SIGNED error (minutes): the systematic bias a mean-absolute error would hide. */
        val meanOnsetError: Double?,
        val meanWakeError: Double?,
        /** Median ABSOLUTE error (minutes): typical magnitude, robust to one wild night. */
        val medianAbsOnsetError: Double?,
        val medianAbsWakeError: Double?,
    )

    fun accuracy(labels: List<SleepEditLabel>): Accuracy {
        val onsets = labels.mapNotNull { it.onsetErrorMinutes }
        val wakes = labels.mapNotNull { it.wakeErrorMinutes }
        return Accuracy(
            count = labels.size,
            meanOnsetError = mean(onsets), meanWakeError = mean(wakes),
            medianAbsOnsetError = medianAbs(onsets), medianAbsWakeError = medianAbs(wakes),
        )
    }

    /** Summed left to right, as upstream's `reduce(0, +)`. */
    private fun mean(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.fold(0.0) { a, x -> a + x } / xs.size

    private fun medianAbs(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.map { Math.abs(it) }.sorted() // finite, non-negative: any ascending sort is Swift's
        return if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2 else s[s.size / 2]
    }

    /**
     * Whether there is enough evidence to FIT a staging knob against these labels — the gate that
     * decides when a default may move off a hand-chosen value. 10 nights is a judgement (roughly where a
     * median stops being dominated by one night), not a statistical guarantee, and it does not cure the
     * selection bias.
     */
    const val MINIMUM_NIGHTS_TO_FIT: Int = 10

    fun isFittable(labels: List<SleepEditLabel>): Boolean = usable(labels).size >= MINIMUM_NIGHTS_TO_FIT
}
