package io.github.opencircuit.app.session

import java.util.concurrent.atomic.AtomicReference

/**
 * Who holds the ring's link for a run of commands that must not interleave with another's: a
 * history sync, or a live measure (the user's or the dashboard's automatic one). One claim at a
 * time, taken in one atomic step — never "check the other side's flag, then set mine", which two
 * starts in the same instant both pass. A live write mid-drain is the hazard: a `d0` can be
 * answered with `0x50`, which the drain reads as the end of history (PROTOCOL.md §4).
 *
 * A claim is a token: [release] frees the link only while that very claim holds it, so releasing
 * twice, or after a hand-over ([transfer]), changes nothing. Thread-safe, lock-free.
 */
class LinkOwner {

    /** What a claim is for. */
    enum class Kind {
        /** A history sync, from its start until its report is published. */
        SYNC,

        /** A live measure the user tapped for. */
        MEASURE_USER,

        /** The dashboard's automatic live heart rate: it stands down for a sync or the user's tap. */
        MEASURE_AUTO,
    }

    /** One holding of the link; compared by identity, so an old claim never frees a newer one. */
    class Claim internal constructor(val kind: Kind) {
        override fun toString(): String = "Claim($kind)"
    }

    private val holder = AtomicReference<Claim?>(null)

    /** The claim holding the link now, or null when it is free. */
    val current: Claim? get() = holder.get()

    /** Takes the link for [kind] if it is free; null when anything holds it. */
    fun claim(kind: Kind): Claim? {
        val claim = Claim(kind)
        return if (holder.compareAndSet(null, claim)) claim else null
    }

    /** Hands the link from [from] to a new claim of [kind] with no instant free between; null when [from] does not hold it. */
    fun transfer(from: Claim, kind: Kind): Claim? {
        val claim = Claim(kind)
        return if (holder.compareAndSet(from, claim)) claim else null
    }

    /** Frees the link if [claim] holds it; true when it did. */
    fun release(claim: Claim): Boolean = holder.compareAndSet(claim, null)
}
