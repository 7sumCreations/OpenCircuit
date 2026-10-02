package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/ActiveEnergyLedger.swift (@ b1c2fdd),
// whole: which active-energy increments to write to the health store, and where.
//
// Given the day attributed into buckets (`Calories.EnergyBucket`) and a record of what each bucket
// has already contributed, which buckets owe kcal right now? The health store SUMS active energy, so
// a double write is permanent: marks advance only on a confirmed save (the caller persists a plan's
// state only then), debts are consumed exactly once, and buckets are addressed by their ordinal from
// the day start — never by list position, since a late drain inserts an EARLIER bucket.
//
// Port notes:
//  • The ledger's grid is the daily estimate's: bucket widths from one second to one billion seconds,
//    ordinals 0 … ⌊26 h / width⌋. Outside the width range `plan` writes nothing and hands the state
//    back and `seed` carries the whole legacy total; a bucket past the day's 26 h span is skipped, as
//    upstream skips one before the day (upstream sizes its marks by any ordinal: a 1e-300 s width
//    traps, a bucket at 3e16 s exhausts memory — PORTING.md D-78).
//  • Fail closed (PORTING.md D-79): when an in-day bucket's energy is NaN, infinite or negative, the
//    day total is not finite, a stored watermark is NaN, infinite or negative, or the carry, workout
//    credit or saved total is NaN or infinite, `plan` writes nothing and returns the state it was
//    given. Upstream over-writes on a negative mark and forgets an unreadable debt or saved total.
//    A negative carry, credit or saved total still counts as zero, as upstream.
//  • `seed` adds the fills of buckets that share a slot (upstream overwrites the slot, so the next
//    plan re-pays energy already written), reads a NaN legacy total as covering the day, and places
//    nothing when it cannot read a bucket's energy (PORTING.md D-80).
//  • The day total is summed in ascending ordinal, where upstream adds a dictionary's values in its
//    per-process order (PORTING.md D-82). Everything else — the fall netting, the oldest-first debt,
//    the clamp to now, the day-total backstop and the aggregate gate — is upstream's, in its order.
//  • Swift's 64-bit `Int`: `ordinal` returns a `Long`, saturating where Swift traps (D-81).

import java.time.Instant
import java.util.Collections
import java.util.TreeMap
import kotlin.math.floor

/** Decides which active-energy increments a sync may write, so that each kcal is written once. */
object ActiveEnergyLedger {

    /** One active-energy sample to save: [kcal] accrued over `[start, end]`. Doubles compare by IEEE `==`. */
    class Write(val start: Instant, val end: Instant, val kcal: Double) {
        override fun equals(other: Any?): Boolean = other is Write && start == other.start && end == other.end && kcal == other.kcal

        override fun hashCode(): Int = listOf(start, end, ieeeHash(kcal)).hashCode()

        override fun toString(): String = "Write(start=$start, end=$end, kcal=$kcal)"
    }

    /**
     * What to save now, and the state to persist ONLY IF the save succeeds. [writes] are oldest first;
     * [watermarks] is the per-bucket kcal accounted for, indexed by ordinal from the day start (commit
     * it verbatim); [carryRemaining] is the debt still unconsumed; [workoutConsumed] is the workout
     * energy netted out by this plan (add it to the credited-so-far mark on success). A value, as
     * upstream's struct: both lists are copied in and read-only out; doubles compare by IEEE `==`.
     */
    class Plan(writes: List<Write>, watermarks: List<Double>, val carryRemaining: Double, val workoutConsumed: Double) {
        val writes: List<Write> = Collections.unmodifiableList(ArrayList(writes))
        val watermarks: List<Double> = Collections.unmodifiableList(ArrayList(watermarks))

        /** The kcal this plan writes, summed oldest first. */
        val totalKcal: Double get() = writes.fold(0.0) { acc, w -> acc + w.kcal }

        override fun equals(other: Any?): Boolean =
            other is Plan && writes == other.writes && ieeeEqual(watermarks, other.watermarks) &&
                carryRemaining == other.carryRemaining && workoutConsumed == other.workoutConsumed

        override fun hashCode(): Int =
            listOf(writes, watermarks.map { ieeeHash(it) }, ieeeHash(carryRemaining), ieeeHash(workoutConsumed)).hashCode()

        override fun toString(): String =
            "Plan(writes=$writes, watermarks=$watermarks, carryRemaining=$carryRemaining, workoutConsumed=$workoutConsumed)"
    }

    /**
     * Upgrade-day seeding's result — upstream's `(watermarks:, carry:)` tuple as a value: the marks to
     * start the day from (copied in, read-only out) and the legacy energy left over as debt.
     */
    class Seed(watermarks: List<Double>, val carry: Double) {
        val watermarks: List<Double> = Collections.unmodifiableList(ArrayList(watermarks))

        operator fun component1(): List<Double> = watermarks

        operator fun component2(): Double = carry

        override fun equals(other: Any?): Boolean = other is Seed && ieeeEqual(watermarks, other.watermarks) && carry == other.carry

        override fun hashCode(): Int = listOf(watermarks.map { ieeeHash(it) }, ieeeHash(carry)).hashCode()

        override fun toString(): String = "Seed(watermarks=$watermarks, carry=$carry)"
    }

    /**
     * Minimum AGGREGATE kcal before anything is saved. Deliberately applied to the day's whole pending
     * sum and never per bucket: light constant walking is worth well under 1 kcal per 15 minutes, so a
     * per-bucket floor would strand every such bucket forever.
     */
    const val MIN_WRITE_KCAL: Double = 1.0

    /**
     * The bucket [t] falls in, counted from [dayStart] in buckets of [bucketSeconds] (floor). 0 for a
     * width that is not positive, as upstream; 64-bit, saturating where Swift's `Int` conversion traps.
     */
    fun ordinal(t: Instant, dayStart: Instant, bucketSeconds: Double): Long {
        if (!(bucketSeconds > 0)) return 0
        return floor(secondsBetween(dayStart, t) / bucketSeconds).toLong()
    }

    /** True for a width on the ledger's grid — the daily estimate's attribution widths (D-78). */
    private fun onGrid(bucketSeconds: Double): Boolean =
        bucketSeconds >= Calories.MIN_ATTRIBUTION_BUCKET_SECONDS && bucketSeconds <= Calories.MAX_ATTRIBUTION_BUCKET_SECONDS

    /** The last ordinal of the day's attribution span at an on-grid width: at most 93 600. */
    private fun lastOrdinal(bucketSeconds: Double): Long = floor(Calories.MAX_ATTRIBUTION_SECONDS / bucketSeconds).toLong()

    /** A kcal amount the ledger can account with: finite and not below zero (−0.0 is zero). */
    private fun readable(kcal: Double): Boolean = kcal.isFinite() && kcal >= 0.0

    private class Pending(val ordinal: Int, val start: Instant, val end: Instant, val kcal: Double)

    /**
     * Decide what to write.
     *
     * Debt ([carry], then [uncreditedWorkoutKcal]) is consumed FIFO from the OLDEST pending increments.
     * Consumed kcal still advances its bucket's watermark — it has been accounted for, just not by a
     * write — otherwise the same debt would be re-applied on every flush.
     *
     * Returns a plan with no writes and UNCHANGED state when the writable total is below
     * [minWriteKcal] (the kcal stays owed and rides the next flush), and when an input cannot be read
     * (see the file header): a corrupt stored value is never trusted into a write.
     */
    fun plan(
        buckets: List<Calories.EnergyBucket>,
        watermarks: List<Double>,
        dayStart: Instant,
        now: Instant,
        carry: Double = 0.0,
        uncreditedWorkoutKcal: Double = 0.0,
        savedKcal: Double = 0.0,
        bucketSeconds: Double = Calories.ENERGY_BUCKET_SECONDS,
        minWriteKcal: Double = MIN_WRITE_KCAL,
    ): Plan {
        val unchanged = Plan(emptyList(), watermarks, carry, 0.0)
        // An empty bucket set means "no data yet", NOT "the day lost its energy" — returning here keeps
        // the fall-netting below from reading a still-empty morning as a giant overpayment.
        if (!onGrid(bucketSeconds) || buckets.isEmpty()) return unchanged

        val last = lastOrdinal(bucketSeconds)
        val byOrdinal = TreeMap<Int, Double>() // ascending ordinal
        val windows = HashMap<Int, Pair<Instant, Instant>>()
        for (bucket in buckets.sortedWith(compareBy { it.start })) { // stable, as Swift's sort
            val o = ordinal(bucket.start, dayStart, bucketSeconds)
            if (o < 0 || o > last) continue // not this day's bucket
            val kcal = bucket.activeKcal
            if (!readable(kcal)) return unchanged
            val i = o.toInt()
            byOrdinal[i] = (byOrdinal[i] ?: 0.0) + kcal
            windows[i] = bucket.start to (if (now < bucket.end) now else bucket.end)
        }
        if (byOrdinal.isEmpty()) return unchanged
        if (!watermarks.all { readable(it) } || !carry.isFinite() || !uncreditedWorkoutKcal.isFinite() || !savedKcal.isFinite()) {
            return unchanged
        }
        // What the day is worth, in ascending ordinal (upstream: the dictionary's own order).
        var dayTotal = 0.0
        for (kcal in byOrdinal.values) dayTotal += kcal
        if (!dayTotal.isFinite()) return unchanged

        val highest = byOrdinal.lastKey()
        val marks = DoubleArray(maxOf(watermarks.size, highest + 1))
        for (k in watermarks.indices) marks[k] = watermarks[k]

        var carryLeft = swiftMax(0.0, carry)

        // A bucket's attributed energy can FALL between flushes (a later drain re-prices a piece, the
        // sleep window widens, a store recovery drops rows). Lower the mark and carry the shortfall as
        // debt, so the day nets out instead of drifting permanently high.
        for (o in marks.indices) {
            if (!(marks[o] > 0)) continue
            val current = byOrdinal[o] ?: 0.0
            if (!(current < marks[o])) continue
            carryLeft += marks[o] - current
            marks[o] = current
        }

        // Pending increments, oldest first, with the window each would be written over.
        val pending = ArrayList<Pending>()
        for ((o, kcal) in byOrdinal) {
            val increment = kcal - marks[o]
            val window = windows[o] ?: continue
            if (!(increment > 0)) continue
            // A bucket still in progress is written up to `now`, never into the future.
            if (!(window.second > window.first)) continue // wholly ahead of now — stays owed
            pending += Pending(o, window.first, window.second, increment)
        }
        if (pending.isEmpty()) {
            // Nothing to write, but a fall may still have been netted — hand back the debt so the caller
            // can persist it, otherwise the overpayment is forgotten and re-paid later.
            return if (carryLeft > swiftMax(0.0, carry)) Plan(emptyList(), marks.asList(), carryLeft, 0.0) else unchanged
        }

        var workoutLeft = swiftMax(0.0, uncreditedWorkoutKcal)
        val writes = ArrayList<Write>()
        for (item in pending) {
            var remaining = item.kcal
            val fromCarry = swiftMin(remaining, carryLeft)
            remaining -= fromCarry
            carryLeft -= fromCarry
            val fromWorkout = swiftMin(remaining, workoutLeft)
            remaining -= fromWorkout
            workoutLeft -= fromWorkout
            // The whole increment is accounted for either way — written, or netted against debt.
            marks[item.ordinal] += item.kcal
            if (remaining > 0) writes += Write(item.start, item.end, remaining)
        }

        // Day-total backstop: never write more than the day is still worth beyond what was saved. Any
        // path that relocates energy between buckets without the marks following could otherwise
        // re-pay kcal the store already holds. It only ever REDUCES a write.
        var headroom = swiftMax(0.0, dayTotal - swiftMax(0.0, savedKcal))
        val clamped = ArrayList<Write>()
        for (w in writes) {
            if (!(headroom > 0)) break
            val kcal = swiftMin(w.kcal, headroom)
            headroom -= kcal
            clamped += Write(w.start, w.end, kcal)
        }

        val writable = clamped.fold(0.0) { acc, w -> acc + w.kcal }
        if (!(writable >= minWriteKcal)) return unchanged

        return Plan(clamped, marks.asList(), carryLeft, swiftMax(0.0, uncreditedWorkoutKcal) - workoutLeft)
    }

    /**
     * Upgrade-day seeding: convert a single legacy `writtenKcal` total into per-bucket marks.
     *
     * Fills buckets chronologically until the total is exhausted, so energy already written lands in
     * the buckets where it was earned and only the rest is written later. Any excess (the legacy total
     * exceeding today's attributed energy) becomes carry, consumed FIFO by later increments.
     */
    fun seed(
        buckets: List<Calories.EnergyBucket>,
        legacyWrittenKcal: Double,
        dayStart: Instant,
        bucketSeconds: Double = Calories.ENERGY_BUCKET_SECONDS,
    ): Seed {
        // A total that cannot be read is taken to cover the day — upstream's answer for an infinite one.
        var remaining = if (legacyWrittenKcal.isNaN()) Double.POSITIVE_INFINITY else swiftMax(0.0, legacyWrittenKcal)
        if (!onGrid(bucketSeconds)) return Seed(emptyList(), remaining)

        val last = lastOrdinal(bucketSeconds)
        val inDay = ArrayList<Pair<Int, Calories.EnergyBucket>>()
        for (bucket in buckets.sortedWith(compareBy { it.start })) {
            val o = ordinal(bucket.start, dayStart, bucketSeconds)
            if (o < 0 || o > last) continue
            if (!readable(bucket.activeKcal)) return Seed(emptyList(), remaining)
            inDay += o.toInt() to bucket
        }

        val marks = ArrayList<Double>()
        val filled = HashSet<Int>()
        for ((o, bucket) in inDay) {
            while (marks.size <= o) marks += 0.0
            val fill = swiftMin(bucket.activeKcal, remaining)
            // A slot shared by several buckets holds the sum of their fills (upstream keeps the last).
            marks[o] = if (filled.add(o)) fill else marks[o] + fill
            remaining -= fill
        }
        return Seed(marks, remaining)
    }

    /** Swift's `==` on `[Double]`: same length and every element equal by IEEE comparison. */
    private fun ieeeEqual(a: List<Double>, b: List<Double>): Boolean {
        if (a.size != b.size) return false
        for (k in a.indices) if (a[k] != b[k]) return false
        return true
    }
}
