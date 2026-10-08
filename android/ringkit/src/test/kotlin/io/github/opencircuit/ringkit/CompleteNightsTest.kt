package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `BulkSleep.completeNights` on the kept seven-night backlog: every night of a multi-day drain,
 * oldest first, each exactly the slice `latestNightRecords` gives for that night alone, no record in
 * two nights — and only nights the ring has finished with (a later record, or the sync drained
 * through 30 min past the slice's last record).
 */
class CompleteNightsTest {

    private val backlog = BacklogSevenNights.load()
    private val lastRecord: Instant = backlog.records.last().date()

    private fun nights(records: List<BulkRecord>, drainedThrough: Instant?) =
        BulkSleep.completeNights(records, backlog.zone, drainedThrough, temperatures = backlog.temps)

    private fun alone(index: Int) = BulkSleep.latestNightRecords(backlog.nightRecords(index), backlog.zone, temperatures = backlog.temps)

    @Test
    fun aDrainedWeekIsSevenNightsOldestFirstEachTheSliceOfThatNightAlone() {
        val found = nights(backlog.records, drainedThrough = lastRecord.plus(Duration.ofHours(2)))

        assertEquals(listOf(222, 241, 194, 180, 201, 209, 172), found.map { it.size })
        found.forEachIndexed { k, slice -> assertEquals(alone(k).map { it.counter }, slice.map { it.counter }, "night ${k + 1}") }
        found.zipWithNext().forEach { (a, b) -> assertTrue(a.last().counter < b.first().counter, "oldest first, disjoint") }
        val all = found.flatten().map { it.counter }
        assertEquals(all.size, all.toSet().size, "no record in two nights")
    }

    @Test
    fun theLastNightIsWithheldUntilALaterRecordOrTheDrainReachesThirtyMinutesPastIt() {
        val last = nights(backlog.records, drainedThrough = lastRecord.plus(Duration.ofHours(2))).last()
        val end = last.last().date()
        // A sync made at the wake: the drain stops at the last night's last record.
        val atWake = backlog.records.filter { !it.date().isAfter(end) }

        assertEquals(7, nights(backlog.records, drainedThrough = null).size, "the day after the last night completes it")
        assertEquals(6, nights(atWake, drainedThrough = null).size, "nothing says the ring is done with the last night")
        assertEquals(6, nights(atWake, drainedThrough = end.plus(Duration.ofMinutes(30)).minusSeconds(1)).size)
        assertEquals(7, nights(atWake, drainedThrough = end.plus(Duration.ofMinutes(30))).size)
        // One later record — the ring wrote past the night — completes it with no drained-through time.
        val later = recordAt(end.plusSeconds(150), template = backlog.records.last())
        assertEquals(7, nights(atWake + later, drainedThrough = null).size)
        assertEquals(last.map { it.counter }, nights(atWake + later, drainedThrough = null).last().map { it.counter })
    }

    @Test
    fun aRemainderThatIsExactlyOneNightIsStillANight() {
        // The size-equality sentinel (whole input back = no night) would read this as no night.
        val only = alone(0)
        val found = nights(only, drainedThrough = lastRecord.plus(Duration.ofDays(1)))

        assertEquals(listOf(only.map { it.counter }), found.map { s -> s.map { it.counter } })
    }

    @Test
    fun noNightAndNoRecordsGiveNoNights() {
        val idle = backlog.records.filter { it.counter > backlog.nights[2].last && it.counter < backlog.nights[3].first }

        assertEquals(emptyList(), nights(idle, drainedThrough = lastRecord))
        assertEquals(emptyList(), nights(emptyList(), drainedThrough = lastRecord))
    }

    @Test
    fun orderAndDuplicatesInTheInputChangeNothing() {
        val drained = lastRecord.plus(Duration.ofHours(2))
        val plain = nights(backlog.records, drained).map { s -> s.map { it.counter } }
        val messy = nights(backlog.records.shuffled(java.util.Random(3)) + backlog.records.take(100), drained).map { s -> s.map { it.counter } }

        assertEquals(plain, messy)
    }

    @Test
    fun twoNightsWhoseMarginsMeetShareNoRecord() {
        // The measured evening shape (ObservedGapAbsorbTest): a still 20:30→21:40 block, 35 min of
        // recorded movement, the night 22:15→06:00. The guard keeps the block out of the night, and
        // the block is overnight by its midpoint, so the peel finds it as a night of its own — whose
        // 30 min margin reaches past the night's first record (21:45).
        val zone = java.time.ZoneId.of("America/New_York")
        fun at(hour: Int, min: Int = 0, day: Int = 0): Instant =
            Instant.ofEpochSecond(1_780_000_000).atZone(zone).toLocalDate().atStartOfDay(zone)
                .plusDays(day.toLong()).toInstant().plusSeconds((hour * 60L + min) * 60)
        val union = span(at(20, 30), at(21, 40), still = true) + span(at(21, 40), at(22, 15), still = false) +
            span(at(22, 15), at(6, 0, day = 1), still = true) + span(at(6, 0, day = 1), at(9, 0, day = 1), still = false)

        val found = BulkSleep.completeNights(union, zone, drainedThrough = null)

        assertEquals(2, found.size)
        assertEquals(BulkSleep.latestNightRecords(union, zone).map { it.counter }, found.last().map { it.counter })
        val all = found.flatten().map { it.counter }
        assertEquals(all.size, all.toSet().size, "no record in two nights")
    }

    /** Still (`[10:15]` = 1) or moving (elevated and varied) records every 150 s over `[from, to)`, raw path. */
    private fun span(from: Instant, to: Instant, still: Boolean): List<BulkRecord> =
        (0 until Duration.between(from, to).seconds step 150).mapIndexed { i, s ->
            val counter = from.plusSeconds(s).epochSecond - Command.SYNC_EPOCH
            val b = ByteArray(BulkRecord.LENGTH)
            b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
            b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
            if (still) {
                for (k in 10 until 15) b[k] = 1
            } else {
                val jitter = i % 5
                b[10] = (8 + jitter).toByte(); b[11] = 12; b[12] = (20 + jitter).toByte(); b[13] = 6; b[14] = 10
            }
            BulkRecord.of(b)!!
        }

    /** [template]'s bytes with its counter moved to [at] (raw path). */
    private fun recordAt(at: Instant, template: BulkRecord): BulkRecord {
        val counter = at.epochSecond - Command.SYNC_EPOCH
        val b = template.raw.copyOf()
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        return BulkRecord.of(b)!!
    }
}
