package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepNightRekeyPlan.swift (@ b1c2fdd): the
// DECISION half of the one-shot "bedtime day → wake day" night-key migration, split out so it can be
// tested. The migration rewrites the uniquely-indexed primary key of the user's only copy of their
// sleep history, behind a one-way latch, with no reverse mapping; the store is left with nothing but
// "apply these moves". See SleepNightKey for the collision it repairs.

import java.time.Instant
import java.time.ZoneId
import java.util.Collections

object SleepNightRekeyPlan {

    /** The only three fields the decision needs from a stored summary. */
    data class Row(val night: Instant, val inBedStart: Instant, val inBedEnd: Instant)

    /** One row's intended relocation, in normalized (start-of-day) keys. */
    data class Move(val from: Instant, val to: Instant)

    /**
     * [moves] are to be applied IN THIS ORDER: applying them in order guarantees each destination is
     * free when its move runs. [refused] are rows that wanted to move but whose destination is held by
     * a row that is not itself moving — refused rather than forced, because the night key is uniquely
     * indexed and a row on a stale key is strictly better than a deleted night.
     *
     * Both lists are copied in and read-only out (upstream's arrays are values): changing the lists a
     * plan was built from never changes the plan.
     */
    class Plan(moves: List<Move>, refused: List<Move>) {
        val moves: List<Move> = Collections.unmodifiableList(ArrayList(moves))
        val refused: List<Move> = Collections.unmodifiableList(ArrayList(refused))

        override fun equals(other: Any?): Boolean = other is Plan && moves == other.moves && refused == other.refused
        override fun hashCode(): Int = 31 * moves.hashCode() + refused.hashCode()
        override fun toString(): String = "Plan(moves=$moves, refused=$refused)"
    }

    /**
     * Decide which rows move where, judging days in [zone].
     *
     * NEWEST FIRST. Every ordinary move is "+1 day" — a night's in-bed window ends either on the day it
     * started or the next one — so descending order frees each destination before the row below it asks
     * for the slot. Ascending order would report a cascade of false collisions and leave the table
     * half-migrated, permanently (the caller latches a done-flag). Rows with equal stored instants keep
     * their input order (upstream's sort is stable).
     *
     * Rows whose window is unknown, inverted or cannot be placed in [zone] are left alone entirely
     * ([SleepNightKey.rekeyed] is null), so a legacy row with distant-past edges can never be relocated
     * to year 0. A stored key the calendar cannot place stands for itself in the occupied set.
     */
    fun plan(rows: List<Row>, zone: ZoneId): Plan {
        if (rows.isEmpty()) return Plan(emptyList(), emptyList())
        fun key(night: Instant): Instant = CalendarDay.startOfDay(night, zone) ?: night
        val occupied = rows.mapTo(HashSet()) { key(it.night) }
        val moves = ArrayList<Move>()
        val refused = ArrayList<Move>()
        for (row in rows.sortedByDescending { it.night }) {
            val newKey = SleepNightKey.rekeyed(storedNight = row.night, inBedStart = row.inBedStart, inBedEnd = row.inBedEnd, zone = zone) ?: continue
            val oldKey = key(row.night)
            val move = Move(from = oldKey, to = newKey)
            if (newKey in occupied) {
                refused += move
                continue
            }
            occupied -= oldKey
            occupied += newKey
            moves += move
        }
        return Plan(moves, refused)
    }
}
