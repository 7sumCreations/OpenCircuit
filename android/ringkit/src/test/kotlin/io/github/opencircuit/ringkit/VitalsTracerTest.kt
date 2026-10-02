package io.github.opencircuit.ringkit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice-end tracer for the vitals basics: decoded history records cross every seam into strain and
 * active energy in one run — `0x4c` records on the raw byte path → the archive merge → the decoder's
 * heart-rate samples → `HRSample`s → TRIMP, strain and TRIMP energy. Each seam passes on its own in
 * its own suite; this proves they line up.
 *
 * The records are four recorded synthetic nights from the sleep differential set (rebuilt from
 * their hex, never through a production builder), pooled so the day clears the 600-reading
 * minimum. The expected energy is counted here from the decoded heart rates with the zone bands
 * written out (50 / 60 / 70 / 80 / 90 % of heart-rate reserve, Edwards' rule), not taken from the
 * code under test.
 */
class VitalsTracerTest {

    private val nights = SleepDifferentialFixtures.inputs().filter { it.id in setOf("synthetic-000", "synthetic-001", "synthetic-002", "synthetic-003") }

    /** One night's records as an archive holds them: deduplicated by counter, sorted. */
    private fun archived(records: List<BulkRecord>): List<BulkRecord> =
        EpochArchive.merge(existing = emptyList(), incoming = records, retention = java.time.Duration.ofSeconds(0xFFFF_FFFFL))

    /** The decoder's heart-rate samples as `HRSample`s (the decoder emits point readings). */
    private fun hrSamples(records: List<BulkRecord>): List<HRSample> =
        BulkSleep.samples(records).filter { it.kind == MetricKind.HEART_RATE }.map { q ->
            check(q.value == q.value.toInt().toDouble()) { "heart rate ${q.value} is not a whole bpm" }
            HRSample(bpm = q.value.toInt(), start = q.start, end = q.end)
        }

    /** Edwards' zone weight, written out from the rule (not the port's function). */
    private fun zone(bpm: Int, maxHR: Int, restingHR: Int): Int {
        val pct = (bpm.toDouble() - restingHR.toDouble()) / (maxHR.toDouble() - restingHR.toDouble()) * 100.0
        return listOf(90.0, 80.0, 70.0, 60.0, 50.0).indexOfFirst { pct >= it }.let { if (it < 0) 0 else 5 - it }
    }

    private val pooled: List<HRSample> = nights.flatMap { hrSamples(archived(it.records)) }

    @Test
    fun decodedRecordsBecomeStrainAndActiveEnergyInOneRun() {
        assertEquals(4, nights.size, "the four recorded nights are present")
        // Decode → HRSample: one sample per decoded heart rate, same time, a point reading, a plausible bpm.
        for (n in nights) {
            val records = archived(n.records)
            val decoded = BulkSleep.samples(records).filter { it.kind == MetricKind.HEART_RATE }
            val samples = hrSamples(records)
            assertEquals(decoded.size, samples.size, "${n.id}: every decoded heart rate becomes one sample")
            assertEquals(decoded.map { it.start }, samples.map { it.start }, "${n.id}: times carried across")
            assertTrue(samples.all { it.end == it.start && it.bpm in LiveHR.VALID_BPM }, "${n.id}: point readings in the valid band")
        }
        assertTrue(pooled.size >= Strain.MIN_READINGS, "the pooled day clears the minimum (${pooled.size})")

        // HRSample → TRIMP energy: a point reading counts as one second (upstream's fallback), at the
        // fixed 60 bpm resting HR, here with a 120 bpm maximum so the stirs and active epochs score.
        var trimp = 0.0
        for (s in pooled) trimp += (1.0 / 60.0) * zone(s.bpm, maxHR = 120, restingHR = Calories.DEFAULT_RESTING_HR).toDouble()
        val kcal = Calories.activeKcal(pooled, maxHR = 120)
        assertEquals(trimp * Calories.TRIMP_KCAL_FACTOR, kcal, "TRIMP energy from the decoded heart rates")
        assertTrue(kcal > 0, "some epochs score above 50 % of reserve")

        // The same heart rates as a series, one reading per 150 s epoch → TRIMP and strain.
        val bpms = pooled.map { it.bpm }
        var seriesTrimp = 0.0
        for (b in bpms) seriesTrimp += (BulkRecord.EPOCH_SECONDS / 60.0) * zone(b, maxHR = 120, restingHR = 50).toDouble()
        assertEquals(seriesTrimp, Strain.edwardsTRIMP(bpms, maxHR = 120, restingHR = 50, sampleSeconds = BulkRecord.EPOCH_SECONDS.toDouble()))
        val strain = Strain(maxHR = 120, restingHR = 50).calculate(bpms, sampleSeconds = BulkRecord.EPOCH_SECONDS.toDouble())
        assertEquals(Strain.trimpToStrain(seriesTrimp), strain)
        assertTrue(strain != null && strain > 0.0, "the pooled day has a positive strain ($strain)")
    }

    @Test
    fun aRedeliveredNightCountsItsEnergyOnceThroughTheArchiveMerge() {
        // Every night delivered twice (a re-drained page): the archive merge keeps one copy per
        // counter, so the samples and the energy are exactly those of a single delivery. The energy
        // functions count every sample they are given, as upstream — the merge is what keeps a
        // repeated delivery from counting twice.
        val twice = nights.flatMap { hrSamples(archived(it.records + it.records)) }
        assertEquals(pooled, twice)
        assertEquals(Calories.activeKcal(pooled, maxHR = 120), Calories.activeKcal(twice, maxHR = 120))

        val unmerged = nights.flatMap { hrSamples(it.records + it.records) }
        assertEquals(2 * pooled.size, unmerged.size)
        val single = Calories.activeKcal(pooled, maxHR = 120)
        assertTrue(single > 0, "the pooled day has TRIMP energy to double")
        assertTrue(abs(Calories.activeKcal(unmerged, maxHR = 120) - 2 * single) <= 1e-9 * single, "without the merge the energy doubles")
    }
}
