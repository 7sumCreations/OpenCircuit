package io.github.opencircuit.ringkit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin-only checks for the summary merge on hostile spans and across repeated syncs: negative and
 * zero spans and asleep values, the same sync re-delivered, and the same slices arriving duplicated
 * and in any order. Kept out of the upstream-port class so its count stays exact.
 *
 * Every expected value was measured on upstream's pinned Swift build. Upstream also accepts NaN and
 * infinite seconds; a `Duration` cannot hold either, so those inputs cannot reach the port (measured
 * upstream for the record: a NaN stored span with NaN asleep is replaced; a NaN new asleep keeps the
 * stored night).
 */
class SleepSummaryMergeHazardTest {

    private fun s(x: Long): Duration = Duration.ofSeconds(x)
    private fun replace(si: Long, ni: Long, sa: Long, na: Long, same: Boolean = false): Boolean =
        SleepSummaryMerge.shouldReplace(storedInBed = s(si), newInBed = s(ni), storedAsleep = s(sa), newAsleep = s(na), sameCoverage = same)

    @Test
    fun negativeAndZeroSpansAsUpstream() {
        assertEquals(true, replace(-5, 10, -5, 0), "a stored row with nothing positive is always replaced")
        assertEquals(false, replace(18_000, 3_600, 0, -7_200), "a negative new asleep falls back to the span, which is shorter")
        assertEquals(false, replace(18_000, 3_600, -1, -7_200))
        assertEquals(true, replace(0, -5, 0, 0), "nothing stored: even a negative new span lands")
        assertEquals(false, replace(0, 0, 3_600, 0), "stored asleep alone makes the row usable, and the new night has less")
        assertEquals(true, replace(-1, -1, -1, -1, same = true))
        assertEquals(true, replace(3_600, -3_600, 0, 7_200), "more asleep wins even with a negative span")
        assertEquals(true, replace(3_600, 3_600, 0, -1), "no positive asleep on either side: equal spans replace")
    }

    /** A slice is (in-bed seconds, asleep seconds); a sync replaces the stored slice iff the merge says so. */
    private fun syncAll(vararg slices: Pair<Long, Long>): Pair<Long, Long>? {
        var stored: Pair<Long, Long>? = null
        for (n in slices) {
            val st = stored
            if (st == null || replace(st.first, n.first, st.second, n.second)) stored = n
        }
        return stored
    }

    private val buffer = 17_100L to 15_000L // a ring-buffer-sized morning slice
    private val fuller = 30_600L to 27_000L // the whole night drained
    private val widened = 34_200L to 27_000L // the same sleep with the awake lead-in
    private val legacy = 14_400L to 0L // a row with no asleep figure

    /** Measured upstream over the same sequences: the fullest night wins whatever the arrival order. */
    @Test
    fun repeatedDuplicatedAndReorderedSyncsConvergeOnTheFullestNight() {
        assertEquals(widened, syncAll(buffer, fuller, widened))
        assertEquals(widened, syncAll(widened, fuller, buffer))
        assertEquals(widened, syncAll(fuller, buffer, widened, buffer, fuller))
        assertEquals(buffer, syncAll(buffer, buffer, buffer))
        assertEquals(buffer, syncAll(legacy, buffer))
        assertEquals(buffer, syncAll(buffer, legacy))
        assertEquals(widened, syncAll(widened, widened, fuller, fuller))
    }

    /** Re-delivering the slice that is already stored replaces it with itself: the stored values never change. */
    @Test
    fun reDeliveringTheStoredSliceIsANoOp() {
        for (slice in listOf(buffer, fuller, widened, legacy, 0L to 0L, -5L to -5L)) {
            assertEquals(true, replace(slice.first, slice.first, slice.second, slice.second), "an identical re-stage applies (to the same values) for $slice")
            assertEquals(slice, syncAll(slice, slice, slice))
        }
    }
}
