package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepEditedNightNotice.swift (@ b1c2fdd): THE
// ONE LINE THAT NAMES THE PART OF A NIGHT NOBODY MEASURED.
//
// It fires on two facts that are both CERTAIN and both already on the stored row: the user EDITED this
// night, and some of the sleep the card shows sits over ground holding NO records (asserted asleep,
// computed from the record set itself). Neither is an inference, so neither can be a false positive.
//
// IT MUST NOT SCOLD. The wearer told us something true the ring could not see. The copy keeps their
// times ("We kept the times you set"), calls the unmeasured part "your account" rather than a guess or
// an estimate, and reports the health-store consequence as a fact about what this app writes.
//
// SCOPED TO ASSERTED ASLEEP, DELIBERATELY: a night with asserted AWAKE and no asserted asleep stays
// silent (a declared limit, not an oversight).
//
// The copy is upstream's, character for character (the apostrophe in "wasn’t" is U+2019). Numbers are
// whole 64-bit integers rendered by Kotlin's locale-free `toString`, as Swift's interpolation renders
// them; a value upstream would trap on (non-finite, or a minute count outside 64 bits) is rejected by
// the formatter, and the line stays silent instead of throwing out of the card.

object SleepEditedNightNotice {

    /**
     * Least asserted-asleep time (seconds) worth a line. NOT a detection threshold — the condition is
     * already certain — purely a rendering floor: below one whole minute the sentence would print
     * "1 minute" for a sub-minute rounding artifact.
     */
    const val MIN_ASSERTED_ASLEEP: Double = 60.0

    /**
     * Below this (seconds) the ring measured NOTHING and a different sentence is used, because
     * [duration] floors at one minute and "the ring recorded 1 minute" would then be a fabrication.
     * Sub-second rather than a round minute: 40 measured seconds render as the honest "1 minute".
     */
    const val NO_MEASURED_ASLEEP: Double = 1.0

    /**
     * The rendered line, or null when this night must stay silent.
     *
     * [measuredAsleep]: asleep seconds over ground the ring recorded; NEGATIVE is the store's "not
     * computed" sentinel and returns null — never treated as zero, because "we did not compute it" and
     * "the ring measured nothing" are different statements. [assertedAsleep]: asleep seconds the edit
     * claims over ground holding no records; negative is the same sentinel. [mirrorsSleepToHealth]:
     * whether this app is actually writing sleep to the health store right now — when false the health
     * sentence is DROPPED rather than reworded; it has no default so callers must pass the real state.
     */
    fun line(measuredAsleep: Double, assertedAsleep: Double, mirrorsSleepToHealth: Boolean): String? {
        if (!(measuredAsleep >= 0 && assertedAsleep >= MIN_ASSERTED_ASLEEP)) return null
        val asserted = render(assertedAsleep) ?: return null

        // The health clause states what we write, not what we withhold. "For the other <span> we have…"
        // is a grammar fix: it agrees for both "1 minute" and "4h 1m" without branching on the number.
        if (measuredAsleep < NO_MEASURED_ASLEEP) {
            val head = "We kept the times you set. The ring wasn’t recording for any of this night, " +
                "so all $asserted of the sleep above is your account, not a measurement."
            if (!mirrorsSleepToHealth) return head
            return head + " It all reaches Apple Health, marked there as entered by you."
        }

        val measured = render(measuredAsleep) ?: return null
        val head = "We kept the times you set. The ring recorded $measured of the " +
            "sleep above; for the other $asserted we have your account, not a measurement."
        if (!mirrorsSleepToHealth) return head
        return head + " Both reach Apple Health; your part is marked there as entered by you."
    }

    /**
     * A span at the precision the measurement supports — whole minutes (rounded half away from zero,
     * never below 1) under an hour, then hours and minutes. Never seconds: the underlying edges are
     * 150 s epoch boundaries. The Sleep card's house formatter, so this line and the bedtime-provenance
     * line beside it cannot drift apart.
     *
     * @throws IllegalArgumentException for a value upstream traps on: NaN, ±infinity, or a rounded
     *   minute count outside 64 bits.
     */
    fun duration(seconds: Double): String =
        render(seconds) ?: throw IllegalArgumentException("$seconds s is not a renderable span")

    private fun render(seconds: Double): String? {
        val minutes = maxOf(wholeMinutes(seconds) ?: return null, 1L)
        if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"}"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h hour${if (h == 1L) "" else "s"}" else "${h}h ${m}m"
    }

    /** Swift's `Int((seconds / 60).rounded())`, or null where that traps (non-finite or outside 64 bits). */
    private fun wholeMinutes(seconds: Double): Long? {
        val r = roundHalfAwayFromZero(seconds / 60)
        if (!(r >= -TWO_POW_63 && r < TWO_POW_63)) return null // NaN fails both
        return r.toLong()
    }

    private const val TWO_POW_63: Double = 9.223372036854775808E18
}
