package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A stored nap as the value the store hands back, read from upstream's model
 * (ios/OpenCircuit/Store/LocalStore.swift `StoredNap` :695-751 @ b1c2fdd), which has no test of
 * its own: the effective window is the edit when there is one, the duration is whole minutes cut
 * toward zero and never negative, and a segment list that cannot be read is no list at all — the
 * nap is then coarse — never an error.
 */
class StoredNapRecordTest {

    private fun ms(v: Long): Instant = Instant.ofEpochMilli(v)

    private val start = ms(1_750_050_000_000)
    private val end = ms(1_750_055_400_000) // 90 min

    // Built on the raw stored path: the segment codec's JSON text, as UTF-8 bytes.
    private val stagedText =
        """[{"start":1750050000000,"end":1750055400000,"stage":"inBed"},""" +
            """{"start":1750050000000,"end":1750052000000,"stage":"asleepDeep"},""" +
            """{"start":1750052000000,"end":1750055400000,"stage":"asleepREM","provenance":"asserted"}]"""

    @Test
    fun aNapRowReadsBackWithEveryColumnAndItsSegmentsDecoded() {
        val row = StoredNapEntity(
            id = 3, start = start, end = end, asleepMin = 88, isLongNap = false, healthWritten = true,
            updatedAt = ms(1_750_060_000_000), isManuallyEdited = true, isManuallyAdded = false,
            napSegmentsData = """[{"start":1750049000000,"end":1750056000000,"stage":"asleepCore"}]""".toByteArray(),
            editedStart = ms(1_750_049_000_000), editedEnd = ms(1_750_056_000_000),
            recordedNapSegmentsData = stagedText.toByteArray(),
            healthWrittenStart = ms(1_750_050_000_000), healthWrittenEnd = ms(1_750_055_400_000),
        )

        assertEquals(
            StoredNapRecord(
                start = start, end = end, asleepMin = 88, isLongNap = false, healthWritten = true,
                updatedAt = ms(1_750_060_000_000), isManuallyEdited = true, isManuallyAdded = false,
                segments = listOf(SleepSegment(ms(1_750_049_000_000), ms(1_750_056_000_000), SleepStage.ASLEEP_CORE)),
                editedStart = ms(1_750_049_000_000), editedEnd = ms(1_750_056_000_000),
                recordedSegments = listOf(
                    SleepSegment(start, end, SleepStage.IN_BED),
                    SleepSegment(start, ms(1_750_052_000_000), SleepStage.ASLEEP_DEEP),
                    SleepSegment(ms(1_750_052_000_000), end, SleepStage.ASLEEP_REM, SleepProvenance.ASSERTED),
                ),
                healthWrittenStart = ms(1_750_050_000_000), healthWrittenEnd = ms(1_750_055_400_000),
            ),
            row.toStoredNapRecord(),
        )
    }

    /** Upstream `effectiveStart` / `effectiveEnd` (:742-743): the edit when present, else the detected window. */
    @Test
    fun theEffectiveWindowIsTheEditWhenThereIsOneAndTheDetectedWindowOtherwise() {
        val unedited = StoredNapEntity(start = start, end = end).toStoredNapRecord()
        assertEquals(start to end, unedited.effectiveStart to unedited.effectiveEnd)

        val edited = StoredNapEntity(start = start, end = end, editedStart = ms(1_750_049_000_000), editedEnd = ms(1_750_056_000_000))
            .toStoredNapRecord()
        assertEquals(ms(1_750_049_000_000) to ms(1_750_056_000_000), edited.effectiveStart to edited.effectiveEnd)

        // Each edge stands alone, as Swift's `??` does.
        val halfEdited = StoredNapEntity(start = start, end = end, editedEnd = ms(1_750_056_000_000)).toStoredNapRecord()
        assertEquals(start to ms(1_750_056_000_000), halfEdited.effectiveStart to halfEdited.effectiveEnd)
    }

    /**
     * Upstream `durationMin` (:744): `max(Int(seconds / 60), 0)` — whole minutes cut toward zero, never
     * rounded, and never below zero.
     */
    @Test
    fun theDurationIsWholeMinutesOfTheEffectiveWindowCutTowardZeroAndNeverNegative() {
        fun duration(fromMs: Long, toMs: Long) = StoredNapEntity(start = ms(fromMs), end = ms(toMs)).toStoredNapRecord().durationMin

        assertEquals(90, duration(0, 5_400_000))
        assertEquals(90, duration(0, 5_459_999)) // 90 min 59.999 s: cut, not rounded to 91
        assertEquals(0, duration(0, 59_999))
        assertEquals(0, duration(0, 0))
        assertEquals(0, duration(5_400_000, 0)) // reversed: −90 min → 0
        assertEquals(1, duration(-60_000, 0)) // before 1970 the same
        assertEquals(
            30,
            StoredNapEntity(start = start, end = end, editedStart = ms(1_750_053_600_000)).toStoredNapRecord().durationMin,
            "the duration is of the effective window",
        )
    }

    /** Upstream `stagedSegments` (:748): `try?` on the decode — anything unreadable is nil, i.e. coarse. */
    @Test
    fun segmentsThatCannotBeReadAreNoSegmentsAndTheNapReadsAsCoarse() {
        val unreadable = listOf(
            "not json",
            """[{"start":1750050000000,"end":1750055400000,"stage":"napping"}]""",
            """[{"start":1750050000000,"stage":"inBed"}]""",
            """{"start":1750050000000,"end":1750055400000,"stage":"inBed"}""",
            "",
        ).map { it.toByteArray() } + listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0x5B, 0xFF.toByte(), 0x5D),
            // Not UTF-8 inside a label: read leniently, U+FFFD would pass as an unknown label and keep the
            // segment; Foundation's decoder refuses the bytes outright (measured, Swift 6.3.2: "Unable to
            // convert data to a string").
            """[{"start":1750050000000,"end":1750055400000,"stage":"inBed","provenance":"""".toByteArray() +
                byteArrayOf(0xFF.toByte()) + "\"}]".toByteArray(),
        )

        for (bytes in unreadable) {
            val nap = StoredNapEntity(start = start, end = end, napSegmentsData = bytes, recordedNapSegmentsData = bytes).toStoredNapRecord()
            assertNull(nap.segments, bytes.decodeToString())
            assertNull(nap.recordedSegments, bytes.decodeToString())
        }
        val none = StoredNapEntity(start = start, end = end).toStoredNapRecord()
        assertNull(none.segments)
        assertNull(none.recordedSegments)
    }

    /** An empty stored list decodes to an empty list — upstream's decoder returns `[]`, not nil, for `[]`. */
    @Test
    fun anEmptyStoredListReadsAsNoSegmentsButNotAsCoarse() {
        val nap = StoredNapEntity(start = start, end = end, napSegmentsData = "[]".toByteArray()).toStoredNapRecord()
        assertEquals(emptyList(), nap.segments)
    }

    /** The store's encoding is the codec's text as UTF-8, and it reads back as the same segments. */
    @Test
    fun segmentsWrittenByTheStoreReadBackAsTheSameSegments() {
        val segments = listOf(
            SleepSegment(start, end, SleepStage.IN_BED),
            SleepSegment(start, end, SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
        )
        val bytes = napSegmentsBytes(segments)

        assertEquals(
            """[{"start":1750050000000,"end":1750055400000,"stage":"inBed"},""" +
                """{"start":1750050000000,"end":1750055400000,"stage":"asleepCore","provenance":"""" +
                SleepProvenance.ASSERTED_COVERAGE_UNKNOWN.rawValue + """"}]""",
            bytes.decodeToString(),
        )
        assertEquals(segments, StoredNapEntity(start = start, end = end, napSegmentsData = bytes).toStoredNapRecord().segments)
    }
}
