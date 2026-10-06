package io.github.opencircuit.app.ring

import io.github.opencircuit.app.session.KeepaliveProblem
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RefusalReason

/** The battery icon's fill, by upstream's thresholds (`ios/OpenCircuit/ContentView.swift:1505-1512` @ b1c2fdd). */
enum class BatteryBand { EMPTY, QUARTER, HALF, THREE_QUARTERS, FULL }

/** The ring battery as the connection card shows it. */
data class BatteryUi(
    val percent: Int,
    val band: BatteryBand,
    /** Charging, by the state byte or the rising battery: drawn with a bolt. */
    val charging: Boolean,
    /** 20 % or less and not charging: drawn in the warning colour. */
    val low: Boolean,
    /** The reading is out of date: drawn faded, with [asOf]. */
    val stale: Boolean,
    /** "~3 d 6 h left", "~40 min to full", "Full", "estimating time left…", or null. */
    val timeLine: String?,
    /** "as of 4 min ago" once the reading is out of date, else null. */
    val asOf: String?,
    /** "Case 88%" while the ring is docked in its charging case, else null. */
    val caseLine: String?,
    /** The case itself is plugged in. */
    val caseCharging: Boolean,
)

/** Everything the connection card shows. */
data class ConnectionCardUi(
    /** The ring's name, or null when there is no ring yet. */
    val ringName: String?,
    /** The link's words and the card's action. */
    val link: LinkStateUi,
    /** The ring battery, or null before the first reading. */
    val battery: BatteryUi?,
    /** "Ring is on the charger — Measure unavailable" while connected, charging and not measuring. */
    val chargerHint: String?,
    /** Why the last status request did not go out, in words, or null. */
    val problem: String?,
)

/** The text the card shows while the ring charges and no measure runs (Measure is disabled then). */
const val ON_THE_CHARGER = "Ring is on the charger — Measure unavailable"

/**
 * The connection card for one moment: the link's state through [LinkStatePresenter], and the
 * battery lines by upstream's rules (`ContentView.swift:1131-1191`): while charging "Full" at
 * 100 %, the time to full, or "estimating time to full…"; otherwise, unless the reading is out of
 * date, the time left or "estimating time left…"; the case only while docked.
 */
fun connectionCardUi(
    linkState: LinkState,
    ringName: String?,
    status: DeviceStatusState,
    measuring: Boolean,
    keepaliveProblem: KeepaliveProblem?,
): ConnectionCardUi = ConnectionCardUi(
    ringName = ringName,
    link = LinkStatePresenter.present(linkState, ringName ?: "the ring"),
    battery = status.batteryPercent?.let { batteryUi(it, status) },
    chargerHint = if (linkState == LinkState.Authenticated && status.charging && !measuring) ON_THE_CHARGER else null,
    problem = keepaliveProblem?.let { "Couldn't ask the ring for its status — ${it.words()}." },
)

private fun batteryUi(percent: Int, status: DeviceStatusState): BatteryUi {
    val stale = status.batteryAgeMillis != null
    val timeLine = when {
        status.charging -> when {
            percent >= 100 -> "Full"
            status.timeToFullSeconds != null && status.timeToFullSeconds > 0 -> "~${durationWords(status.timeToFullSeconds)} to full"
            else -> "estimating time to full…"
        }
        stale -> null
        status.timeToEmptySeconds != null -> "~${durationWords(status.timeToEmptySeconds)} left"
        else -> "estimating time left…"
    }
    return BatteryUi(
        percent = percent,
        band = batteryBand(percent),
        charging = status.charging,
        low = percent <= LOW_PERCENT && !status.charging,
        stale = stale,
        timeLine = timeLine,
        asOf = status.batteryAgeMillis?.let { "as of ${ageWords(it)}" },
        caseLine = status.caseBattery?.let { "Case ${it.percent}%" },
        caseCharging = status.caseBattery?.isCharging == true,
    )
}

/** The icon band for [percent] (CV:1505-1512). */
fun batteryBand(percent: Int): BatteryBand = when {
    percent < 13 -> BatteryBand.EMPTY
    percent < 38 -> BatteryBand.QUARTER
    percent < 63 -> BatteryBand.HALF
    percent < 88 -> BatteryBand.THREE_QUARTERS
    else -> BatteryBand.FULL
}

/** "3 d 6 h", "2 h 15 min", "40 min" — upstream's `tteString` (CV:1494-1503), at least one minute. */
fun durationWords(seconds: Double): String {
    val totalMinutes = (seconds / 60).toLong()
    val days = totalMinutes / MINUTES_PER_DAY
    val hours = (totalMinutes % MINUTES_PER_DAY) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> if (hours > 0) "$days d $hours h" else "$days d"
        hours > 0 -> if (minutes > 0) "$hours h $minutes min" else "$hours h"
        else -> "${maxOf(totalMinutes, 1)} min"
    }
}

/** "4 min ago", "2 h ago", "3 d ago". */
private fun ageWords(ageMillis: Long): String {
    val minutes = ageMillis / 60_000
    return when {
        minutes < 60 -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / 60} h ago"
        else -> "${minutes / MINUTES_PER_DAY} d ago"
    }
}

private fun KeepaliveProblem.words(): String = when (this) {
    is KeepaliveProblem.Refused -> when (reason) {
        RefusalReason.NOT_AUTHENTICATED -> "the ring isn't connected"
        RefusalReason.NOT_BONDED -> "this phone isn't paired with the ring"
        RefusalReason.AUTH_COMMAND_RESERVED, RefusalReason.HISTORY_UNSAFE -> "the ring link refused it"
    }
    is KeepaliveProblem.Failed -> "the ring stopped answering"
}

/** At or below this the battery is drawn in the warning colour (CV:1145). */
private const val LOW_PERCENT = 20

private const val MINUTES_PER_DAY = 1_440L
