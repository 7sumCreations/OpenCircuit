package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SyncMeasurement.OverdueLevel
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The overdue-sync warning, by the ring's firmware prefix: Gen 2 / Gen 2 Air (FR02 / FR04) amber at
 * 4 days, red at 5 (they keep about 7); Gen 3 (FR05) amber at 7, red at 9 (it keeps about 10); any
 * other prefix gets the Gen 2 numbers. Measured from the oldest record still on the ring. Thresholds
 * typed from the approved rule.
 */
class OverdueWarningTest {

    private val now: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun gen(version: String) = FirmwareInfo(version = version).generation

    private fun daysAgo(days: Double): Instant = now.minusMillis((days * 86_400_000).toLong())

    @Test
    fun gen2IsQuietBeforeFourDaysAmberAtFourRedAtFive() {
        val g = gen("FR02.018")
        assertNull(SyncMeasurement.overdue(g, daysAgo(3.999), now))
        val amber = assertNotNull(SyncMeasurement.overdue(g, daysAgo(4.0), now))
        assertEquals(OverdueLevel.AMBER, amber.level)
        assertEquals(7, amber.keepsDays)
        assertEquals(OverdueLevel.AMBER, SyncMeasurement.overdue(g, daysAgo(4.999), now)?.level)
        assertEquals(OverdueLevel.RED, SyncMeasurement.overdue(g, daysAgo(5.0), now)?.level)
        assertEquals(OverdueLevel.RED, SyncMeasurement.overdue(g, daysAgo(30.0), now)?.level)
    }

    @Test
    fun gen2AirHasTheGen2Thresholds() {
        val g = gen("FR04.002")
        assertEquals(RingGeneration.GEN2_AIR, g)
        assertNull(SyncMeasurement.overdue(g, daysAgo(3.999), now))
        assertEquals(OverdueLevel.AMBER, SyncMeasurement.overdue(g, daysAgo(4.0), now)?.level)
        assertEquals(OverdueLevel.RED, SyncMeasurement.overdue(g, daysAgo(5.0), now)?.level)
    }

    @Test
    fun gen3IsQuietBeforeSevenDaysAmberAtSevenRedAtNine() {
        val g = gen("FR05.011")
        assertNull(SyncMeasurement.overdue(g, daysAgo(6.999), now))
        val amber = assertNotNull(SyncMeasurement.overdue(g, daysAgo(7.0), now))
        assertEquals(OverdueLevel.AMBER, amber.level)
        assertEquals(10, amber.keepsDays)
        assertEquals(OverdueLevel.AMBER, SyncMeasurement.overdue(g, daysAgo(8.999), now)?.level)
        assertEquals(OverdueLevel.RED, SyncMeasurement.overdue(g, daysAgo(9.0), now)?.level)
    }

    @Test
    fun anUnknownPrefixAndNoFirmwareAtAllGetTheGen2Numbers() {
        for (g in listOf(gen("XY09.001"), gen(""), gen("fr05.011"), gen("FR01.003"))) {
            assertNull(SyncMeasurement.overdue(g, daysAgo(3.999), now), "$g")
            assertEquals(OverdueLevel.AMBER, SyncMeasurement.overdue(g, daysAgo(4.0), now)?.level, "$g")
            assertEquals(OverdueLevel.RED, SyncMeasurement.overdue(g, daysAgo(5.0), now)?.level, "$g")
            assertEquals(7, SyncMeasurement.overdue(g, daysAgo(5.0), now)?.keepsDays, "$g")
        }
    }

    @Test
    fun nothingKnownAboutTheRingOrATimeAheadOfNowWarnsOfNothing() {
        assertNull(SyncMeasurement.overdue(RingGeneration.GEN2, oldestOnRing = null, now = now))
        assertNull(SyncMeasurement.overdue(RingGeneration.GEN2, oldestOnRing = now.plus(Duration.ofDays(9)), now = now))
    }
}
