package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.RingAlarm
import io.github.opencircuit.ringkit.VibrationPattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of the ring alarm:
 * `{"backupNotification":…,"burstCount":…,"burstSpacing":…,"hour":…,"isEnabled":…,"minute":…,"pattern":<1|2>,"weekdays":[…]}`.
 *
 * Upstream's synthesized Codable on `RingAlarm`. Every key is required; `pattern` is the vibration
 * pattern's raw byte; weekdays are a set, so a repeated day reads once, and a day outside 1–7, an
 * hour of -1 or a minute of 99 is kept as stored (such an alarm never fires). Two differences:
 * the days are written in ascending order, so one alarm always has one stored text (Foundation
 * writes a set in a different order from run to run); and an hour, minute, burst count or day past
 * 32 bits makes the alarm unreadable. What an unreadable alarm costs is the caller's rule.
 */
object RingAlarmCodec {

    /** The stored text, or null when [RingAlarm.burstSpacing] is NaN or infinite (JSON cannot hold it). */
    fun encode(alarm: RingAlarm): String? {
        val spacing = alarm.burstSpacing.finiteJsonOrNull() ?: return null
        return jsonObjectOf(
            "backupNotification" to JsonPrimitive(alarm.backupNotification),
            "burstCount" to JsonPrimitive(alarm.burstCount),
            "burstSpacing" to spacing,
            "hour" to JsonPrimitive(alarm.hour),
            "isEnabled" to JsonPrimitive(alarm.isEnabled),
            "minute" to JsonPrimitive(alarm.minute),
            "pattern" to JsonPrimitive(alarm.pattern.rawValue),
            // Sorted here, not left to the set's own order.
            "weekdays" to JsonArray(alarm.weekdays.sorted().map(::JsonPrimitive)),
        ).toString()
    }

    fun decode(text: String): Decoded<RingAlarm> = readStored(text) { root ->
        val o = root.obj()
        val patternRaw = o.required("pattern").uInt8()
        RingAlarm(
            isEnabled = o.required("isEnabled").bool(),
            hour = o.required("hour").int(),
            minute = o.required("minute").int(),
            weekdays = o.required("weekdays").array().map { it.int() }.toSet(),
            pattern = VibrationPattern.entries.firstOrNull { it.rawValue == patternRaw } ?: unreadable("unknown pattern $patternRaw"),
            burstCount = o.required("burstCount").int(),
            burstSpacing = o.required("burstSpacing").double(),
            backupNotification = o.required("backupNotification").bool(),
        )
    }
}
