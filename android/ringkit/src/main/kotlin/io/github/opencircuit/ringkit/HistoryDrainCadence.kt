package io.github.opencircuit.ringkit

// Periodic history-drain cadence: how often, while connected and idle, to drain the ring's `0x4c`
// history — and the OVERNIGHT-QUIET gate. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/HistoryDrainCadence.swift:48-94 (@ b1c2fdd).
//
// WHY THE OVERNIGHT-QUIET GATE. Upstream drained every ~30 min all night and the ring still STOPPED
// handing off `0x4c` sleep history mid-night and never resumed — the back ~3 h was lost. Each
// drain's cursor≈now open plus its per-channel `0x07` fetch contends the ring's single resume
// pointer. So inside the sleep window an AUTOMATIC drain runs NOTHING and the night accumulates
// untouched on the ring (it buffers for days); the whole night is pulled in ONE pass once the window
// ends, because by then `lastDrainAt` is hours old and [isDue] is true. ⚠️ Upstream flags this as
// still needing on-device validation. A manual sync always bypasses the gate.
//
// Upstream's header also corrects an older claim: the gate does NOT eliminate overnight skin
// temperature on at least one ring, and WHY it survives is not established — do not reason from
// "overnight temp is gone".

import java.time.Duration
import java.time.Instant

object HistoryDrainCadence {

    /**
     * Minimum time between periodic drains while connected and idle. Inside the sleep window
     * ([isNight]) 30 min (45 min in battery saver) — with the overnight-quiet gate this only matters
     * for [isDue] bookkeeping. By day 1 h (3 h in battery saver).
     *
     * iOS-tuned — re-check in E9/E11. All four values ported verbatim from upstream (`:62-65`).
     */
    fun interval(isNight: Boolean, batterySaver: Boolean): Duration =
        if (isNight) {
            Duration.ofMinutes(if (batterySaver) 45 else 30)
        } else {
            Duration.ofMinutes(if (batterySaver) 180 else 60)
        }

    /**
     * Whether a periodic drain is due: nothing drained yet, or [interval] has elapsed since the last
     * drain (the boundary itself is due). [now] and [lastDrainAt] are passed in so this stays pure.
     */
    fun isDue(lastDrainAt: Instant?, now: Instant, isNight: Boolean, batterySaver: Boolean): Boolean {
        val last = lastDrainAt ?: return true
        return Duration.between(last, now) >= interval(isNight, batterySaver)
    }

    /**
     * The OVERNIGHT-QUIET gate: whether to actually run a history drain right now. A user-initiated
     * ([manual]) sync always drains; an automatic one inside the sleep window never does; otherwise
     * this mirrors [isDue], so daytime behaviour is unchanged.
     */
    fun shouldDrain(manual: Boolean, inSleepWindow: Boolean, isDue: Boolean): Boolean {
        if (manual) return true           // user asked explicitly — never gated
        if (inSleepWindow) return false   // overnight-quiet: one drain at wake, not many through the night
        return isDue                      // daytime: the normal cadence
    }
}
