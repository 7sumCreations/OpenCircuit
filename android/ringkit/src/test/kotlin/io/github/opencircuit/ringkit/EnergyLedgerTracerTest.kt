package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice-end tracer for active-energy write planning: one day's heart rate and steps go through the
 * daily estimate and the write ledger the way a sync would, with the ledger's state committed after
 * each confirmed save — and the energy is written once.
 *
 *  1. Noon sync: the morning's readings → `Calories.dailyEstimate` → `ActiveEnergyLedger.plan` writes
 *     every bucket the estimate attributed, and nothing else.
 *  2. Evening re-sync that ADDS data — an afternoon of readings plus a late-arriving morning step
 *     window: the second plan writes exactly the new energy (by value), in the new buckets only,
 *     including the late EARLIER bucket; nothing the first plan wrote is written again.
 *  3. A third flush with nothing new writes nothing and leaves the state as it was.
 *
 * The store is a stand-in: the plan's watermarks, carry and saved total, committed only when the
 * "save" succeeds. Each write gets its window from `ActiveEnergyWindow.resolve` as the writer would.
 */
class EnergyLedgerTracerTest {

    private val day: Instant = Instant.ofEpochSecond(1_780_272_000) // 2026-06-01 00:00 UTC, the day start
    private fun at(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))
    private val profile = UserProfile(age = 35, weightKg = 72.0, heightCm = 178.0, sex = BiologicalSex.MALE)

    /** Point readings every 150 s (the ring's history epoch) from [from] to [to], at [bpm]. */
    private fun readings(from: Double, to: Double, bpm: (Double) -> Int): List<HRSample> {
        val out = mutableListOf<HRSample>()
        var t = from
        while (t < to) {
            out += HRSample(bpm(t), at(t))
            t += 150.0 / 3600
        }
        return out
    }

    private val morningHR = readings(6.0, 12.0) { h -> if (h >= 8.0 && h < 8.0 + 40.0 / 60) 125 else 66 }
    private val morningSteps = listOf(StepWindow(at(8.0), at(8.5), 2000), StepWindow(at(10.0), at(10.25), 800))
    private val afternoonHR = readings(12.0, 18.0) { h -> if (h >= 15.0 && h < 15.5) 130 else 70 }
    private val afternoonSteps = listOf(StepWindow(at(16.0), at(16.5), 1500))
    private val lateMorningSteps = listOf(StepWindow(at(11.0), at(11.25), 600)) // recorded at 11:00, delivered in the evening

    private fun estimate(hr: List<HRSample>, windows: List<StepWindow>) =
        Calories.dailyEstimate(hr, steps = windows.sumOf { it.delta }, profile = profile, stepWindows = windows, dayStart = day)

    /** The stand-in store: what a writer persists only after a confirmed save. */
    private class Store(var marks: List<Double> = emptyList(), var carry: Double = 0.0, var saved: Double = 0.0)

    private fun flush(e: Calories.DailyEstimate, now: Instant, store: Store, commit: Boolean = true): ActiveEnergyLedger.Plan {
        val p = ActiveEnergyLedger.plan(e.buckets, store.marks, day, now, carry = store.carry, savedKcal = store.saved)
        for (w in p.writes) {
            assertTrue(w.kcal > 0 && w.kcal.isFinite() && w.start < w.end && w.end <= now, "write $w at $now")
        }
        if (commit) {
            store.marks = p.watermarks
            store.carry = p.carryRemaining
            store.saved += p.totalKcal
        }
        return p
    }

    @Test
    fun aReSyncThatAddsDataWritesOnlyTheNewEnergyAndNothingNewWritesNothing() {
        val store = Store()

        // 1. Noon: the morning, as synced.
        val first = estimate(morningHR, morningSteps)
        assertTrue(first.buckets.size >= 3, "the morning attributes into several buckets: ${first.buckets.size}")
        val noon = flush(first, at(12.0), store)
        assertEquals(first.activeKcal, noon.totalKcal, 1e-9, "the first plan writes the whole morning")
        assertEquals(first.buckets.filter { it.activeKcal > 0 }.map { it.start }, noon.writes.map { it.start }, "one write per bucket, in its own window")
        val noonWindow = ActiveEnergyWindow.resolve(anchor = null, notBefore = null, now = at(12.0), dayStart = day, kcal = noon.totalKcal)
        assertEquals(day to at(12.0), noonWindow?.let { it.start to it.end })

        // 2. Evening: an afternoon of readings and steps, plus a morning window delivered late.
        val second = estimate(morningHR + afternoonHR, morningSteps + afternoonSteps + lateMorningSteps)
        val oldStarts = first.buckets.map { it.start }.toSet()
        // What the noon sync priced is priced the same now (nothing old moves), so the new energy is
        // exactly the energy of the buckets that were not there before.
        assertEquals(first.buckets, second.buckets.filter { it.start in oldStarts }, "the morning's buckets are unchanged")
        val newBuckets = second.buckets.filter { it.start !in oldStarts && it.activeKcal > 0 }
        assertTrue(newBuckets.any { it.start == at(11.0) }, "the late morning window made an earlier bucket")
        assertTrue(newBuckets.any { it.start >= at(15.0) }, "the afternoon made later buckets")
        val newEnergy = newBuckets.fold(0.0) { a, b -> a + b.activeKcal }

        val failed = flush(second, at(18.0), store, commit = false) // a save that fails commits nothing…
        val evening = flush(second, at(18.0), store) // …so the retry plans the same energy, once
        assertEquals(failed, evening, "an uncommitted plan leaves the energy owed, unchanged")
        assertEquals(newEnergy, evening.totalKcal, 1e-9, "the second plan writes exactly the new energy")
        assertEquals(second.activeKcal - first.activeKcal, evening.totalKcal, 1e-9)
        assertEquals(newBuckets.map { it.start }, evening.writes.map { it.start }, "only the new buckets are written, oldest first")
        assertTrue(evening.writes.none { it.start in oldStarts }, "nothing the first plan wrote is written again")
        assertEquals(second.activeKcal, store.saved, 1e-9, "the store holds what the day is worth")
        val eveningWindow = ActiveEnergyWindow.resolve(anchor = at(12.0), notBefore = null, now = at(18.0), dayStart = day, kcal = evening.totalKcal)
        assertEquals(at(12.0) to at(18.0), eveningWindow?.let { it.start to it.end }, "the evening delta tiles from the noon window")

        // 3. Later: the same day, nothing new.
        val marksBefore = store.marks
        val carryBefore = store.carry
        val later = flush(second, at(19.0), store)
        assertEquals(emptyList(), later.writes, "a flush with nothing new writes nothing")
        assertEquals(marksBefore, later.watermarks)
        assertEquals(carryBefore, later.carryRemaining)
        assertEquals(second.activeKcal, store.saved, 1e-9)
    }
}
