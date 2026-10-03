package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepSegment
import java.util.Collections

// The one builder of StoredNight from a stored row. A timeline is decoded with upstream's codec,
// which never throws: an unreadable blob reads as no segments, as upstream `hypnogram(night:)`
// (ios/OpenCircuit/Store/LocalStore.swift:2074-2077 @ b1c2fdd). Each list is the value's own copy
// and cannot be changed through a cast (Swift's arrays copy; the column reader hands back an
// ArrayList). Code that needs a row's raw bytes reads the entity and compares them with
// `contentEquals`, never `==` (a `ByteArray`'s `==` is identity).

internal fun StoredSleepSummaryEntity.toStoredNight() = StoredNight(
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
)

/** The stored timeline's segments; none when the bytes are empty or unreadable. */
internal fun decodedTimeline(data: ByteArray): List<SleepSegment> = SleepHypnogramCodec.decode(data).ownCopy()

private fun <T> List<T>.ownCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))
