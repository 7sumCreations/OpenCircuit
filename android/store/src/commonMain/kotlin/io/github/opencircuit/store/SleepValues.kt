package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import java.time.Duration
import java.time.Instant

// What the sleep store takes and hands back. Ported from ios/OpenCircuit/Store/LocalStore.swift
// (@ b1c2fdd): `SleepNightExtras` (:1450-1465), `StoreError` (:1480-1490), `SleepBasis` (:377-387),
// and the stored night (`StoredSleepSummary`, :86-230) as a plain value. Upstream hands out its
// SwiftData model objects; here the rows stay internal and callers get values whose timelines are
// already decoded, so no byte array (compared by identity) ever leaves the store.

/**
 * Which basis a stored night's minutes were computed on. Stored as its raw string, so a value this
 * build does not know reads as [UNKNOWN] rather than as one of the known bases.
 */
enum class SleepBasis(val rawValue: String) {
    /** Written before the basis was known, or by an edit whose recording is gone: the split is unknown. */
    UNKNOWN(""),

    /** Every segment is measured — every unedited, staged night. */
    MEASURED_ONLY("measuredOnly"),

    /** The night carries user-asserted time, tagged, with the split in the provenance columns. */
    ASSERTED_TAGGED("assertedTagged"),
    ;

    companion object {
        /** The basis named by [raw], or [UNKNOWN] for any string this build does not know. */
        fun fromStored(raw: String): SleepBasis = entries.firstOrNull { it.rawValue == raw } ?: UNKNOWN
    }
}

/**
 * What a staging pass computed besides the stage minutes. A zero score or temperature means "not
 * computed this pass" and keeps the stored value; [skinTempWithheld] means the night was judged and
 * its temperature rejected, which clears a stored one. [hypnogram] is the segments the minutes were
 * rolled up from: it is stored with them, an empty list as "no timeline recorded".
 */
data class SleepNightExtras(
    val skinTempC: Double = 0.0,
    val skinTempWithheld: Boolean = false,
    val sleepScore: Int = 0,
    val stressScore: Int = 0,
    val hrByStage: Map<SleepStage, Int> = emptyMap(),
    val movementLevels: List<Int> = emptyList(),
    val hypnogram: List<SleepSegment> = emptyList(),
)

/**
 * One stored night, keyed by [night] (the start of its key day). Every instant column that was never
 * written is [SleepEdit.DISTANT_PAST]; the provenance figures are −1 when not computed.
 *
 * [hypnogram] is the night's current timeline and [recordedHypnogram] the ring's own reading, kept
 * where an edit cannot reach it; a stored timeline this build cannot read reads as no segments, and
 * the rest of the night is still returned. Only the store builds these (its lists are its own copies).
 *
 * [editedOnset] is the onset saved with the wearer's edit, read with the row; null when the night is
 * not edited, or no onset this build can read was saved with the edit. The `current…` edges are
 * what the night shows: the edited ones once [isManuallyEdited], the recorded ones before.
 */
@ConsistentCopyVisibility
data class StoredNight internal constructor(
    val night: Instant,
    val asleepMin: Int,
    val deepMin: Int,
    val lightMin: Int,
    val remMin: Int,
    val awakeMin: Int,
    val efficiency: Double,
    val inBedStart: Instant,
    val inBedEnd: Instant,
    val sleepOnset: Instant,
    val sleepWake: Instant,
    val updatedAt: Instant,
    val skinTempC: Double,
    val sleepScore: Int,
    val stressScore: Int,
    val feelScore: Int,
    val hrDeep: Int,
    val hrLight: Int,
    val hrRem: Int,
    val hrAwake: Int,
    val movementLevels: List<Int>,
    val hypnogram: List<SleepSegment>,
    val osaAvgSpO2: Double,
    val osaMinSpO2: Double,
    val osaTimeBelow90Sec: Double,
    val osaODI: Double,
    val osaValidWindows: Int,
    val editedInBedStart: Instant,
    val editedInBedEnd: Instant,
    val isManuallyEdited: Boolean,
    val widenedRecordedInBedStart: Instant,
    val widenedRecordedInBedEnd: Instant,
    val widenedRecordedOnset: Instant,
    val widenedRecordedWake: Instant,
    val recordedHypnogram: List<SleepSegment>,
    val measuredAsleepSeconds: Double,
    val assertedAsleepSeconds: Double,
    val coverageFraction: Double,
    val longestGapSeconds: Double,
    val measuredEfficiency: Double,
    val sleepBasis: SleepBasis,
    val editedOnset: Instant?,
) {
    // Upstream's model accessors (LocalStore.swift:281-335). The edit stores one edited end, which is
    // both the in-bed end and the wake.

    /** The bedtime the night shows: the edited one once the wearer edited it, else the recorded one. */
    val currentInBedStart: Instant get() = if (isManuallyEdited) editedInBedStart else inBedStart

    /** The in-bed end the night shows: the edited end once edited, else the recorded one. */
    val currentInBedEnd: Instant get() = if (isManuallyEdited) editedInBedEnd else inBedEnd

    /**
     * The onset the night shows. Once edited: the onset saved with the edit, or — when none was saved
     * or it cannot be read — the recorded onset clamped into the edited window (the edited bedtime
     * when no onset was recorded), never the bedtime itself, which would read as falling asleep at once.
     */
    val currentOnset: Instant
        get() {
            if (!isManuallyEdited) return sleepOnset
            editedOnset?.let { return it }
            val recorded = if (sleepOnset > SleepEdit.DISTANT_PAST) sleepOnset else editedInBedStart
            return minOf(maxOf(recorded, editedInBedStart), editedInBedEnd)
        }

    /** The wake the night shows: the edited end once edited, else the recorded wake. */
    val currentWake: Instant get() = if (isManuallyEdited) editedInBedEnd else sleepWake

    /** The window the ring recorded, as the save stored it; an edit never moves it. */
    val recordedWindow: SleepEdit.RecordedWindow get() = SleepEdit.RecordedWindow(inBedStart, inBedEnd, sleepOnset, sleepWake)

    /**
     * The recorded window an edit is clamped within: [recordedWindow] widened outward by any fuller
     * staging kept beside an edited or a fuller stored night since. Only the editor anchors on it.
     */
    val clampWindow: SleepEdit.RecordedWindow
        get() = clampWindowOf(
            recordedWindow,
            SleepEdit.RecordedWindow(widenedRecordedInBedStart, widenedRecordedInBedEnd, widenedRecordedOnset, widenedRecordedWake),
        )

    /**
     * The stored minutes as a staging summary: in bed recovered as asleep / [efficiency] so the shown
     * efficiency matches, or asleep + awake when the efficiency is not positive. An in-bed time past
     * what a `Duration` of nanoseconds holds (~292 years, from a vanishing efficiency) is capped there.
     */
    val asSummary: SleepStaging.Summary
        get() {
            val light = Duration.ofMinutes(lightMin.toLong())
            val deep = Duration.ofMinutes(deepMin.toLong())
            val rem = Duration.ofMinutes(remMin.toLong())
            val awake = Duration.ofMinutes(awakeMin.toLong())
            val asleep = light.plus(deep).plus(rem)
            val inBed = if (efficiency > 0) Duration.ofNanos(Math.round(asleep.seconds / efficiency * 1e9)) else asleep.plus(awake)
            return SleepStaging.Summary(inBed = inBed, awake = awake, light = light, deep = deep, rem = rem)
        }
}

/** [recorded] widened outward by [widened] (upstream `sleepEditClampWindow`), or [recorded] when that widens nothing. */
internal fun clampWindowOf(recorded: SleepEdit.RecordedWindow, widened: SleepEdit.RecordedWindow): SleepEdit.RecordedWindow =
    SleepEdit.widenRecorded(stored = recorded, incoming = widened) ?: recorded

/** Why a sleep write was refused (upstream `StoreError`). Nothing was written when one is thrown. */
sealed class SleepStoreException(message: String) : Exception(message) {
    /**
     * The one-time move of stored nights onto their wake day has not succeeded yet, so a night
     * written under the new key could split a night across two rows. Deferred, not lost: the next
     * drain stages the night again.
     */
    class NightKeyMigrationPending : SleepStoreException("the move of stored nights onto their wake day has not completed")

    /**
     * The move of stored nights onto their wake day found two rows that belong to one day; it
     * changed nothing.
     */
    class NightKeyMigrationUnsafe : SleepStoreException("two stored nights belong to the same wake day; nothing was moved")
}
