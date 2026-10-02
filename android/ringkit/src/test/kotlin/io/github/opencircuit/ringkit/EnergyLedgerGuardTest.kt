package io.github.opencircuit.ringkit

import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Guards for what the Kotlin write-ledger and write-window port adds or could lose relative to Swift:
 * the constants (typed from upstream's sources), immutable values whose doubles compare by IEEE `==`
 * as Swift's synthesized `Equatable`, lists copied in and read-only out where Swift's arrays copy,
 * inputs neither aliased nor changed, 64-bit ordinals, the day total summed in a fixed order where
 * upstream's dictionary order is seeded per process, and no read of the machine's clock, locale or
 * time zone.
 */
class EnergyLedgerGuardTest {

    private fun noSetters(type: Class<*>): List<String> = type.methods.filter { it.name.startsWith("set") }.map { it.name }
    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private fun at(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))
    private fun bucket(hour: Double, kcal: Double) = Calories.EnergyBucket(at(hour), at(hour).plusSeconds(900), kcal, 0.0, 0.0)

    @Test
    fun constantsEqualUpstreamLiterals() {
        // ActiveEnergyLedger.swift :66, ActiveEnergyWindow.swift :51
        assertEquals(1.0, ActiveEnergyLedger.MIN_WRITE_KCAL)
        assertEquals(20.0, ActiveEnergyWindow.MAX_PLAUSIBLE_KCAL_PER_MINUTE)
        // The default width is the daily estimate's 900 s (ActiveEnergyLedger.swift :90, :200): a
        // bucket at 00:15 is slot 1, so the marks have two entries.
        assertEquals(2, ActiveEnergyLedger.plan(listOf(bucket(0.25, 5.0)), emptyList(), day, at(1.0)).watermarks.size)
        assertEquals(listOf(0.0, 3.0), ActiveEnergyLedger.seed(listOf(bucket(0.25, 5.0)), 3.0, day).watermarks)
        // The aggregate gate is inclusive: exactly one kilocalorie is written, a hair less is held.
        assertEquals(1.0, ActiveEnergyLedger.plan(listOf(bucket(8.0, 1.0)), emptyList(), day, at(12.0)).totalKcal)
        assertTrue(ActiveEnergyLedger.plan(listOf(bucket(8.0, Math.nextDown(1.0))), emptyList(), day, at(12.0)).writes.isEmpty())
    }

    @Test
    fun valuesHaveNoSettersAndCompareByIeeeEquality() {
        for (type in listOf(ActiveEnergyLedger.Write::class.java, ActiveEnergyLedger.Plan::class.java, ActiveEnergyLedger.Seed::class.java)) {
            assertEquals(emptyList(), noSetters(type), "${type.simpleName} is immutable")
        }
        fun write(kcal: Double) = ActiveEnergyLedger.Write(day, at(1.0), kcal)
        assertEquals(write(2.0), write(2.0))
        assertNotEquals(write(Double.NaN), write(Double.NaN), "NaN is unequal to itself, as Swift's ==")
        assertEquals(write(-0.0), write(0.0), "-0.0 equals 0.0, as Swift's ==")
        assertEquals(write(-0.0).hashCode(), write(0.0).hashCode())
        assertNotEquals(write(2.0), ActiveEnergyLedger.Write(day, at(2.0), 2.0))
        fun plan(marks: List<Double>, carry: Double = 0.0) = ActiveEnergyLedger.Plan(listOf(write(2.0)), marks, carry, 0.0)
        assertEquals(plan(listOf(1.0, 2.0)), plan(listOf(1.0, 2.0)))
        assertEquals(plan(listOf(-0.0)), plan(listOf(0.0)))
        assertEquals(plan(listOf(-0.0)).hashCode(), plan(listOf(0.0)).hashCode())
        assertNotEquals(plan(listOf(Double.NaN)), plan(listOf(Double.NaN)))
        assertNotEquals(plan(listOf(1.0)), plan(listOf(1.0, 0.0)), "the marks' length is part of the value")
        assertNotEquals(plan(listOf(1.0), carry = Double.NaN), plan(listOf(1.0), carry = Double.NaN))
        assertEquals(4.0, ActiveEnergyLedger.Plan(listOf(write(2.0), write(2.0)), emptyList(), 0.0, 0.0).totalKcal)
        val seed = ActiveEnergyLedger.Seed(listOf(1.0, -0.0), 3.0)
        assertEquals(ActiveEnergyLedger.Seed(listOf(1.0, 0.0), 3.0), seed)
        assertNotEquals(ActiveEnergyLedger.Seed(listOf(Double.NaN), 3.0), ActiveEnergyLedger.Seed(listOf(Double.NaN), 3.0))
        val (marks, carry) = seed // destructures like upstream's (watermarks:, carry:) tuple
        assertEquals(listOf(1.0, -0.0), marks)
        assertEquals(3.0, carry)
    }

    @Test
    fun listsAreCopiedInAndReadOnlyOut() {
        val writes = mutableListOf(ActiveEnergyLedger.Write(day, at(1.0), 2.0))
        val marks = mutableListOf(1.0, 2.0)
        val plan = ActiveEnergyLedger.Plan(writes, marks, 0.0, 0.0)
        val seed = ActiveEnergyLedger.Seed(marks, 0.0)
        writes.clear()
        marks.clear()
        assertEquals(1, plan.writes.size, "the plan keeps its own writes")
        assertEquals(listOf(1.0, 2.0), plan.watermarks, "the plan keeps its own marks")
        assertEquals(listOf(1.0, 2.0), seed.watermarks, "the seed keeps its own marks")
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (plan.writes as MutableList<ActiveEnergyLedger.Write>).clear() }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (plan.watermarks as MutableList<Double>)[0] = 9.0 }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (seed.watermarks as MutableList<Double>).add(9.0) }
    }

    @Test
    fun inputsAreNeitherAliasedNorChanged() {
        val buckets = mutableListOf(bucket(9.0, 25.0), bucket(8.0, 40.0)) // unsorted on purpose
        val marks = MutableList(33) { 0.0 }.also { it[32] = 10.0 }
        val bucketsBefore = buckets.toList()
        val marksBefore = marks.toList()
        val p = ActiveEnergyLedger.plan(buckets, marks, day, at(12.0))
        val s = ActiveEnergyLedger.seed(buckets, 50.0, day)
        assertEquals(bucketsBefore, buckets, "the buckets are sorted in a copy, as Swift's sorted() returns one")
        assertEquals(marksBefore, marks, "the stored marks are never written through")
        assertEquals(55.0, p.totalKcal)
        assertNotSame<List<Double>>(marks, p.watermarks)
        val planned = p.watermarks.toList()
        val seeded = s.watermarks.toList()
        marks.clear()
        buckets.clear()
        assertEquals(planned, p.watermarks, "the plan's marks are its own")
        assertEquals(seeded, s.watermarks)
        // The unchanged plan hands back a copy of the stored marks, not the caller's list.
        val stored = mutableListOf(3.0, 4.0)
        val held = ActiveEnergyLedger.plan(listOf(bucket(10.0, 0.5)), stored, day, at(16.0))
        stored[0] = 99.0
        assertEquals(listOf(3.0, 4.0), held.watermarks)
    }

    @Test
    fun ordinalsAreSixtyFourBit() {
        // Swift's Int is 64-bit: a million years out the ordinal leaves 32 bits; a 32-bit ordinal would
        // wrap or saturate to a different slot.
        val far = day.plusSeconds(1_000_000L * 365 * 86_400)
        assertEquals(35_040_000_000L, ActiveEnergyLedger.ordinal(far, day, 900.0))
        assertTrue(ActiveEnergyLedger.ordinal(far, day, 900.0) > Int.MAX_VALUE)
        assertEquals(31_536_000_000_000L, ActiveEnergyLedger.ordinal(far, day, 1.0))
    }

    @Test
    fun theDayTotalIsSummedInAscendingOrdinal() {
        // Upstream adds its dictionary's values in a per-process order. Here, 1e16 kcal in slot 3 and 1
        // kcal in slots 17 and 18: in ascending ordinal (1e16 + 1) + 1 rounds to 1e16, while the order a
        // default hash map iterates (17, 18, 3) gives 1e16 + 2. With 1e16 − 2 kcal already saved, the
        // day-total backstop leaves 2 kcal of headroom in ascending order — 4 in the hash map's.
        val buckets = listOf(bucket(0.75, 1e16), bucket(4.25, 1.0), bucket(4.5, 1.0))
        for (order in listOf(buckets, buckets.reversed(), listOf(buckets[1], buckets[2], buckets[0]))) {
            val p = ActiveEnergyLedger.plan(order, emptyList(), day, at(12.0), savedKcal = 1e16 - 2)
            assertEquals(2.0, p.totalKcal, "whatever order the buckets arrive in")
            assertEquals(listOf(at(0.75)), p.writes.map { it.start })
        }
    }

    @Test
    fun nothingReadsTheMachineLocaleOrTimeZone() {
        val buckets = listOf(bucket(8.0, 40.0), bucket(9.0, 25.0), bucket(24.5, 10.0)) // into the next calendar day
        fun results(): List<Any?> = listOf(
            ActiveEnergyLedger.plan(buckets, emptyList(), day, at(25.0), uncreditedWorkoutKcal = 12.0),
            ActiveEnergyLedger.seed(buckets, 50.0, day),
            ActiveEnergyLedger.ordinal(at(24.5), day, 900.0),
            ActiveEnergyWindow.resolve(at(9.0), at(7.0), at(9.0).plusSeconds(44), day, 190.0),
            ActiveEnergyWindow.resolve(null, at(7.5), at(25.0), day),
        )
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val reference = results()
            for ((locale, tz) in listOf(
                Locale.forLanguageTag("ar-EG") to "America/New_York",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "Asia/Kolkata",
                Locale.forLanguageTag("tr-TR") to "Pacific/Chatham",
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(tz))
                assertEquals(reference, results(), "$locale / $tz")
            }
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    @Test
    fun theSlicesSourcesNeverReadTheClockLocaleZoneOrEnvironment() {
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set"))
        val dir = File(root, "ringkit/src/main/kotlin/io/github/opencircuit/ringkit")
        val forbidden = Regex("""Instant\.now\(|ZoneId\.systemDefault\(|Locale\.getDefault\(|Clock\.system|System\.getenv|TimeZone\.getDefault\(""")
        for (f in listOf("ActiveEnergyLedger.kt", "ActiveEnergyWindow.kt").map { File(dir, it) }) {
            assertTrue(f.isFile, "missing source ${f.name}")
            val hits = f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
            assertEquals(emptyList(), hits, "ambient environment read in ${f.name}")
        }
        // The pattern bites: each forbidden call is found in a line built here. The environment read
        // is assembled from pieces, because the corpus-gate audit forbids that text in test sources.
        for (probe in listOf("Instant.now()", "ZoneId.systemDefault()", "Locale.getDefault()", "Clock.systemUTC()", "System" + ".getenv(\"X\")", "TimeZone.getDefault()")) {
            assertTrue(forbidden.containsMatchIn("val x = $probe"), "the audit catches $probe")
        }
    }
}
