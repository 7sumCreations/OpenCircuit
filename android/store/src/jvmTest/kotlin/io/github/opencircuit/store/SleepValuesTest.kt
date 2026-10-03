package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The values the sleep store hands back instead of its rows: every column of the stored night,
 * its timelines decoded, and no byte array anywhere (a `ByteArray` compares by identity, so a value
 * holding one would never equal its own re-read).
 */
class SleepValuesTest {

    private fun t(epochSecond: Long): Instant = Instant.ofEpochSecond(epochSecond)

    @Test
    fun aStoredBasisThisBuildDoesNotKnowReadsAsUnknown() {
        assertEquals(SleepBasis.UNKNOWN, SleepBasis.fromStored(""))
        assertEquals(SleepBasis.MEASURED_ONLY, SleepBasis.fromStored("measuredOnly"))
        assertEquals(SleepBasis.ASSERTED_TAGGED, SleepBasis.fromStored("assertedTagged"))
        // Upstream's `SleepBasis(rawValue:)` is case-sensitive and has no other case.
        assertEquals(SleepBasis.UNKNOWN, SleepBasis.fromStored("MeasuredOnly"))
        assertEquals(SleepBasis.UNKNOWN, SleepBasis.fromStored("measuredAndAsserted"))
        assertEquals(listOf("", "measuredOnly", "assertedTagged"), SleepBasis.entries.map { it.rawValue })
    }

    @Test
    fun aRowReadsBackWithEveryColumnAndItsTimelinesDecoded() {
        // Timelines on the raw stored path: [start, end, stage] (+ provenance when not measured).
        val hypnogram = "[[1750000000,1750028800,0],[1750000000,1750003600,2],[1750003600,1750007200,3,1]]"
        val recorded = "[[1750000000,1750028800,0],[1750000000,1750007200,2]]"
        val row = StoredSleepSummaryEntity(
            id = 7, night = t(1_749_945_600), asleepMin = 420, deepMin = 90, lightMin = 270, remMin = 60, awakeMin = 30,
            efficiency = 0.875, inBedStart = t(1_750_000_000), inBedEnd = t(1_750_028_800), sleepOnset = t(1_750_000_600),
            sleepWake = t(1_750_027_000), updatedAt = t(1_750_030_000), skinTempC = 33.25, sleepScore = 81, stressScore = 12,
            feelScore = 6, hrDeep = 52, hrLight = 55, hrRem = 58, hrAwake = 64, movementLevels = listOf(0, 2, 5),
            hypnogramData = hypnogram.toByteArray(), osaAvgSpO2 = 95.5, osaMinSpO2 = 88.0, osaTimeBelow90Sec = 120.0, osaODI = 3.5,
            osaValidWindows = 40, editedInBedStart = t(1_749_999_000), editedInBedEnd = t(1_750_029_000), isManuallyEdited = true,
            widenedRecordedInBedStart = t(1_749_998_000), widenedRecordedInBedEnd = t(1_750_029_500),
            widenedRecordedOnset = t(1_749_999_500), widenedRecordedWake = t(1_750_029_400),
            recordedHypnogramData = recorded.toByteArray(), measuredAsleepSeconds = 3_600.0, assertedAsleepSeconds = 3_600.0,
            coverageFraction = 0.5, longestGapSeconds = 3_600.0, measuredEfficiency = 0.9, sleepBasis = "assertedTagged",
        )

        val night = row.toStoredNight(editedOnset = null)

        assertEquals(
            StoredNight(
                night = t(1_749_945_600), asleepMin = 420, deepMin = 90, lightMin = 270, remMin = 60, awakeMin = 30,
                efficiency = 0.875, inBedStart = t(1_750_000_000), inBedEnd = t(1_750_028_800), sleepOnset = t(1_750_000_600),
                sleepWake = t(1_750_027_000), updatedAt = t(1_750_030_000), skinTempC = 33.25, sleepScore = 81, stressScore = 12,
                feelScore = 6, hrDeep = 52, hrLight = 55, hrRem = 58, hrAwake = 64, movementLevels = listOf(0, 2, 5),
                hypnogram = listOf(
                    SleepSegment(t(1_750_000_000), t(1_750_028_800), SleepStage.IN_BED),
                    SleepSegment(t(1_750_000_000), t(1_750_003_600), SleepStage.ASLEEP_CORE),
                    SleepSegment(t(1_750_003_600), t(1_750_007_200), SleepStage.ASLEEP_DEEP, SleepProvenance.ASSERTED),
                ),
                osaAvgSpO2 = 95.5, osaMinSpO2 = 88.0, osaTimeBelow90Sec = 120.0, osaODI = 3.5, osaValidWindows = 40,
                editedInBedStart = t(1_749_999_000), editedInBedEnd = t(1_750_029_000), isManuallyEdited = true,
                widenedRecordedInBedStart = t(1_749_998_000), widenedRecordedInBedEnd = t(1_750_029_500),
                widenedRecordedOnset = t(1_749_999_500), widenedRecordedWake = t(1_750_029_400),
                recordedHypnogram = listOf(
                    SleepSegment(t(1_750_000_000), t(1_750_028_800), SleepStage.IN_BED),
                    SleepSegment(t(1_750_000_000), t(1_750_007_200), SleepStage.ASLEEP_CORE),
                ),
                measuredAsleepSeconds = 3_600.0, assertedAsleepSeconds = 3_600.0, coverageFraction = 0.5,
                longestGapSeconds = 3_600.0, measuredEfficiency = 0.9, sleepBasis = SleepBasis.ASSERTED_TAGGED,
                editedOnset = null,
            ),
            night,
        )
        // Two reads of the same row are equal values (a byte array would make them differ).
        assertEquals(night, row.copy().toStoredNight(editedOnset = null))
    }

    @Test
    fun anUnreadableTimelineReadsAsNoSegmentsAndTheRestOfTheRowIsKept() {
        val row = StoredSleepSummaryEntity(
            night = t(1_749_945_600), asleepMin = 420, hypnogramData = "not json".toByteArray(),
            recordedHypnogramData = ByteArray(0), sleepBasis = "measuredOnly",
        )

        val night = row.toStoredNight(editedOnset = null)

        assertEquals(emptyList(), night.hypnogram)
        assertEquals(emptyList(), night.recordedHypnogram)
        assertEquals(420, night.asleepMin)
        assertEquals(SleepBasis.MEASURED_ONLY, night.sleepBasis)
    }

    @Test
    fun noValueTheSleepStoreHandsBackOrTakesHoldsAByteArray() {
        for (type in listOf(StoredNight::class.java, SleepNightExtras::class.java)) {
            val bytes = type.declaredFields.filter { it.type == ByteArray::class.java }.map { it.name }
            assertTrue(bytes.isEmpty(), "${type.simpleName} holds byte arrays: $bytes")
        }
    }

    @Test
    fun aStoredNightsListsCannotBeChangedThroughACast() {
        val night = StoredSleepSummaryEntity(
            movementLevels = arrayListOf(1, 2),
            hypnogramData = "[[1750000000,1750003600,2]]".toByteArray(),
        ).toStoredNight(editedOnset = null)

        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (night.movementLevels as MutableList<Int>).add(3) }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (night.hypnogram as MutableList<SleepSegment>).clear() }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (night.recordedHypnogram as MutableList<SleepSegment>).clear() }
    }
}
