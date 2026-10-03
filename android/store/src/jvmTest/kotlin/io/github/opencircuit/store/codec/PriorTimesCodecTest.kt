package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.SleepEdit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stored form of a night's edit undo stack: the times each edit replaced.
 *
 * Upstream stores `[Snapshot]` (`inBedStart`, `sleepOnset`, `sleepWake`) with `JSONEncoder` and
 * reads it with `try? JSONDecoder().decode` (ios/OpenCircuit/Store/LocalStore.swift:401-418 @
 * b1c2fdd), so one bad entry makes the whole stack unreadable. Here the dates are whole epoch
 * milliseconds, as every stored form of this store.
 */
class PriorTimesCodecTest {

    private fun t(ms: Long) = Instant.ofEpochMilli(ms)

    @Test
    fun theStoredFormIsEachEntrysThreeTimesInEpochMilliseconds() {
        assertEquals(
            """[{"inBedStart":1750000000000,"sleepOnset":1750001800000,"sleepWake":1750028800000}]""",
            PriorTimesCodec.encode(listOf(SleepEdit.Times(t(1_750_000_000_000), t(1_750_001_800_000), t(1_750_028_800_000)))),
        )
    }

    @Test
    fun aStackRoundTripsInItsOrderIncludingAnUnknownOnset() {
        val stack = listOf(
            SleepEdit.Times(t(1_750_000_000_000), SleepEdit.DISTANT_PAST, t(1_750_028_800_000)),
            SleepEdit.Times(t(1_749_996_400_123), t(1_750_001_800_000), t(1_750_032_400_000)),
        )
        assertEquals(stack, readable(PriorTimesCodec.decode(PriorTimesCodec.encode(stack))))
        assertEquals(emptyList(), readable(PriorTimesCodec.decode(PriorTimesCodec.encode(emptyList()))))
    }

    @Test
    fun oneBadEntryMakesTheWholeStackUnreadable() {
        for (raw in listOf(
            """[{"inBedStart":1,"sleepOnset":2,"sleepWake":3},{"inBedStart":1,"sleepOnset":2}]""",
            """[{"inBedStart":1,"sleepOnset":2,"sleepWake":null}]""",
            """[{"inBedStart":"1","sleepOnset":2,"sleepWake":3}]""",
            """[{"inBedStart":1.5,"sleepOnset":2,"sleepWake":3}]""",
            """{"inBedStart":1,"sleepOnset":2,"sleepWake":3}""",
            """[1]""",
            "not json",
            "",
        )) {
            assertUnreadable(PriorTimesCodec.decode(raw), raw)
        }
    }
}
