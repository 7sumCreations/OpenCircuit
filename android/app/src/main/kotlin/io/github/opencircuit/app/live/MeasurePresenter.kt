package io.github.opencircuit.app.live

import io.github.opencircuit.ble.RefusalReason

/** One Measure card: heart rate or SpO₂. */
data class MeasureCardUi(
    val mode: LiveMode,
    /** "Heart rate" / "SpO₂". */
    val title: String,
    /** Shown with an "est." mark (SpO₂ is a single-window estimate, PROTOCOL.md §5.1). */
    val estimate: Boolean,
    /** The line under the title: the last reading, or how the running measure is doing. */
    val caption: String,
    /** Why the latest measure of this card failed, or null. */
    val failure: String?,
    /** This card's measure is running: its button stops it. */
    val measuring: Boolean,
    /** What the card's button does, in words (also its accessibility label). */
    val actionLabel: String,
)

/** The Live card shown while a measure runs. */
data class LiveCardUi(
    val title: String,
    val estimate: Boolean,
    /** The newest reading, or "—" before one arrives and once the readout is stale. */
    val readout: String,
    val unit: String,
    /** "lo–hi so far", or null before two readings. */
    val range: String?,
    /** A line saying what the measure is doing. */
    val progress: String?,
    /** The chart's points, oldest first. */
    val points: List<LivePoint>,
    /** How much time the chart spans. */
    val windowMillis: Long,
    val windowLabel: String,
)

/** The Measure section of the Ring screen. */
data class MeasureUi(val heartRate: MeasureCardUi, val spo2: MeasureCardUi, val live: LiveCardUi?)

/** The words and numbers for [state]; pure, so it is tested on the JVM. */
fun measureUi(state: LiveMeasureState): MeasureUi = MeasureUi(
    heartRate = card(state, LiveMode.HEART_RATE),
    spo2 = card(state, LiveMode.SPO2),
    live = state.mode?.let { liveCard(state, it) },
)

/** The line a failed measure leaves on its card. */
fun MeasureFailure.message(): String = when (this) {
    MeasureFailure.NoReading ->
        // Upstream's wording (ios/OpenCircuit/BLE/RingSession.swift:1167 @ b1c2fdd).
        "Couldn't get a reading — make sure the ring is worn snugly and not on the charger, then hold still."
    MeasureFailure.RingDisconnected -> "Measurement stopped — the ring disconnected."
    is MeasureFailure.CommandRefused -> when (reason) {
        RefusalReason.NOT_AUTHENTICATED -> "Couldn't measure — the ring isn't connected."
        RefusalReason.NOT_BONDED -> "Couldn't measure — this phone isn't paired with the ring."
        RefusalReason.AUTH_COMMAND_RESERVED, RefusalReason.HISTORY_UNSAFE ->
            "Couldn't measure — the ring link refused the command."
    }
    is MeasureFailure.CommandFailed -> "Measurement stopped — the ring stopped answering."
}

private fun card(state: LiveMeasureState, mode: LiveMode): MeasureCardUi {
    val result = if (mode == LiveMode.HEART_RATE) state.heartRate else state.spo2
    val measuring = state.mode == mode
    val caption = when {
        measuring && state.preparing -> "preparing…"
        measuring && mode == LiveMode.HEART_RATE && state.settledHeartRate != null ->
            "${state.settledHeartRate} bpm (settled) · measuring…"
        measuring -> "measuring…"
        result.lastValue != null -> "Last: ${result.lastValue}${unitSuffix(mode)}"
        else -> "No reading yet"
    }
    val name = if (mode == LiveMode.HEART_RATE) "heart rate" else "SpO₂"
    return MeasureCardUi(
        mode = mode,
        title = if (mode == LiveMode.HEART_RATE) "Heart rate" else "SpO₂",
        estimate = mode == LiveMode.SPO2,
        caption = caption,
        failure = result.failure?.message(),
        measuring = measuring,
        actionLabel = if (measuring) "Stop measuring $name" else "Measure $name",
    )
}

private fun liveCard(state: LiveMeasureState, mode: LiveMode): LiveCardUi {
    val readout = state.newest?.takeUnless { state.stale }
    val holdStill = state.preparing || state.stale || (mode == LiveMode.HEART_RATE && state.warmingUp)
    val progress = when {
        // CV:1455-1461: "Hold still" while frames arrive but have not locked on.
        holdStill -> HOLD_STILL
        mode == LiveMode.HEART_RATE -> "Measuring heart rate…"
        else -> "Measuring SpO₂…"
    }
    return LiveCardUi(
        title = if (mode == LiveMode.HEART_RATE) "Live heart rate" else "Live SpO₂",
        estimate = mode == LiveMode.SPO2,
        readout = readout?.toString() ?: "—",
        unit = if (mode == LiveMode.HEART_RATE) "bpm" else "%",
        range = state.session.range?.let { "${it.first}–${it.last} so far" },
        progress = progress,
        points = state.session.points,
        windowMillis = LiveSession.WINDOW_MILLIS,
        windowLabel = "last ${LiveSession.WINDOW_MILLIS / 1_000} s",
    )
}

private fun unitSuffix(mode: LiveMode): String = if (mode == LiveMode.HEART_RATE) " bpm" else "%"

private const val HOLD_STILL = "Hold still — getting a reading"
