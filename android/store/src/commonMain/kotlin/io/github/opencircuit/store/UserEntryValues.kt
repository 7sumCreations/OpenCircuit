package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import java.time.Instant

// What the store hands back for the user-entered logs and the frozen risk rows (upstream returns
// its SwiftData model objects; here the rows stay internal and callers get plain values). Ported
// from ios/OpenCircuit/CycleStore.swift and ios/OpenCircuit/Store/HeadacheStore.swift (@ b1c2fdd).
// The display labels upstream adds on the models (`flowLabel`, `severityLabel`) belong to the UI.

/**
 * Where a logged headache came from. Stored as its raw string; a raw string this build does not
 * know reads as [USER], as upstream.
 */
enum class HeadacheSource(val rawValue: String) {
    USER("user"),

    /** Read back from the health store; never written back to it. */
    HEALTH_IMPORT("healthImport"),

    /** Migrated from a period entry's headache tag. */
    PERIOD_LOG_IMPORT("periodLogImport"),
    ;

    companion object {
        /** The source named by [raw], or [USER] for an unknown raw string. */
        fun of(raw: String): HeadacheSource = entries.firstOrNull { it.rawValue == raw } ?: USER
    }
}

/**
 * One logged period. [end] is null while the period is still open. [flowLevelRaw]: 1 = light,
 * 2 = medium, 3 = heavy. [healthWritten] and [hkSampleUUIDs] track the health-store mirror.
 * Only the store builds these (its lists are its own copies).
 */
@ConsistentCopyVisibility
data class PeriodEntry internal constructor(
    val start: Instant,
    val end: Instant?,
    val flowLevelRaw: Int,
    val symptoms: List<String>,
    val notes: String,
    val healthWritten: Boolean,
    val hkSampleUUIDs: List<String>,
    val updatedAt: Instant,
)

/**
 * One logged headache. [end] is null while it is still open. [severityRaw] uses HealthKit's raw
 * severity values (0 = unspecified, 1 = not present, 2 = mild, 3 = moderate, 4 = severe).
 * [importedHKUUID] is the health-store sample an imported entry came from. Only the store builds
 * these (its lists are its own copies).
 */
@ConsistentCopyVisibility
data class HeadacheEntry internal constructor(
    val onset: Instant,
    val end: Instant?,
    val severityRaw: Int,
    val symptoms: List<String>,
    val customSymptoms: List<String>,
    val factors: List<String>,
    val notes: String,
    val sourceRaw: String,
    val importedHKUUID: String?,
    val healthWritten: Boolean,
    val hkSampleUUIDs: List<String>,
    val updatedAt: Instant,
) {
    /** [sourceRaw] as a [HeadacheSource] ([HeadacheSource.USER] when unknown). */
    val source: HeadacheSource get() = HeadacheSource.of(sourceRaw)
}

/**
 * One day's frozen headache-risk score. [day] is the local start of the day the night ended on;
 * [nightKey] is the scored night's sleep-summary key, or [SleepEdit.DISTANT_PAST] when the day was
 * scored without one (it does not move when the time zone does, unlike [day]). [index] is 0–100,
 * [bandRaw] 0 = typical, 1 = elevated, 2 = flagged. [contributionsJSON] / [absentJSON] are kept as
 * given. [sleepRestaged], [sleepUpdatedAt] and [alerted] are the only things that change later.
 */
data class HeadacheRiskDay(
    val day: Instant,
    val nightKey: Instant = SleepEdit.DISTANT_PAST,
    val index: Double = 0.0,
    val bandRaw: Int = 0,
    val ringFeatureCount: Int = 0,
    val coverageFraction: Double = 0.0,
    val contributionsJSON: String = "",
    val absentJSON: String = "",
    val computedAt: Instant,
    val sleepUpdatedAt: Instant? = null,
    val sleepRestaged: Boolean = false,
    val alerted: Boolean = false,
    val postUnlock: Boolean = false,
    val updatedAt: Instant,
)

internal fun StoredPeriodEntryEntity.toPeriodEntry() = PeriodEntry(
    start = start, end = end, flowLevelRaw = flowLevelRaw, symptoms = symptoms, notes = notes,
    healthWritten = healthWritten, hkSampleUUIDs = hkSampleUUIDs, updatedAt = updatedAt,
)

internal fun StoredHeadacheEntryEntity.toHeadacheEntry() = HeadacheEntry(
    onset = onset, end = end, severityRaw = severityRaw, symptoms = symptoms, customSymptoms = customSymptoms,
    factors = factors, notes = notes, sourceRaw = sourceRaw, importedHKUUID = importedHKUUID,
    healthWritten = healthWritten, hkSampleUUIDs = hkSampleUUIDs, updatedAt = updatedAt,
)

/** The row for this day, every instant cut to the stored millisecond. */
internal fun HeadacheRiskDay.toEntity() = StoredHeadacheRiskEntity(
    day = day.toStoredMillis(), nightKey = nightKey.toStoredMillis(), index = index, bandRaw = bandRaw,
    ringFeatureCount = ringFeatureCount, coverageFraction = coverageFraction, contributionsJSON = contributionsJSON,
    absentJSON = absentJSON, computedAt = computedAt.toStoredMillis(), sleepUpdatedAt = sleepUpdatedAt?.toStoredMillis(),
    sleepRestaged = sleepRestaged, alerted = alerted, postUnlock = postUnlock, updatedAt = updatedAt.toStoredMillis(),
)

internal fun StoredHeadacheRiskEntity.toRiskDay() = HeadacheRiskDay(
    day = day, nightKey = nightKey, index = index, bandRaw = bandRaw, ringFeatureCount = ringFeatureCount,
    coverageFraction = coverageFraction, contributionsJSON = contributionsJSON, absentJSON = absentJSON,
    computedAt = computedAt, sleepUpdatedAt = sleepUpdatedAt, sleepRestaged = sleepRestaged, alerted = alerted,
    postUnlock = postUnlock, updatedAt = updatedAt,
)
