package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Kotlin-only checks for the missed-night recency math: both 2026 DST transitions in four zones
 * (including Havana's midnight gap and Lord Howe's 30-minute shift), 32-bit schedule extremes, the
 * strict boundaries (now exactly at the wake, a sync exactly at the wake), a sync or a night in the
 * future, and instants beyond the calendar. Kept out of the upstream-port class so its count stays
 * exact.
 *
 * Every expected value was measured on upstream's pinned Swift build. A sweep of 23 104 (instant ×
 * schedule) points every 10 minutes across ±30 h of each 2026 transition in eleven zones agreed with
 * `java.time` on every one; the table keeps every point where an answer changes, and both sides of it.
 */
class MissedNightHazardTest {

    private fun s(epoch: Long): Instant = Instant.ofEpochSecond(epoch)
    private val utc: ZoneId = ZoneOffset.UTC

    /**
     * Measured upstream: zone, bed and wake minutes, now (epoch s), morning wake, status for a night
     * that woke 50 h before now with a sync 1 min before now, and whether a night that woke 3 h before
     * now ended today.
     */
    private val dstRows = """
            America/New_York 1350 390 1772853600 1772796600 missing 1
            America/New_York 1350 390 1772854200 1772883000 ok 1
            America/New_York 1350 390 1772859000 1772883000 ok 1
            America/New_York 1350 390 1772859600 1772883000 ok 0
            America/New_York 1350 390 1772869800 1772883000 ok 0
            America/New_York 1350 390 1772870400 1772883000 ok 1
            America/New_York 1350 390 1772883000 1772883000 ok 1
            America/New_York 1350 390 1772883600 1772883000 missing 1
            America/New_York 1350 390 1772940000 1772883000 missing 1
            America/New_York 1350 390 1772940600 1772969400 ok 1
            America/New_York 1350 390 1772945400 1772969400 ok 1
            America/New_York 1350 390 1772946000 1772969400 ok 0
            America/New_York 1350 390 1772956200 1772969400 ok 0
            America/New_York 1350 390 1772956800 1772969400 ok 1
            America/New_York 1350 390 1772969400 1772969400 ok 1
            America/New_York 1350 390 1772970000 1772969400 missing 1
            America/New_York 1350 390 1773010800 1772969400 missing 1
            America/New_York 1350 390 1773011400 1772883000 missing 1
            America/New_York 1350 390 1773012600 1772883000 missing 1
            America/New_York 1350 390 1773013200 1772969400 missing 1
            America/New_York 1350 390 1773022800 1772969400 missing 1
            America/New_York 1350 390 1773023400 1773052200 ok 1
            America/New_York 1350 390 1773028200 1773052200 ok 1
            America/New_York 1350 390 1773028800 1773052200 ok 0
            America/New_York 1350 390 1773039000 1773052200 ok 0
            America/New_York 1350 390 1773039600 1773052200 ok 1
            America/New_York 1350 390 1773052200 1773052200 ok 1
            America/New_York 1350 390 1773052800 1773052200 missing 1
            America/New_York 1350 390 1773061200 1773052200 missing 1
            America/New_York 1350 390 1793404800 1793356200 missing 1
            America/New_York 1350 390 1793413200 1793356200 missing 1
            America/New_York 1350 390 1793413800 1793442600 ok 1
            America/New_York 1350 390 1793418600 1793442600 ok 1
            America/New_York 1350 390 1793419200 1793442600 ok 0
            America/New_York 1350 390 1793429400 1793442600 ok 0
            America/New_York 1350 390 1793430000 1793442600 ok 1
            America/New_York 1350 390 1793442600 1793442600 ok 1
            America/New_York 1350 390 1793443200 1793442600 missing 1
            America/New_York 1350 390 1793499600 1793442600 missing 1
            America/New_York 1350 390 1793500200 1793529000 ok 1
            America/New_York 1350 390 1793505000 1793529000 ok 1
            America/New_York 1350 390 1793505600 1793529000 ok 0
            America/New_York 1350 390 1793515800 1793529000 ok 0
            America/New_York 1350 390 1793516400 1793529000 ok 1
            America/New_York 1350 390 1793529000 1793529000 ok 1
            America/New_York 1350 390 1793529600 1793529000 missing 1
            America/New_York 1350 390 1793589600 1793529000 missing 1
            America/New_York 1350 390 1793590200 1793619000 ok 1
            America/New_York 1350 390 1793595000 1793619000 ok 1
            America/New_York 1350 390 1793595600 1793619000 ok 0
            America/New_York 1350 390 1793605800 1793619000 ok 0
            America/New_York 1350 390 1793606400 1793619000 ok 1
            America/New_York 1350 390 1793619000 1793619000 ok 1
            America/New_York 1350 390 1793619600 1793619000 missing 1
            America/New_York 60 540 1772859000 1772805600 missing 1
            America/New_York 60 540 1772859600 1772805600 missing 0
            America/New_York 60 540 1772862600 1772805600 missing 0
            America/New_York 60 540 1772863200 1772892000 ok 0
            America/New_York 60 540 1772869800 1772892000 ok 0
            America/New_York 60 540 1772870400 1772892000 ok 1
            America/New_York 60 540 1772892000 1772892000 ok 1
            America/New_York 60 540 1772892600 1772892000 missing 1
            America/New_York 60 540 1772945400 1772892000 missing 1
            America/New_York 60 540 1772946000 1772892000 missing 0
            America/New_York 60 540 1772949000 1772892000 missing 0
            America/New_York 60 540 1772949600 1772978400 ok 0
            America/New_York 60 540 1772956200 1772978400 ok 0
            America/New_York 60 540 1772956800 1772978400 ok 1
            America/New_York 60 540 1772978400 1772978400 ok 1
            America/New_York 60 540 1772979000 1772978400 missing 1
            America/New_York 60 540 1773019800 1772978400 missing 1
            America/New_York 60 540 1773020400 1772892000 missing 1
            America/New_York 60 540 1773021600 1772892000 missing 1
            America/New_York 60 540 1773022200 1772978400 missing 1
            America/New_York 60 540 1773028200 1772978400 missing 1
            America/New_York 60 540 1773028800 1772978400 missing 0
            America/New_York 60 540 1773031800 1772978400 missing 0
            America/New_York 60 540 1773032400 1773061200 ok 0
            America/New_York 60 540 1773039000 1773061200 ok 0
            America/New_York 60 540 1773039600 1773061200 ok 1
            America/New_York 60 540 1773061200 1773061200 ok 1
            America/New_York 60 540 1793404800 1793365200 missing 1
            America/New_York 60 540 1793418600 1793365200 missing 1
            America/New_York 60 540 1793419200 1793365200 missing 0
            America/New_York 60 540 1793422200 1793365200 missing 0
            America/New_York 60 540 1793422800 1793451600 ok 0
            America/New_York 60 540 1793429400 1793451600 ok 0
            America/New_York 60 540 1793430000 1793451600 ok 1
            America/New_York 60 540 1793451600 1793451600 ok 1
            America/New_York 60 540 1793452200 1793451600 missing 1
            America/New_York 60 540 1793505000 1793451600 missing 1
            America/New_York 60 540 1793505600 1793451600 missing 0
            America/New_York 60 540 1793508600 1793451600 missing 0
            America/New_York 60 540 1793509200 1793538000 ok 0
            America/New_York 60 540 1793515800 1793538000 ok 0
            America/New_York 60 540 1793516400 1793538000 ok 1
            America/New_York 60 540 1793538000 1793538000 ok 1
            America/New_York 60 540 1793538600 1793538000 missing 1
            America/New_York 60 540 1793595000 1793538000 missing 1
            America/New_York 60 540 1793595600 1793538000 missing 0
            America/New_York 60 540 1793598600 1793538000 missing 0
            America/New_York 60 540 1793599200 1793628000 ok 0
            America/New_York 60 540 1793605800 1793628000 ok 0
            America/New_York 60 540 1793606400 1793628000 ok 1
            Europe/London 1350 390 1774650000 1774593000 missing 1
            Europe/London 1350 390 1774650600 1774679400 ok 1
            Europe/London 1350 390 1774655400 1774679400 ok 1
            Europe/London 1350 390 1774656000 1774679400 ok 0
            Europe/London 1350 390 1774666200 1774679400 ok 0
            Europe/London 1350 390 1774666800 1774679400 ok 1
            Europe/London 1350 390 1774679400 1774679400 ok 1
            Europe/London 1350 390 1774680000 1774679400 missing 1
            Europe/London 1350 390 1774736400 1774679400 missing 1
            Europe/London 1350 390 1774737000 1774765800 ok 1
            Europe/London 1350 390 1774741800 1774765800 ok 1
            Europe/London 1350 390 1774742400 1774765800 ok 0
            Europe/London 1350 390 1774752600 1774765800 ok 0
            Europe/London 1350 390 1774753200 1774765800 ok 1
            Europe/London 1350 390 1774765800 1774765800 ok 1
            Europe/London 1350 390 1774766400 1774765800 missing 1
            Europe/London 1350 390 1774807200 1774765800 missing 1
            Europe/London 1350 390 1774807800 1774679400 missing 1
            Europe/London 1350 390 1774809000 1774679400 missing 1
            Europe/London 1350 390 1774809600 1774765800 missing 1
            Europe/London 1350 390 1774819200 1774765800 missing 1
            Europe/London 1350 390 1774819800 1774848600 ok 1
            Europe/London 1350 390 1774824600 1774848600 ok 1
            Europe/London 1350 390 1774825200 1774848600 ok 0
            Europe/London 1350 390 1774835400 1774848600 ok 0
            Europe/London 1350 390 1774836000 1774848600 ok 1
            Europe/London 1350 390 1774848600 1774848600 ok 1
            Europe/London 1350 390 1774849200 1774848600 missing 1
            Europe/London 1350 390 1774854000 1774848600 missing 1
            Europe/London 1350 390 1792782000 1792733400 missing 1
            Europe/London 1350 390 1792790400 1792733400 missing 1
            Europe/London 1350 390 1792791000 1792819800 ok 1
            Europe/London 1350 390 1792795800 1792819800 ok 1
            Europe/London 1350 390 1792796400 1792819800 ok 0
            Europe/London 1350 390 1792806600 1792819800 ok 0
            Europe/London 1350 390 1792807200 1792819800 ok 1
            Europe/London 1350 390 1792819800 1792819800 ok 1
            Europe/London 1350 390 1792820400 1792819800 missing 1
            Europe/London 1350 390 1792876800 1792819800 missing 1
            Europe/London 1350 390 1792877400 1792906200 ok 1
            Europe/London 1350 390 1792882200 1792906200 ok 1
            Europe/London 1350 390 1792882800 1792906200 ok 0
            Europe/London 1350 390 1792893000 1792906200 ok 0
            Europe/London 1350 390 1792893600 1792906200 ok 1
            Europe/London 1350 390 1792906200 1792906200 ok 1
            Europe/London 1350 390 1792906800 1792906200 missing 1
            Europe/London 1350 390 1792966800 1792906200 missing 1
            Europe/London 1350 390 1792967400 1792996200 ok 1
            Europe/London 1350 390 1792972200 1792996200 ok 1
            Europe/London 1350 390 1792972800 1792996200 ok 0
            Europe/London 1350 390 1792983000 1792996200 ok 0
            Europe/London 1350 390 1792983600 1792996200 ok 1
            Europe/London 1350 390 1792996200 1792996200 ok 1
            Europe/London 1350 390 1792996800 1792996200 missing 1
            America/Havana 1350 390 1772853600 1772796600 missing 1
            America/Havana 1350 390 1772854200 1772883000 ok 1
            America/Havana 1350 390 1772859000 1772883000 ok 1
            America/Havana 1350 390 1772859600 1772883000 ok 0
            America/Havana 1350 390 1772869800 1772883000 ok 0
            America/Havana 1350 390 1772870400 1772883000 ok 1
            America/Havana 1350 390 1772883000 1772883000 ok 1
            America/Havana 1350 390 1772883600 1772883000 missing 1
            America/Havana 1350 390 1772940000 1772883000 missing 1
            America/Havana 1350 390 1772940600 1772969400 ok 1
            America/Havana 1350 390 1772945400 1772969400 ok 1
            America/Havana 1350 390 1772946000 1772969400 ok 0
            America/Havana 1350 390 1772956200 1772969400 ok 0
            America/Havana 1350 390 1772956800 1772969400 ok 1
            America/Havana 1350 390 1772969400 1772969400 ok 1
            America/Havana 1350 390 1772970000 1772969400 missing 1
            America/Havana 1350 390 1773026400 1772969400 missing 1
            America/Havana 1350 390 1773027000 1773055800 ok 1
            America/Havana 1350 390 1773028200 1773055800 ok 1
            America/Havana 1350 390 1773028800 1773052200 ok 0
            America/Havana 1350 390 1773039000 1773052200 ok 0
            America/Havana 1350 390 1773039600 1773052200 ok 1
            America/Havana 1350 390 1773052200 1773052200 ok 1
            America/Havana 1350 390 1773052800 1773052200 missing 1
            America/Havana 1350 390 1773054000 1773052200 missing 1
            America/Havana 1350 390 1793401200 1793356200 missing 1
            America/Havana 1350 390 1793413200 1793356200 missing 1
            America/Havana 1350 390 1793413800 1793442600 ok 1
            America/Havana 1350 390 1793418600 1793442600 ok 1
            America/Havana 1350 390 1793419200 1793442600 ok 0
            America/Havana 1350 390 1793429400 1793442600 ok 0
            America/Havana 1350 390 1793430000 1793442600 ok 1
            America/Havana 1350 390 1793442600 1793442600 ok 1
            America/Havana 1350 390 1793443200 1793442600 missing 1
            America/Havana 1350 390 1793499600 1793442600 missing 1
            America/Havana 1350 390 1793500200 1793529000 ok 1
            America/Havana 1350 390 1793505000 1793529000 ok 1
            America/Havana 1350 390 1793505600 1793529000 ok 0
            America/Havana 1350 390 1793515800 1793529000 ok 0
            America/Havana 1350 390 1793516400 1793529000 ok 1
            America/Havana 1350 390 1793529000 1793529000 ok 1
            America/Havana 1350 390 1793529600 1793529000 missing 1
            America/Havana 1350 390 1793589600 1793529000 missing 1
            America/Havana 1350 390 1793590200 1793619000 ok 1
            America/Havana 1350 390 1793595000 1793619000 ok 1
            America/Havana 1350 390 1793595600 1793619000 ok 0
            America/Havana 1350 390 1793605800 1793619000 ok 0
            America/Havana 1350 390 1793606400 1793619000 ok 1
            Australia/Lord_Howe 1350 390 1775215200 1775158200 missing 1
            Australia/Lord_Howe 1350 390 1775215800 1775244600 ok 1
            Australia/Lord_Howe 1350 390 1775220600 1775244600 ok 1
            Australia/Lord_Howe 1350 390 1775221200 1775244600 ok 0
            Australia/Lord_Howe 1350 390 1775231400 1775244600 ok 0
            Australia/Lord_Howe 1350 390 1775232000 1775244600 ok 1
            Australia/Lord_Howe 1350 390 1775244600 1775244600 ok 1
            Australia/Lord_Howe 1350 390 1775245200 1775244600 missing 1
            Australia/Lord_Howe 1350 390 1775301600 1775244600 missing 1
            Australia/Lord_Howe 1350 390 1775302200 1775331000 ok 1
            Australia/Lord_Howe 1350 390 1775307000 1775331000 ok 1
            Australia/Lord_Howe 1350 390 1775307600 1775331000 ok 0
            Australia/Lord_Howe 1350 390 1775317800 1775331000 ok 0
            Australia/Lord_Howe 1350 390 1775318400 1775331000 ok 1
            Australia/Lord_Howe 1350 390 1775331000 1775331000 ok 1
            Australia/Lord_Howe 1350 390 1775331600 1775331000 missing 1
            Australia/Lord_Howe 1350 390 1775389800 1775331000 missing 1
            Australia/Lord_Howe 1350 390 1775390400 1775419200 ok 1
            Australia/Lord_Howe 1350 390 1775395200 1775419200 ok 1
            Australia/Lord_Howe 1350 390 1775395800 1775419200 ok 0
            Australia/Lord_Howe 1350 390 1775406000 1775419200 ok 0
            Australia/Lord_Howe 1350 390 1775406600 1775419200 ok 1
            Australia/Lord_Howe 1350 390 1775419200 1775419200 ok 1
            Australia/Lord_Howe 1350 390 1775419800 1775419200 missing 1
            Australia/Lord_Howe 1350 390 1775422800 1775419200 missing 1
            Australia/Lord_Howe 1350 390 1790933400 1790884800 missing 1
            Australia/Lord_Howe 1350 390 1790941800 1790884800 missing 1
            Australia/Lord_Howe 1350 390 1790942400 1790971200 ok 1
            Australia/Lord_Howe 1350 390 1790947200 1790971200 ok 1
            Australia/Lord_Howe 1350 390 1790947800 1790971200 ok 0
            Australia/Lord_Howe 1350 390 1790958000 1790971200 ok 0
            Australia/Lord_Howe 1350 390 1790958600 1790971200 ok 1
            Australia/Lord_Howe 1350 390 1790971200 1790971200 ok 1
            Australia/Lord_Howe 1350 390 1790971800 1790971200 missing 1
            Australia/Lord_Howe 1350 390 1791028200 1790971200 missing 1
            Australia/Lord_Howe 1350 390 1791028800 1791057600 ok 1
            Australia/Lord_Howe 1350 390 1791033600 1791057600 ok 1
            Australia/Lord_Howe 1350 390 1791034200 1791057600 ok 0
            Australia/Lord_Howe 1350 390 1791044400 1791057600 ok 0
            Australia/Lord_Howe 1350 390 1791045000 1791057600 ok 1
            Australia/Lord_Howe 1350 390 1791057600 1791057600 ok 1
            Australia/Lord_Howe 1350 390 1791058200 1791057600 missing 1
            Australia/Lord_Howe 1350 390 1791099600 1791057600 missing 1
            Australia/Lord_Howe 1350 390 1791100200 1790971200 missing 1
            Australia/Lord_Howe 1350 390 1791100800 1790971200 missing 1
            Australia/Lord_Howe 1350 390 1791101400 1791057600 missing 1
            Australia/Lord_Howe 1350 390 1791112800 1791057600 missing 1
            Australia/Lord_Howe 1350 390 1791113400 1791142200 ok 1
            Australia/Lord_Howe 1350 390 1791118200 1791142200 ok 1
            Australia/Lord_Howe 1350 390 1791118800 1791142200 ok 0
            Australia/Lord_Howe 1350 390 1791129000 1791142200 ok 0
            Australia/Lord_Howe 1350 390 1791129600 1791142200 ok 1
            Australia/Lord_Howe 1350 390 1791142200 1791142200 ok 1
            Australia/Lord_Howe 1350 390 1791142800 1791142200 missing 1
    """

    private fun statusName(s: MissedNight.Status): String = when (s) {
        MissedNight.Status.OK -> "ok"
        MissedNight.Status.NOT_SYNCED_YET -> "notSyncedYet"
        MissedNight.Status.MISSING -> "missing"
    }

    @Test
    fun bothDstTransitionsMatchUpstreamInFourZones() {
        val rows = dstRows.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(260, rows.size, "the measured table is complete")
        for (row in rows) {
            val f = row.split(' ')
            val zone = ZoneId.of(f[0])
            val bed = f[1].toInt()
            val wake = f[2].toInt()
            val now = s(f[3].toLong())
            assertEquals(s(f[4].toLong()), MissedNight.morningWake(now = now, bedMinutes = bed, wakeMinutes = wake, zone = zone), "morning wake $row")
            val st = MissedNight.status(
                now = now,
                bedMinutes = bed,
                wakeMinutes = wake,
                nightWake = now.minusSeconds(50 * 3600),
                wakeKnown = true,
                lastSyncAt = now.minusSeconds(60),
                zone = zone,
            )
            assertEquals(f[5], statusName(st), "status $row")
            assertEquals(
                f[6] == "1",
                MissedNight.endedToday(inBedEnd = now.minusSeconds(3 * 3600), nightKey = now.minusSeconds(50 * 3600), now = now, zone = zone),
                "ended today $row",
            )
        }
    }

    /**
     * Upstream's own quirk, kept for parity: on a spring-forward evening the "nearest wake" flips to
     * tomorrow's shortened day and the 24 h step back lands on the PREVIOUS day, so for about 20 to 40
     * minutes the morning wake is yesterday's (measured in New York, London, Havana and Lord Howe).
     */
    @Test
    fun theSpringForwardEveningStepsBackToYesterdaysWakeAsUpstream() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 19:10 EDT: this morning's wake was 1772969400 (07:30 EDT on the wall clock, the
        // 06:30 schedule moved by the lost hour), but upstream answers 03-07's wake.
        assertEquals(s(1_772_883_000), MissedNight.morningWake(now = s(1_773_011_400), bedMinutes = 1350, wakeMinutes = 390, zone = ny))
        // Twenty minutes later it is back on this morning's wake.
        assertEquals(s(1_772_969_400), MissedNight.morningWake(now = s(1_773_013_200), bedMinutes = 1350, wakeMinutes = 390, zone = ny))
    }

    /** The schedule takes 32-bit minutes; upstream's 64-bit `Int` gives the same answers at the extremes (measured). */
    @Test
    fun thirtyTwoBitAndOutOfRangeScheduleMinutesAsUpstream() {
        val now = Instant.parse("2026-06-15T08:00:00Z")
        fun mw(b: Int, w: Int) = MissedNight.morningWake(now = now, bedMinutes = b, wakeMinutes = w, zone = utc)
        assertEquals(s(1_781_505_000), mw(Int.MAX_VALUE, 390))
        assertEquals(s(1_781_505_000), mw(Int.MIN_VALUE, 390))
        assertEquals(s(1_781_489_220), mw(1350, Int.MAX_VALUE))
        assertEquals(s(1_781_473_920), mw(1350, Int.MIN_VALUE))
        assertEquals(s(1_781_489_220), mw(Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(s(1_781_505_000), mw(-30, 390))
        assertEquals(s(1_781_505_000), mw(1350 + 1440 * 5, 390))
        assertEquals(s(1_781_505_000), mw(1350, -1050))
    }

    @Test
    fun strictBoundariesAndFutureInstantsAsUpstream() {
        val stale = Instant.parse("2026-06-13T06:30:00Z")
        val wake = Instant.parse("2026-06-15T06:30:00Z")
        fun st(now: Instant, nightWake: Instant?, known: Boolean, sync: Instant?, bed: Int = 1350, wk: Int = 390) =
            MissedNight.status(now = now, bedMinutes = bed, wakeMinutes = wk, nightWake = nightWake, wakeKnown = known, lastSyncAt = sync, zone = utc)
        assertEquals(MissedNight.Status.OK, st(wake, stale, true, wake), "now exactly at the wake is not past it")
        assertEquals(MissedNight.Status.NOT_SYNCED_YET, st(wake.plusSeconds(1), stale, true, wake), "a sync exactly at the wake is not after it")
        assertEquals(MissedNight.Status.MISSING, st(wake.plusSeconds(60), stale, true, wake.plusSeconds(86_400L * 30)), "a sync dated in the future still counts")
        assertEquals(MissedNight.Status.MISSING, st(wake.plusSeconds(3600), wake.plusSeconds(86_400L * 3), true, wake.plusSeconds(60)), "a night dated in the future did not end today")
        assertEquals(MissedNight.Status.OK, st(wake.plusSeconds(3600), null, true, wake.plusSeconds(60)), "known but missing wake")
        assertEquals(MissedNight.Status.OK, st(wake.plusSeconds(3600), stale, false, wake.plusSeconds(60)), "a wake not known is never judged")
        assertEquals(MissedNight.Status.OK, st(wake.plusSeconds(3600), stale, true, wake.plusSeconds(60), bed = 390, wk = 390), "degenerate schedule")
        assertFalse(MissedNight.endedToday(inBedEnd = null, nightKey = SleepEdit.DISTANT_PAST, now = wake, zone = utc))
    }

    /**
     * Instants the calendar cannot place give no wake, no claim and no credit (fail closed). Upstream's
     * calendar has stopped being a calendar long before `java.time`'s limit (see the night-key hazard
     * test), so there is no upstream value to match there.
     */
    @Test
    fun instantsBeyondTheCalendarFailClosed() {
        val stale = Instant.parse("2026-06-13T06:30:00Z")
        val wake = Instant.parse("2026-06-15T06:30:00Z")
        for (edge in listOf(Instant.MAX, Instant.MIN)) {
            assertNull(MissedNight.morningWake(now = edge, bedMinutes = 1350, wakeMinutes = 390, zone = utc))
            assertEquals(
                MissedNight.Status.OK,
                MissedNight.status(now = edge, bedMinutes = 1350, wakeMinutes = 390, nightWake = stale, wakeKnown = true, lastSyncAt = edge, zone = utc),
            )
            assertFalse(MissedNight.endedToday(inBedEnd = edge, nightKey = stale, now = edge, zone = utc), "no credit when the day cannot be placed")
            assertFalse(MissedNight.endedToday(inBedEnd = edge, nightKey = stale, now = wake, zone = utc))
        }
        // A night dated beyond the calendar did not end today, so after a post-wake sync it is missing
        // (upstream, with a night dated 3.2e16 s: missing too).
        assertEquals(
            MissedNight.Status.MISSING,
            MissedNight.status(now = wake.plusSeconds(3600), bedMinutes = 1350, wakeMinutes = 390, nightWake = Instant.MAX, wakeKnown = true, lastSyncAt = wake.plusSeconds(60), zone = utc),
        )
    }
}
