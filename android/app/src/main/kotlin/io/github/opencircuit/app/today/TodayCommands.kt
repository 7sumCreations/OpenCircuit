package io.github.opencircuit.app.today

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.session.SessionHost

/** What Today's own actions do to the ring session. */
interface TodayCommands {
    /** Pull to refresh: sync now. */
    fun refresh()

    /** The hero's SpO₂ Measure. */
    fun measureSpO2()

    /** The hero's Stop. */
    fun stopMeasure()

    companion object {
        /** No ring: nothing to do. */
        val NONE: TodayCommands = object : TodayCommands {
            override fun refresh() = Unit
            override fun measureSpO2() = Unit
            override fun stopMeasure() = Unit
        }
    }
}

/**
 * Today's commands on the current ring session; each does nothing when there is none.
 * - [TodayCommands.refresh]: the automatic heart-rate measure stands down first (a sync never
 *   starts while a measure runs), then Sync now. A measure the user started is left alone, and
 *   the sync then refuses — the hero's Stop ends that measure.
 * - [TodayCommands.measureSpO2]: the user's SpO₂ measure; it takes the automatic heart rate over,
 *   which comes back when it ends.
 */
fun todayCommands(sessions: SessionHost?): TodayCommands {
    if (sessions == null) return TodayCommands.NONE
    return object : TodayCommands {
        override fun refresh() {
            sessions.current.value?.syncNow()
        }

        override fun measureSpO2() {
            sessions.current.value?.liveMeasure?.start(LiveMode.SPO2)
        }

        override fun stopMeasure() {
            sessions.current.value?.liveMeasure?.stopUser()
        }
    }
}
