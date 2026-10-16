package io.github.opencircuit.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How records are cut into Health Connect calls: order kept, a record-count bound and a byte bound. */
class ChunkerTest {

    @Test
    fun nothingMakesNoCall() {
        assertEquals(emptyList(), Chunker.chunks(emptyList<Int>()) { 1L })
    }

    @Test
    fun aThousandRecordsMakeOneCallAndTheThousandAndFirstStartsTheNext() {
        val thousand = (1..1000).toList()
        assertEquals(listOf(thousand), Chunker.chunks(thousand) { 1L })
        assertEquals(listOf(thousand, listOf(1001)), Chunker.chunks((1..1001).toList()) { 1L })
    }

    @Test
    fun theByteBoundCutsBeforeTheRecordThatWouldCrossItInOrder() {
        // 3 + 3 fits a bound of 6; the next 1 would cross it.
        assertEquals(listOf(listOf(3L, 3L), listOf(1L, 5L), listOf(2L)), Chunker.chunks(listOf(3L, 3L, 1L, 5L, 2L), maxBytes = 6) { it })
    }

    @Test
    fun aRecordAboveTheByteBoundGetsACallOfItsOwn() {
        assertEquals(listOf(listOf(1L), listOf(9L), listOf(1L)), Chunker.chunks(listOf(1L, 9L, 1L), maxBytes = 6) { it })
    }

    @Test
    fun theDefaultsAreTheDocumentedCallLimits() {
        assertEquals(1000, Chunker.MAX_RECORDS)
        assertEquals(4L * 1024 * 1024, Chunker.MAX_BYTES)
        // The writer's real estimate: ten hours of 3600 one-second readings stay under the byte bound.
        val hour = TestRecords.heartRate(samples = (0 until 3600).map { HeartRateSample(TestRecords.NOW.minusSeconds(3600L - it), 60) })
        assertEquals(1, Chunker.chunks(List(10) { hour }) { RecordSize.estimateBytes(it) }.size)
    }

    @Test
    fun aBoundBelowOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { Chunker.chunks(listOf(1), maxRecords = 0) { 1L } }
        assertFailsWith<IllegalArgumentException> { Chunker.chunks(listOf(1), maxBytes = 0) { 1L } }
    }
}
