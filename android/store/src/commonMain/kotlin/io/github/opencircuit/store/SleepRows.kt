package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.SleepSegmentCodec
import java.time.Instant
import java.util.Collections

// The one builder of StoredNight and StoredNapRecord from a stored row. A nap's segment lists are
// the segment codec's text as UTF-8 bytes; one that cannot be read is null, the nap coarse
// (upstream `StoredNap.stagedSegments`, LocalStore.swift:749-751). A night's timeline is decoded with upstream's codec,
// which never throws: an unreadable blob reads as no segments, as upstream `hypnogram(night:)`
// (ios/OpenCircuit/Store/LocalStore.swift:2074-2077 @ b1c2fdd). Each list is the value's own copy
// and cannot be changed through a cast (Swift's arrays copy; the column reader hands back an
// ArrayList). Code that needs a row's raw bytes reads the entity and compares them with
// `contentEquals`, never `==` (a `ByteArray`'s `==` is identity).

/** This row as a value; [editedOnset] is the onset saved with the row's edit (null for an unedited row). */
internal fun StoredSleepSummaryEntity.toStoredNight(editedOnset: Instant?) = StoredNight(
    night = night, asleepMin = asleepMin, deepMin = deepMin, lightMin = lightMin, remMin = remMin, awakeMin = awakeMin,
    efficiency = efficiency, inBedStart = inBedStart, inBedEnd = inBedEnd, sleepOnset = sleepOnset, sleepWake = sleepWake,
    updatedAt = updatedAt, skinTempC = skinTempC, sleepScore = sleepScore, stressScore = stressScore, feelScore = feelScore,
    hrDeep = hrDeep, hrLight = hrLight, hrRem = hrRem, hrAwake = hrAwake, movementLevels = movementLevels.ownCopy(),
    hypnogram = decodedTimeline(hypnogramData), osaAvgSpO2 = osaAvgSpO2, osaMinSpO2 = osaMinSpO2,
    osaTimeBelow90Sec = osaTimeBelow90Sec, osaODI = osaODI, osaValidWindows = osaValidWindows,
    editedInBedStart = editedInBedStart, editedInBedEnd = editedInBedEnd, isManuallyEdited = isManuallyEdited,
    widenedRecordedInBedStart = widenedRecordedInBedStart, widenedRecordedInBedEnd = widenedRecordedInBedEnd,
    widenedRecordedOnset = widenedRecordedOnset, widenedRecordedWake = widenedRecordedWake,
    recordedHypnogram = decodedTimeline(recordedHypnogramData), measuredAsleepSeconds = measuredAsleepSeconds,
    assertedAsleepSeconds = assertedAsleepSeconds, coverageFraction = coverageFraction, longestGapSeconds = longestGapSeconds,
    measuredEfficiency = measuredEfficiency, sleepBasis = SleepBasis.fromStored(sleepBasis),
    editedOnset = editedOnset,
)

/** This nap row as a value, its segment lists decoded. */
internal fun StoredNapEntity.toStoredNapRecord() = StoredNapRecord(
    start = start, end = end, asleepMin = asleepMin, isLongNap = isLongNap, healthWritten = healthWritten,
    updatedAt = updatedAt, isManuallyEdited = isManuallyEdited, isManuallyAdded = isManuallyAdded,
    segments = decodedNapSegments(napSegmentsData), editedStart = editedStart, editedEnd = editedEnd,
    recordedSegments = decodedNapSegments(recordedNapSegmentsData), healthWrittenStart = healthWrittenStart, healthWrittenEnd = healthWrittenEnd,
)

/** A nap's segments as stored: the segment codec's text in UTF-8. */
internal fun napSegmentsBytes(segments: List<SleepSegment>): ByteArray = SleepSegmentCodec.encode(segments).encodeToByteArray()

/**
 * A nap's stored segments, or null — the nap is coarse — when none are stored or they cannot be read,
 * as upstream's `try?` decode (LocalStore.swift:750). Bytes that are not UTF-8 are unreadable too.
 */
private fun decodedNapSegments(data: ByteArray?): List<SleepSegment>? {
    if (data == null) return null
    val text = try {
        data.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return null
    }
    return when (val d = SleepSegmentCodec.decode(text)) {
        is Decoded.Readable -> d.value.ownCopy()
        is Decoded.Unreadable -> null
    }
}

/** The stored timeline's segments; none when the bytes are empty or unreadable. */
internal fun decodedTimeline(data: ByteArray): List<SleepSegment> = SleepHypnogramCodec.decode(data).ownCopy()

private fun <T> List<T>.ownCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))
