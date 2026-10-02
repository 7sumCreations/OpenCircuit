package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `SleepHypnogramCodec` is an ON-DISK format: these bytes sit in every stored night. The format-lock
 * test is what stops a refactor from silently changing what stored nights decode to.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepHypnogramCodecTests.swift
 * (@ b1c2fdd) — all 12 tests. Swift `Data` is a `ByteArray`; the expected strings are upstream's,
 * compared whole as UTF-8.
 */
class SleepHypnogramCodecTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(offset: Long): Instant = t0.plusSeconds(offset)
    private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    // Round trip

    @Test
    fun roundTripIsExact() {
        val segments = listOf(
            SleepSegment(at(0), at(150), SleepStage.IN_BED),
            SleepSegment(at(150), at(600), SleepStage.AWAKE),
            SleepSegment(at(600), at(3_000), SleepStage.ASLEEP_CORE),
            SleepSegment(at(3_000), at(5_400), SleepStage.ASLEEP_DEEP),
            SleepSegment(at(5_400), at(9_000), SleepStage.ASLEEP_REM),
        )
        assertEquals(segments, SleepHypnogramCodec.decode(SleepHypnogramCodec.encode(segments)))
    }

    @Test
    fun everyStageSurvivesRoundTrip() {
        // Guards the code table: a renumbered case would decode as a different stage.
        for ((i, stage) in SleepStage.entries.withIndex()) {
            val seg = SleepSegment(at(i * 300L), at(i * 300L + 150), stage)
            assertEquals(listOf(seg), SleepHypnogramCodec.decode(SleepHypnogramCodec.encode(listOf(seg))), "stage ${stage.rawValue} did not survive the round trip")
        }
    }

    @Test
    fun emptySegmentsEncodeToEmptyJSONArray() {
        assertEquals("[]", utf8(SleepHypnogramCodec.encode(emptyList())))
        assertEquals(emptyList(), SleepHypnogramCodec.decode(SleepHypnogramCodec.encode(emptyList())))
    }

    // On-disk format lock

    @Test
    fun onDiskFormatIsPinnedToExactBytes() {
        val segments = listOf(
            SleepSegment(t0, at(150), SleepStage.ASLEEP_DEEP),
            SleepSegment(at(150), at(300), SleepStage.ASLEEP_CORE),
        )
        assertEquals(
            "[[1700000000,1700000150,3],[1700000150,1700000300,2]]",
            utf8(SleepHypnogramCodec.encode(segments)),
            "On-disk hypnogram format changed. Nights already stored on user devices are in the OLD format — " +
                "changing this encoding requires a migration, not an edit to this expectation.",
        )
    }

    @Test
    fun stageCodesArePinned() {
        // Spelled out separately from the byte lock so a renumbering names the offending stage.
        val expected = mapOf(
            SleepStage.IN_BED to 0, SleepStage.AWAKE to 1, SleepStage.ASLEEP_CORE to 2,
            SleepStage.ASLEEP_DEEP to 3, SleepStage.ASLEEP_REM to 4,
        )
        for ((stage, code) in expected) {
            val data = SleepHypnogramCodec.encode(listOf(SleepSegment(t0, at(150), stage)))
            assertEquals("[[1700000000,1700000150,$code]]", utf8(data), "stage ${stage.rawValue} must stay wire code $code")
        }
    }

    // Defensive decode (never throws, never fabricates)

    @Test
    fun emptyDataDecodesToEmpty() {
        assertEquals(emptyList(), SleepHypnogramCodec.decode(ByteArray(0)))
    }

    @Test
    fun malformedJSONDecodesToEmpty() {
        for (junk in listOf("not json at all", "{\"night\":1}", "[", "[[1,2,\"deep\"]]", "[1,2,3]")) {
            assertEquals(emptyList(), SleepHypnogramCodec.decode(junk.toByteArray(Charsets.UTF_8)), "malformed payload $junk should decode to []")
        }
    }

    @Test
    fun unknownStageCodeDropsOnlyThatSegment() {
        val data = "[[1700000000,1700000150,3],[1700000150,1700000300,99]]".toByteArray(Charsets.UTF_8)
        assertEquals(listOf(SleepSegment(t0, at(150), SleepStage.ASLEEP_DEEP)), SleepHypnogramCodec.decode(data))
    }

    @Test
    fun wrongArityDropsOnlyThatSegment() {
        val data = "[[1700000000,1700000150],[1700000150,1700000300,2]]".toByteArray(Charsets.UTF_8)
        assertEquals(listOf(SleepSegment(at(150), at(300), SleepStage.ASLEEP_CORE)), SleepHypnogramCodec.decode(data))
    }

    @Test
    fun reversedAndZeroLengthSegmentsAreDropped() {
        val data = "[[1700000150,1700000000,2],[1700000000,1700000000,2],[1700000000,1700000150,1]]".toByteArray(Charsets.UTF_8)
        assertEquals(listOf(SleepSegment(t0, at(150), SleepStage.AWAKE)), SleepHypnogramCodec.decode(data))
    }

    @Test
    fun encodeSkipsSegmentsDecodeWouldRefuse() {
        // A stored segment we would not read back gives a night whose stored form disagrees with its
        // loaded form; encode must not create one.
        val segments = listOf(
            SleepSegment(at(150), at(0), SleepStage.ASLEEP_CORE), // reversed
            SleepSegment(at(300), at(300), SleepStage.ASLEEP_CORE), // zero length
            SleepSegment(at(600), at(750), SleepStage.ASLEEP_REM), // good
        )
        val encoded = SleepHypnogramCodec.encode(segments)
        assertEquals("[[1700000600,1700000750,4]]", utf8(encoded))
        assertEquals(listOf(segments[2]), SleepHypnogramCodec.decode(encoded))
    }

    @Test
    fun encodeIsIdempotentThroughDecode() {
        val segments = listOf(
            SleepSegment(t0, at(150), SleepStage.ASLEEP_DEEP),
            SleepSegment(at(150), at(300), SleepStage.ASLEEP_REM),
        )
        val once = SleepHypnogramCodec.encode(segments)
        val twice = SleepHypnogramCodec.encode(SleepHypnogramCodec.decode(once))
        assertContentEquals(once, twice)
    }
}
