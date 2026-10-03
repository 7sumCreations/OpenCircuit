package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.store.PendingSleepReconcile
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stored form of the queue of sleep edits waiting to reach Health: a list of
 * `{night, inBedStart, sleepOnset, sleepWake, segments}`, the segments in the segment codec's form.
 *
 * Upstream `PendingSleepReconcile` is synthesized Codable read with `try? JSONDecoder().decode`
 * (ios/OpenCircuit/Store/LocalStore.swift:566-572, :596-601 @ b1c2fdd): one bad item makes the
 * whole queue unreadable. The dates here are whole epoch milliseconds, as every stored form of
 * this store.
 */
class PendingSleepReconcileCodecTest {

    private fun t(ms: Long) = Instant.ofEpochMilli(ms)

    private val item = PendingSleepReconcile(
        night = t(1_749_945_600_000),
        inBedStart = t(1_750_000_000_000),
        sleepOnset = t(1_750_001_800_000),
        sleepWake = t(1_750_028_800_000),
        segments = listOf(
            SleepSegment(t(1_750_000_000_000), t(1_750_003_600_000), SleepStage.ASLEEP_CORE),
            SleepSegment(t(1_750_003_600_000), t(1_750_007_200_000), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        ),
    )

    @Test
    fun theStoredFormIsEachItemsTimesInEpochMillisecondsAndItsSegmentsInTheSegmentForm() {
        assertEquals(
            """[{"night":1749945600000,"inBedStart":1750000000000,"sleepOnset":1750001800000,"sleepWake":1750028800000,""" +
                """"segments":[{"start":1750000000000,"end":1750003600000,"stage":"asleepCore"},""" +
                """{"start":1750003600000,"end":1750007200000,"stage":"asleepCore","provenance":"asserted"}]}]""",
            PendingSleepReconcileCodec.encode(listOf(item)),
        )
    }

    @Test
    fun aQueueRoundTripsInItsOrderAndAnEmptyOneStaysEmpty() {
        val second = item.copy(night = t(1_750_032_000_000), segments = emptyList())
        assertEquals(listOf(item, second), readable(PendingSleepReconcileCodec.decode(PendingSleepReconcileCodec.encode(listOf(item, second)))))
        assertEquals(emptyList(), readable(PendingSleepReconcileCodec.decode(PendingSleepReconcileCodec.encode(emptyList()))))
    }

    @Test
    fun oneBadItemOrSegmentMakesTheWholeQueueUnreadable() {
        val good = """{"night":1,"inBedStart":2,"sleepOnset":3,"sleepWake":4,"segments":[]}"""
        for (raw in listOf(
            "[$good,{\"night\":1,\"inBedStart\":2,\"sleepOnset\":3,\"sleepWake\":4}]",
            """[{"night":null,"inBedStart":2,"sleepOnset":3,"sleepWake":4,"segments":[]}]""",
            """[{"night":"1","inBedStart":2,"sleepOnset":3,"sleepWake":4,"segments":[]}]""",
            """[{"night":1,"inBedStart":2,"sleepOnset":3,"sleepWake":4,"segments":[{"start":1,"end":2,"stage":"nap"}]}]""",
            """[{"night":1,"inBedStart":2,"sleepOnset":3,"sleepWake":4,"segments":[{"start":1,"stage":"awake"}]}]""",
            """[{"night":1,"inBedStart":2,"sleepOnset":3,"sleepWake":4,"segments":{}}]""",
            good,
            "not json",
            "",
        )) {
            assertUnreadable(PendingSleepReconcileCodec.decode(raw), raw)
        }
    }
}
