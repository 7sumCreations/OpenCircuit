package io.github.opencircuit.health

import io.github.opencircuit.ringkit.FirmwareInfo
import io.github.opencircuit.ringkit.RingGeneration
import java.time.Instant
import java.time.ZoneOffset

/** How a record came about, as Health Connect labels it. */
enum class RecordingMethod { AUTOMATICALLY_RECORDED, MANUAL_ENTRY }

/** The ring that measured a record. Health Connect stores it as a device of type ring. */
data class RingDevice(val model: String) {
    init {
        require(model.isNotBlank()) { "a ring model must not be blank" }
    }

    companion object {
        const val MANUFACTURER = "RingConn"

        /** The model when the ring's firmware is not known, or names no known ring. */
        const val UNKNOWN_MODEL = "Ring"

        /**
         * The ring's model from its firmware revision prefix (FR02 Gen 2, FR04 Gen 2 Air, FR05
         * Gen 3, FR01 Gen 1 — `FirmwareInfo.generation`), or [UNKNOWN_MODEL].
         */
        fun ofFirmware(version: String?): RingDevice {
            val generation = FirmwareInfo(version = version.orEmpty()).generation
            return RingDevice(if (generation == RingGeneration.UNKNOWN) UNKNOWN_MODEL else generation.rawValue)
        }
    }
}

/**
 * The metadata every record carries. [clientRecordId] is the record's own id (`oc:<type>:<key>`,
 * see [RecordIds]): Health Connect replaces a stored record with the same id and type instead of
 * adding a second one, provided [version] is higher than the stored one. A record without an id
 * would be de-duplicated by time instead, silently, so an id is required here. [device] is null
 * only for what the user typed in (a logged period, a manually added nap).
 */
data class RecordMeta(
    val clientRecordId: String,
    val version: Long,
    val method: RecordingMethod,
    val device: RingDevice?,
) {
    init {
        require(clientRecordId.isNotBlank()) { "a record needs a clientRecordId" }
        require(version >= 1) { "a record version starts at 1, was $version" }
    }
}

/** One record the app writes to Health Connect, in plain Kotlin (the adapter maps it to Jetpack). */
sealed interface HealthRecord {
    val type: RecordType
    val meta: RecordMeta
}

/** A record at one instant. */
sealed interface InstantHealthRecord : HealthRecord {
    val time: Instant
    val zoneOffset: ZoneOffset
}

/** A record over a span. */
sealed interface IntervalHealthRecord : HealthRecord {
    val start: Instant
    val startZoneOffset: ZoneOffset
    val end: Instant
    val endZoneOffset: ZoneOffset
}

/** One heart-rate reading inside a [HeartRateSeries]. */
data class HeartRateSample(val time: Instant, val beatsPerMinute: Long)

/** Heart-rate readings over one span (the writer uses one UTC clock hour per record). */
class HeartRateSeries(
    override val start: Instant,
    override val startZoneOffset: ZoneOffset,
    override val end: Instant,
    override val endZoneOffset: ZoneOffset,
    samples: List<HeartRateSample>,
    override val meta: RecordMeta,
) : IntervalHealthRecord {
    /** A copy of the samples given, read-only. */
    val samples: List<HeartRateSample> = samples.toList()
    override val type: RecordType get() = RecordType.HEART_RATE

    override fun equals(other: Any?): Boolean = other is HeartRateSeries && parts() == other.parts()
    override fun hashCode(): Int = parts().hashCode()
    override fun toString(): String = "HeartRateSeries(${parts()})"
    private fun parts() = listOf(start, startZoneOffset, end, endZoneOffset, samples, meta)
}

data class RestingHeartRate(
    override val time: Instant,
    override val zoneOffset: ZoneOffset,
    val beatsPerMinute: Long,
    override val meta: RecordMeta,
) : InstantHealthRecord {
    override val type: RecordType get() = RecordType.RESTING_HEART_RATE
}

/** Heart-rate variability as RMSSD, in milliseconds. */
data class HeartRateVariabilityRmssd(
    override val time: Instant,
    override val zoneOffset: ZoneOffset,
    val millis: Double,
    override val meta: RecordMeta,
) : InstantHealthRecord {
    override val type: RecordType get() = RecordType.HEART_RATE_VARIABILITY_RMSSD
}

/** Blood oxygen saturation, in percent (0 to 100). */
data class OxygenSaturation(
    override val time: Instant,
    override val zoneOffset: ZoneOffset,
    val percent: Double,
    override val meta: RecordMeta,
) : InstantHealthRecord {
    override val type: RecordType get() = RecordType.OXYGEN_SATURATION
}

/** Breaths per minute. */
data class RespiratoryRate(
    override val time: Instant,
    override val zoneOffset: ZoneOffset,
    val breathsPerMinute: Double,
    override val meta: RecordMeta,
) : InstantHealthRecord {
    override val type: RecordType get() = RecordType.RESPIRATORY_RATE
}

/** One skin-temperature difference from the record's baseline, in degrees Celsius. */
data class SkinTemperatureDelta(val time: Instant, val deltaCelsius: Double)

/** Where on the body a skin temperature was taken. The ring is worn on a finger. */
enum class SkinTemperatureLocation { UNKNOWN, FINGER, TOE, WRIST }

/**
 * Skin temperature over one span (the writer uses one night per record): differences from
 * [baselineCelsius], which is null when no baseline is known yet.
 */
class SkinTemperature(
    override val start: Instant,
    override val startZoneOffset: ZoneOffset,
    override val end: Instant,
    override val endZoneOffset: ZoneOffset,
    val baselineCelsius: Double?,
    deltas: List<SkinTemperatureDelta>,
    val location: SkinTemperatureLocation,
    override val meta: RecordMeta,
) : IntervalHealthRecord {
    /** A copy of the deltas given, read-only. */
    val deltas: List<SkinTemperatureDelta> = deltas.toList()
    override val type: RecordType get() = RecordType.SKIN_TEMPERATURE

    override fun equals(other: Any?): Boolean = other is SkinTemperature && parts() == other.parts()
    override fun hashCode(): Int = parts().hashCode()
    override fun toString(): String = "SkinTemperature(${parts()})"
    private fun parts() = listOf(start, startZoneOffset, end, endZoneOffset, baselineCelsius, deltas, location, meta)
}

data class Steps(
    override val start: Instant,
    override val startZoneOffset: ZoneOffset,
    override val end: Instant,
    override val endZoneOffset: ZoneOffset,
    val count: Long,
    override val meta: RecordMeta,
) : IntervalHealthRecord {
    override val type: RecordType get() = RecordType.STEPS
}

data class Distance(
    override val start: Instant,
    override val startZoneOffset: ZoneOffset,
    override val end: Instant,
    override val endZoneOffset: ZoneOffset,
    val meters: Double,
    override val meta: RecordMeta,
) : IntervalHealthRecord {
    override val type: RecordType get() = RecordType.DISTANCE
}

data class ActiveCaloriesBurned(
    override val start: Instant,
    override val startZoneOffset: ZoneOffset,
    override val end: Instant,
    override val endZoneOffset: ZoneOffset,
    val kilocalories: Double,
    override val meta: RecordMeta,
) : IntervalHealthRecord {
    override val type: RecordType get() = RecordType.ACTIVE_CALORIES_BURNED
}

/** Basal metabolic rate at one instant, in kilocalories per day. */
data class BasalMetabolicRate(
    override val time: Instant,
    override val zoneOffset: ZoneOffset,
    val kilocaloriesPerDay: Double,
    override val meta: RecordMeta,
) : InstantHealthRecord {
    override val type: RecordType get() = RecordType.BASAL_METABOLIC_RATE
}

/** Health Connect's sleep stage kinds (the platform's own numbering). */
enum class SleepStageType(val platformCode: Int) {
    UNKNOWN(0),
    AWAKE(1),
    SLEEPING(2),
    OUT_OF_BED(3),
    LIGHT(4),
    DEEP(5),
    REM(6),
    AWAKE_IN_BED(7),
}

data class SleepStage(val start: Instant, val end: Instant, val stage: SleepStageType)

/** One sleep (a night or a nap): the in-bed span and its stages, which must not overlap. */
class SleepSession(
    override val start: Instant,
    override val startZoneOffset: ZoneOffset,
    override val end: Instant,
    override val endZoneOffset: ZoneOffset,
    val title: String?,
    val notes: String?,
    stages: List<SleepStage>,
    override val meta: RecordMeta,
) : IntervalHealthRecord {
    /** A copy of the stages given, read-only. */
    val stages: List<SleepStage> = stages.toList()
    override val type: RecordType get() = RecordType.SLEEP_SESSION

    override fun equals(other: Any?): Boolean = other is SleepSession && parts() == other.parts()
    override fun hashCode(): Int = parts().hashCode()
    override fun toString(): String = "SleepSession(${parts()})"
    private fun parts() = listOf(start, startZoneOffset, end, endZoneOffset, title, notes, stages, meta)
}

/** Health Connect's menstruation flow levels (the platform's own numbering). */
enum class MenstruationFlowLevel(val platformCode: Int) { UNKNOWN(0), LIGHT(1), MEDIUM(2), HEAVY(3) }

data class MenstruationFlow(
    override val time: Instant,
    override val zoneOffset: ZoneOffset,
    val flow: MenstruationFlowLevel,
    override val meta: RecordMeta,
) : InstantHealthRecord {
    override val type: RecordType get() = RecordType.MENSTRUATION_FLOW
}

data class MenstruationPeriod(
    override val start: Instant,
    override val startZoneOffset: ZoneOffset,
    override val end: Instant,
    override val endZoneOffset: ZoneOffset,
    override val meta: RecordMeta,
) : IntervalHealthRecord {
    override val type: RecordType get() = RecordType.MENSTRUATION_PERIOD
}
