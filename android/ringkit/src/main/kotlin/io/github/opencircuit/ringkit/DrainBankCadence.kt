package io.github.opencircuit.ringkit

// When acked-but-uncommitted history pages must be banked to the [EpochArchive]. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/DrainBankCadence.swift:28-64 (@ b1c2fdd).
//
// WHY. Every `0x4c` page is ACKed, and the ACK advances the ring's single resume pointer — so an
// acked page is gone from the ring whether or not it was kept. Pages arriving WITH a drain open sit
// in volatile memory until the drain commits. 🟢 Upstream lost a whole 119-record night inside
// exactly that window (2026-08-07, Gen 2 Air FR04.009).
//
// THE RULE. Bank on a short quiet debounce so one burst costs a couple of archive writes — but ALSO
// bound the total hold, because the debounce re-arms on every page and a CONTINUOUS handoff would
// otherwise defer the bank until the burst ends, which is the exact window being closed.

import java.time.Duration
import java.time.Instant

object DrainBankCadence {

    /**
     * Quiet window after the last page before banking: 4 s, above the measured 1–3 s inter-page
     * gaps so a healthy stream is not chopped into one write per page.
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:38`).
     */
    val QUIET: Duration = Duration.ofSeconds(4)

    /**
     * Hard bound on how long acked-but-unbanked pages may sit in volatile memory: 8 s. The
     * load-bearing half — during a continuous handoff only this bound ever fires. Banking is
     * idempotent ([EpochArchive.merge] dedups by counter).
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:46`).
     */
    val MAX_HOLD: Duration = Duration.ofSeconds(8)

    enum class Action {
        /** Bank immediately — the hold bound is reached; do not wait out the debounce. */
        BANK_NOW,

        /** Arm (or re-arm) the quiet debounce. */
        DEBOUNCE,
    }

    /**
     * @param firstUnbankedAt when the oldest not-yet-banked page arrived, or null when nothing is held.
     * @param now the current instant, passed in so this stays pure.
     */
    fun decide(firstUnbankedAt: Instant?, now: Instant, maxHold: Duration = MAX_HOLD): Action {
        val first = firstUnbankedAt ?: return Action.DEBOUNCE
        return if (Duration.between(first, now) >= maxHold) Action.BANK_NOW else Action.DEBOUNCE
    }
}
