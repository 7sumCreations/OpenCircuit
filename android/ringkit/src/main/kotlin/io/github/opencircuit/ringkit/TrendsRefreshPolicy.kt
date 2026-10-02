package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/TrendsRefreshPolicy.swift (@ b1c2fdd),
// whole.
//
// Port notes:
//  • `now` is a required `Instant` (upstream defaults it to the device clock).
//  • `minInterval` is a `Duration` (upstream: a `TimeInterval` of 60 s), and the elapsed time is the
//    exact difference of two instants, where upstream subtracts two `Date` doubles that resolve about
//    0.1 µs at present dates: instants closer than that compare equal upstream and not here.

import java.time.Duration
import java.time.Instant

/**
 * When a trends reload is worth paying for.
 *
 * The trends snapshot is the single most expensive read in the app (measured upstream on a real
 * device: one load fetches about 25 000 rows over its 14-day window), and the view used to fire it
 * three times for one user action — its first appearance, the app becoming active, and a sync
 * finishing.
 *
 * The asymmetry this policy encodes: **only a finished sync knows new rows exist.** Appearing and
 * foregrounding are navigation events that say nothing about the store's contents, so they are
 * debounced; a sync completing is the one signal that the data actually changed, so it always
 * reloads. A first load with nothing on screen yet is never suppressed.
 */
object TrendsRefreshPolicy {

    /** What is asking for the reload. */
    enum class Reason {
        /** The view appeared for the first time this launch. */
        APPEARED,

        /** The app came back to the foreground. */
        FOREGROUNDED,

        /** A history sync just finished — new rows may have landed. */
        SYNC_FINISHED,
    }

    /**
     * How stale a snapshot must be before a navigation event (appear / foreground) re-reads it. 60 s
     * against the ring's 150 s epoch cadence: a suppressed reload can be behind by at most one epoch.
     */
    val MIN_INTERVAL: Duration = Duration.ofSeconds(60)

    /**
     * Whether to run the reload now. [lastLoadedAt] is when the current snapshot was loaded, null if
     * none has ever loaded. A clock that moved backwards (time-zone or NTP correction) must not latch
     * the snapshot stale forever: any non-forward interval is due.
     */
    fun shouldReload(reason: Reason, lastLoadedAt: Instant?, now: Instant): Boolean {
        // Nothing on screen yet — always load, whatever asked.
        if (lastLoadedAt == null) return true
        // A finished sync is the only trigger that carries information about the STORE rather than
        // about navigation, so it is never debounced.
        if (reason == Reason.SYNC_FINISHED) return true
        val elapsed = Duration.between(lastLoadedAt, now)
        return elapsed.isNegative || elapsed >= MIN_INTERVAL
    }
}
