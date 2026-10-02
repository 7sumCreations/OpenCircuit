package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 *
 * One deliberate divergence (an owner decision): before the nearest window's bedtime upstream steps
 * `now` back a fixed 24 h; Kotlin steps back one calendar day from that window's wake. The two differ
 * only in the 56 hours after a clock change that loses time: on the evening of that day and of the day
 * after, upstream answers a stale wake; and where the lost hour is the one after midnight, upstream
 * places the next morning's wake an hour late. Eight table rows (New York at both schedules, London,
 * Lord Howe) carry the Kotlin answer, not upstream's; their status and "ended today" columns are
 * unchanged. [onlyTheDaysAfterASpringForwardLeaveUpstreamsAnswer] proves nothing else moved.
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
            America/New_York 1350 390 1773011400 1772969400 missing 1
            America/New_York 1350 390 1773012600 1772969400 missing 1
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
            America/New_York 60 540 1773020400 1772978400 missing 1
            America/New_York 60 540 1773021600 1772978400 missing 1
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
            Europe/London 1350 390 1774807800 1774765800 missing 1
            Europe/London 1350 390 1774809000 1774765800 missing 1
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
            Australia/Lord_Howe 1350 390 1791100200 1791057600 missing 1
            Australia/Lord_Howe 1350 390 1791100800 1791057600 missing 1
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
     * On a spring-forward evening the nearest window is tomorrow's, and stepping back from it must land
     * on THIS morning's wake. Upstream steps back a fixed 24 h, which on the shortened day lands on the
     * day before and answers yesterday's wake for up to 30 minutes (measured: New York and London
     * 19:10–19:30, Lord Howe 18:50–19:00 at 10-minute resolution); there a sync from yesterday evening
     * would read as "after this morning's wake" and the banner could say MISSING. Kotlin steps back one
     * calendar day. Each case: zone, now (epoch s), this morning's wake, upstream's stale answer.
     */
    @Test
    fun aSpringForwardEveningStepsBackOneCalendarDayToThisMorningsWake() {
        val cases = listOf(
            // 2026-03-08 19:10 / 19:30 EDT: this morning's 06:30 schedule fell at 07:30 EDT.
            Triple("America/New_York", 1_773_011_400L, 1_772_969_400L) to 1_772_883_000L,
            Triple("America/New_York", 1_773_012_600L, 1_772_969_400L) to 1_772_883_000L,
            // 2026-03-29 19:10 / 19:30 BST.
            Triple("Europe/London", 1_774_807_800L, 1_774_765_800L) to 1_774_679_400L,
            Triple("Europe/London", 1_774_809_000L, 1_774_765_800L) to 1_774_679_400L,
            // 2026-10-04 18:50 / 19:00 +11:00 (Lord Howe's 30-minute shift).
            Triple("Australia/Lord_Howe", 1_791_100_200L, 1_791_057_600L) to 1_790_971_200L,
            Triple("Australia/Lord_Howe", 1_791_100_800L, 1_791_057_600L) to 1_790_971_200L,
        )
        for ((case, upstreamStale) in cases) {
            val (zone, now, thisMorning) = case
            val got = MissedNight.morningWake(now = s(now), bedMinutes = 1350, wakeMinutes = 390, zone = ZoneId.of(zone))
            assertEquals(s(thisMorning), got, "$zone at $now answers this morning's wake, not upstream's ${s(upstreamStale)}")
            // The banner consequence: a sync made yesterday evening is NOT after this morning's wake.
            val syncedYesterdayEvening = s(upstreamStale).plusSeconds(12 * 3600)
            assertEquals(
                MissedNight.Status.NOT_SYNCED_YET,
                MissedNight.status(
                    now = s(now),
                    bedMinutes = 1350,
                    wakeMinutes = 390,
                    nightWake = s(upstreamStale),
                    wakeKnown = true,
                    lastSyncAt = syncedYesterdayEvening,
                    zone = ZoneId.of(zone),
                ),
                "$zone at $now: yesterday's sync does not make last night MISSING",
            )
        }
    }

    /**
     * The fall-back evening of the same zones (a 25 h day; Lord Howe's is 24.5 h) keeps this morning's
     * wake: stepping back one calendar day from the nearest window's day lands on today, never two
     * wakes back. These are the instants where stepping `now` itself back a calendar day (25 h) and
     * then taking the nearest window WOULD have answered yesterday's wake (measured by the sweep below).
     * Each case: zone, now (epoch s), this morning's wake — upstream's answer too.
     */
    @Test
    fun aFallBackEveningKeepsThisMorningsWake() {
        val cases = listOf(
            Triple("America/New_York", 1_793_574_600L, 1_793_529_000L), // 2026-11-01 18:10 EST
            Triple("America/New_York", 1_793_575_800L, 1_793_529_000L), // 18:30 EST
            Triple("Europe/London", 1_792_951_800L, 1_792_906_200L), // 2026-10-25 18:10 GMT
            Triple("Europe/London", 1_792_953_000L, 1_792_906_200L), // 18:30 GMT
            Triple("Australia/Lord_Howe", 1_775_375_400L, 1_775_331_000L), // 2026-04-05 18:20 +10:30
            Triple("Australia/Lord_Howe", 1_775_376_000L, 1_775_331_000L), // 18:30 +10:30
        )
        for ((zone, now, thisMorning) in cases) {
            assertEquals(
                s(thisMorning),
                MissedNight.morningWake(now = s(now), bedMinutes = 1350, wakeMinutes = 390, zone = ZoneId.of(zone)),
                "$zone at $now",
            )
        }
    }

    /**
     * The same calendar-day step on the evening of the day AFTER the clock change, which upstream's
     * 24 h step also gets wrong, in two ways (now, this morning's scheduled wake, upstream's answer).
     *
     * Stale: 24 h back lands on the changed day, whose wake fell an hour late and is therefore the
     * nearer one, so upstream answers YESTERDAY's wake (New York, Havana). A sync made yesterday
     * evening then reads as "after this morning's wake".
     *
     * An hour late: where the lost hour is the one after midnight, the changed day starts at 01:00,
     * and upstream builds the next day's wake from that start, so it answers 07:30 for a 06:30
     * schedule (Santiago, Beirut) — although at noon the same day it answered 06:30. A sync made at
     * 07:00 was after the wake at noon and before it in the evening; here it stays after it all day.
     */
    @Test
    fun theEveningAfterASpringForwardDayAnswersThatMorningsScheduledWake() {
        fun wake(zone: String, now: String) =
            MissedNight.morningWake(now = Instant.parse(now), bedMinutes = 1350, wakeMinutes = 390, zone = ZoneId.of(zone))
        fun status(zone: String, now: String, nightWake: String, lastSync: String) = MissedNight.status(
            now = Instant.parse(now),
            bedMinutes = 1350,
            wakeMinutes = 390,
            nightWake = Instant.parse(nightWake),
            wakeKnown = true,
            lastSyncAt = Instant.parse(lastSync),
            zone = ZoneId.of(zone),
        )

        // Stale. 2026-03-09 18:40 -04:00, the day after the change: this morning's 06:30 is 10:30Z.
        for (zone in listOf("America/New_York", "America/Havana")) {
            val now = "2026-03-09T22:40:00Z"
            val upstreamStale = Instant.parse("2026-03-08T11:30:00Z") // 07:30 on the changed day
            assertEquals(upstreamStale, upstreamMorningWake(Instant.parse(now), 1350, 390, ZoneId.of(zone)), "$zone: upstream's answer")
            assertEquals(Instant.parse("2026-03-09T10:30:00Z"), wake(zone, now), zone)
            assertEquals(
                MissedNight.Status.NOT_SYNCED_YET,
                status(zone, now, nightWake = "2026-03-08T11:30:00Z", lastSync = "2026-03-08T23:30:00Z"),
                "$zone: yesterday evening's sync does not make last night MISSING",
            )
        }

        // An hour late. Santiago 2026-09-07 19:40 -03:00 and Beirut 2026-03-30 19:40 +03:00.
        val hourLate = listOf(
            listOf("America/Santiago", "2026-09-07T22:40:00Z", "2026-09-07T09:30:00Z", "2026-09-07T15:00:00Z", "2026-09-07T10:00:00Z", "2026-09-05T09:30:00Z"),
            listOf("Asia/Beirut", "2026-03-30T16:40:00Z", "2026-03-30T03:30:00Z", "2026-03-30T09:00:00Z", "2026-03-30T04:00:00Z", "2026-03-28T04:30:00Z"),
        )
        for (row in hourLate) {
            val (zone, evening, scheduled, noon, syncAtSeven) = row
            val staleNight = row[5]
            val expected = Instant.parse(scheduled)
            assertEquals(expected.plusSeconds(3600), upstreamMorningWake(Instant.parse(evening), 1350, 390, ZoneId.of(zone)), "$zone: upstream's answer")
            assertEquals(expected, wake(zone, evening), "$zone in the evening")
            assertEquals(expected, wake(zone, noon), "$zone at noon — the same wake all day")
            // A sync at 07:00 came after the 06:30 wake: the night is MISSING at noon and stays so.
            assertEquals(MissedNight.Status.MISSING, status(zone, noon, staleNight, syncAtSeven), "$zone at noon")
            assertEquals(MissedNight.Status.MISSING, status(zone, evening, staleNight, syncAtSeven), "$zone in the evening")
        }
    }

    /**
     * A late wake on the day that lost an hour falls past midnight (23:30 becomes 00:30 the next
     * day), yet it is still that day's wake: one calendar day before it is the previous day's 23:30,
     * not the same wake again. Day sleeper, bed 15:00, wake 23:30, New York 2026-03-08 13:00 EDT —
     * awake since last night's wake, the nearest wake is tonight's. Upstream agrees here.
     */
    @Test
    fun aWakePushedPastMidnightByTheLostHourIsStillItsOwnDays() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-08T17:00:00Z")
        val tonight = SleepWindow.interval(bedMinutes = 900, wakeMinutes = 1410, nightEndingNear = now, zone = zone)
        assertEquals(Instant.parse("2026-03-09T04:30:00Z"), tonight?.end, "tonight's wake lands at 00:30 on the 9th")
        val lastNight = Instant.parse("2026-03-08T04:30:00Z") // 23:30 EST on the 7th
        assertEquals(lastNight, MissedNight.morningWake(now = now, bedMinutes = 900, wakeMinutes = 1410, zone = zone))
        assertEquals(lastNight, upstreamMorningWake(now, 900, 1410, zone))
    }

    /**
     * Upstream's morning wake, transcribed: before the nearest window's bedtime, step back a fixed
     * 24 h and take the nearest window again. Measured identical to the pinned Swift build on every
     * point of the ±30 h sweep the measured table was drawn from (the same zones and transitions).
     */
    private fun upstreamMorningWake(now: Instant, bed: Int, wake: Int, zone: ZoneId): Instant? {
        val near = SleepWindow.interval(bedMinutes = bed, wakeMinutes = wake, nightEndingNear = now, zone = zone) ?: return null
        if (now >= near.start) return near.end
        return SleepWindow.interval(bedMinutes = bed, wakeMinutes = wake, nightEndingNear = now.minusSeconds(86_400), zone = zone)?.end
    }

    /** Whether [wake] is a wake the sleep window itself gives when asked about that moment. */
    private fun isAScheduledWake(wake: Instant, bedMinutes: Int, wakeMinutes: Int, zone: ZoneId): Boolean =
        SleepWindow.interval(bedMinutes = bedMinutes, wakeMinutes = wakeMinutes, nightEndingNear = wake, zone = zone)?.end == wake

    /**
     * The divergence is confined to the days after a clock change that loses time: every 10 minutes
     * across ±72 h of every 2026 transition in eleven zones (the fixed-offset zones swept across New
     * York's), ten schedules (including a day sleeper and one-minute windows), 190 300 points — 816
     * answers differ from upstream's, every one within 56 h after a spring-forward transition. No
     * fall-back point and no fixed-offset point moves.
     *
     * Every answer that differs is a wake the window itself gives, and has already passed. Upstream's
     * is then one of two things: a stale wake (369 points; ours is the later one), or — only where the
     * lost hour is the one after midnight (Santiago, Havana, Beirut) — a wake placed an hour late that
     * the window never gives (447 points; ours is the earlier, scheduled one).
     *
     * Measured once over the whole of 2026 (5 781 600 points): the same 816 and nothing else. At
     * one-minute resolution this sweep gives 8 343 of 1 901 020.
     */
    @Test
    fun onlyTheDaysAfterASpringForwardLeaveUpstreamsAnswer() {
        val zones = listOf(
            "America/New_York", "Europe/London", "America/Santiago", "America/Havana", "Asia/Beirut", "Australia/Lord_Howe",
            "Pacific/Chatham", "Asia/Kolkata", "Asia/Kathmandu", "America/St_Johns", "UTC",
        )
        val schedules = listOf(1350 to 390, 60 to 540, 1380 to 420, 0 to 480, 120 to 600, 1320 to 360, 720 to 1200, 1439 to 1, 1 to 1439, 30 to 0)
        val yearStart = Instant.parse("2026-01-01T00:00:00Z")
        val yearEnd = Instant.parse("2027-01-01T00:00:00Z")
        fun transitions(zone: ZoneId) = generateSequence(zone.rules.nextTransition(yearStart)) { zone.rules.nextTransition(it.instant) }
            .takeWhile { it.instant < yearEnd }
            .toList()
        val newYorkInstants = transitions(ZoneId.of("America/New_York")).map { it.instant to false }
        var points = 0
        val laterPerZone = sortedMapOf<String, Int>()
        val earlierPerZone = sortedMapOf<String, Int>()
        for (name in zones) {
            val zone = ZoneId.of(name)
            val own = transitions(zone).map { it.instant to it.isGap }
            assertEquals(if (name in setOf("Asia/Kolkata", "Asia/Kathmandu", "UTC")) 0 else 2, own.size, "$name has its 2026 transitions")
            for ((at, springForward) in own.ifEmpty { newYorkInstants }) {
                for ((bed, wake) in schedules) {
                    var now = at.minusSeconds(72 * 3600)
                    while (now <= at.plusSeconds(72 * 3600)) {
                        points++
                        val upstream = upstreamMorningWake(now, bed, wake, zone)
                        val got = MissedNight.morningWake(now = now, bedMinutes = bed, wakeMinutes = wake, zone = zone)
                        if (got != upstream) {
                            val where = "$name $bed/$wake at ${now.epochSecond}: upstream $upstream, got $got"
                            assertTrue(springForward, "only a spring-forward transition may differ — $where")
                            assertTrue(now > at && now < at.plusSeconds(56 * 3600), "only in the 56 h after it — $where")
                            assertTrue(upstream != null && got != null, where)
                            assertTrue(got!! <= now, "a wake already passed — $where")
                            assertTrue(isAScheduledWake(got, bed, wake, zone), "a wake the window itself gives — $where")
                            if (got > upstream!!) {
                                laterPerZone.merge(name, 1, Int::plus)
                            } else {
                                assertFalse(isAScheduledWake(upstream, bed, wake, zone), "upstream's later answer is a wake the window never gives — $where")
                                earlierPerZone.merge(name, 1, Int::plus)
                            }
                        }
                        now = now.plusSeconds(600)
                    }
                }
            }
        }
        assertEquals(190_300, points, "the sweep is complete")
        assertEquals(
            mapOf(
                "America/Havana" to 51, "America/New_York" to 48, "America/Santiago" to 51, "America/St_Johns" to 48,
                "Asia/Beirut" to 51, "Australia/Lord_Howe" to 24, "Europe/London" to 48, "Pacific/Chatham" to 48,
            ),
            laterPerZone.toMap(),
            "upstream's answer was a stale wake",
        )
        assertEquals(
            mapOf("America/Havana" to 149, "America/Santiago" to 149, "Asia/Beirut" to 149),
            earlierPerZone.toMap(),
            "upstream's answer was an hour late (midnight-gap zones only)",
        )
        assertEquals(816, laterPerZone.values.sum() + earlierPerZone.values.sum())
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
        // The earliest day java.time can place: before a 06:00 bedtime the window is that day's, and the
        // calendar day before it cannot be placed — no wake, no claim, never an exception.
        val firstDayOneAm = java.time.LocalDate.MIN.atTime(1, 0).toInstant(ZoneOffset.UTC)
        assertNull(MissedNight.morningWake(now = firstDayOneAm, bedMinutes = 360, wakeMinutes = 420, zone = utc))
        assertEquals(
            MissedNight.Status.OK,
            MissedNight.status(now = firstDayOneAm, bedMinutes = 360, wakeMinutes = 420, nightWake = stale, wakeKnown = true, lastSyncAt = firstDayOneAm, zone = utc),
        )
        // A night dated beyond the calendar did not end today, so after a post-wake sync it is missing
        // (upstream, with a night dated 3.2e16 s: missing too).
        assertEquals(
            MissedNight.Status.MISSING,
            MissedNight.status(now = wake.plusSeconds(3600), bedMinutes = 1350, wakeMinutes = 390, nightWake = Instant.MAX, wakeKnown = true, lastSyncAt = wake.plusSeconds(60), zone = utc),
        )
    }
}
