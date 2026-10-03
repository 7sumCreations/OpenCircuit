package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.store.EpochArchiveMarks
import io.github.opencircuit.store.StoredEpochArchive
import io.github.opencircuit.store.hex
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The stored form of one ring's epoch archive and its drain facts.
 *
 * Kotlin-only. Upstream (ios/OpenCircuit/Store/EpochArchiveStore.swift @ b1c2fdd) keeps the
 * archive as the raw bytes `EpochArchive.encode` writes (23 per record) and each drain fact in its
 * own `UserDefaults` key; here they are one value, the bytes as lowercase hex. The bytes decode as
 * upstream's do (`EpochArchive.decode`: a trailing partial record is dropped). A drain fact that
 * cannot be read reads as upstream's own default for a missing key (no date, 0 drains, no verdict)
 * and never costs the archive, which nothing can fetch again.
 */
class EpochArchiveCodecTest {

    // A real `0x4c` page (as in CaptureToStoreEndToEndTest); the hex below is the page's own text
    // with its 3-byte head (opcode, 00, countdown) and 1-byte trailer cut: the six 23-byte records
    // as they arrived.
    private val page = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"
    private val records: List<BulkRecord> = BulkSleep.recordsFromPage(hex(page))
    private val recordsHex = page.substring(6, 6 + 6 * 46)

    private val marks = EpochArchiveMarks(
        lastDrainAt = Instant.ofEpochMilli(1_790_000_000_123),
        headAt = Instant.ofEpochMilli(1_789_990_000_000),
        unmovedDrains = 3,
        hrvPooling = BulkSleep.HRVPooling.AGREE,
    )

    @Test
    fun theStoredFormIsTheRecordBytesAsHexAndTheDrainFacts() {
        assertEquals(6, records.size)
        assertEquals(
            """{"headAt":1789990000000,"hrvPooling":"agree","lastDrainAt":1790000000123,"records":"$recordsHex","unmovedDrains":3}""",
            EpochArchiveCodec.encode(StoredEpochArchive(records, marks)),
        )
        assertEquals("""{"records":"","unmovedDrains":0}""", EpochArchiveCodec.encode(StoredEpochArchive.EMPTY))
    }

    @Test
    fun anArchiveRoundTrips() {
        for (archive in listOf(
            StoredEpochArchive(records, marks),
            StoredEpochArchive(records, marks.copy(hrvPooling = BulkSleep.HRVPooling.DISAGREE, lastDrainAt = null)),
            StoredEpochArchive.EMPTY,
        )) {
            assertEquals(archive, readable(EpochArchiveCodec.decode(EpochArchiveCodec.encode(archive))))
        }
    }

    @Test
    fun theRecordsAreCopiedInSoALaterChangeToTheCallersListChangesNothing() {
        val list = records.toMutableList()
        val held = StoredEpochArchive(list, marks)
        list.clear()
        assertEquals(records, held.records)
        assertEquals(records, held.copy(marks = EpochArchiveMarks.NONE).records)
    }

    @Test
    fun aTrailingPartialRecordIsDroppedAsUpstreamDecodes() {
        val read = readable(EpochArchiveCodec.decode("""{"records":"${recordsHex}0102030405","unmovedDrains":0}"""))
        assertEquals(records, read.records)
    }

    @Test
    fun recordBytesThatAreNotTheStoredHexMakeTheArchiveUnreadable() {
        for (records in listOf(
            "\"${recordsHex.uppercase()}\"",
            "\"${recordsHex.dropLast(1)}\"",
            "\"${recordsHex.substring(0, 4)} ${recordsHex.substring(4)}\"",
            "\"zz\"",
            "\"０１\"",
            "5",
            "null",
        )) {
            val raw = """{"records":$records,"unmovedDrains":0}"""
            assertUnreadable(EpochArchiveCodec.decode(raw), raw)
        }
        for (raw in listOf("""{"unmovedDrains":0}""", "[]", "\"$recordsHex\"")) {
            assertUnreadable(EpochArchiveCodec.decode(raw), raw)
        }
    }

    @Test
    fun aDrainFactThatCannotBeReadReadsAsItsDefaultAndKeepsTheArchive() {
        val raw = """{"headAt":"x","hrvPooling":"noEvidence","lastDrainAt":1.5,"records":"$recordsHex","unmovedDrains":5000000000}"""
        assertEquals(StoredEpochArchive(records, EpochArchiveMarks.NONE), readable(EpochArchiveCodec.decode(raw)))

        val other = """{"headAt":0,"hrvPooling":7,"lastDrainAt":-5,"records":"$recordsHex","unmovedDrains":"3"}"""
        assertEquals(StoredEpochArchive(records, EpochArchiveMarks.NONE), readable(EpochArchiveCodec.decode(other)))

        // A number past every bound the number parser has (an exponent past 32 bits, or one that
        // overflows the digit count) is a fact that cannot be read, not a damaged archive.
        val huge = """{"headAt":10e2147483647,"lastDrainAt":1e2147483647,"records":"$recordsHex","unmovedDrains":1e2147483648}"""
        assertEquals(StoredEpochArchive(records, EpochArchiveMarks.NONE), readable(EpochArchiveCodec.decode(huge)))

        // Missing facts are the defaults too.
        assertEquals(StoredEpochArchive(records, EpochArchiveMarks.NONE), readable(EpochArchiveCodec.decode("""{"records":"$recordsHex"}""")))

        // A negative drain count is not a count: the default, and the archive is kept.
        val negative = """{"records":"$recordsHex","unmovedDrains":-1}"""
        assertEquals(StoredEpochArchive(records, EpochArchiveMarks.NONE), readable(EpochArchiveCodec.decode(negative)))
    }

    /**
     * Kotlin-only: marks that would not read back as themselves are refused when built. A time stored as 0 ms or less reads as no date (upstream's `t > 0`),
     * a time past 64-bit milliseconds cannot be written, and a drain count below 0 is not a count.
     */
    @Test
    fun marksThatWouldNotReadBackAsThemselvesAreRefusedWhenBuilt() {
        val unstorable = listOf(
            Instant.EPOCH,
            Instant.ofEpochMilli(-1),
            Instant.ofEpochSecond(0, 999_999), // cut to 0 ms when stored
            Instant.ofEpochSecond(-1_000_000_000_000L),
            Instant.MAX,
        )
        for (t in unstorable) {
            assertFailsWith<IllegalArgumentException>("lastDrainAt $t") { EpochArchiveMarks(lastDrainAt = t) }
            assertFailsWith<IllegalArgumentException>("headAt $t") { EpochArchiveMarks(headAt = t) }
        }
        assertFailsWith<IllegalArgumentException> { EpochArchiveMarks(unmovedDrains = -1) }
        assertFailsWith<IllegalArgumentException> { EpochArchiveMarks(unmovedDrains = Int.MIN_VALUE) }
    }

    /** Every boundary value the marks accept round-trips through the stored form unchanged. */
    @Test
    fun marksAtEveryAcceptedBoundaryRoundTrip() {
        val times = listOf(null, Instant.ofEpochMilli(1), Instant.ofEpochSecond(0, 1_000_000), Instant.ofEpochMilli(Long.MAX_VALUE))
        val counts = listOf(0, 1, Int.MAX_VALUE)
        val verdicts = listOf(null, BulkSleep.HRVPooling.AGREE, BulkSleep.HRVPooling.DISAGREE)
        for (t in times) for (n in counts) for (v in verdicts) {
            val archive = StoredEpochArchive(records, EpochArchiveMarks(lastDrainAt = t, headAt = t, unmovedDrains = n, hrvPooling = v))
            assertEquals(archive, readable(EpochArchiveCodec.decode(EpochArchiveCodec.encode(archive))))
        }
    }
}
