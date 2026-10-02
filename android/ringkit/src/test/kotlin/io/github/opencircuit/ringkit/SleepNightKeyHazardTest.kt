package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input and calendar-edge checks for the night key and the re-key plan: both 2026
 * DST transitions in eleven zones (including midnight gaps and a repeated midnight, a 30-minute DST
 * shift and offsets of :30 and :45), the distant-past sentinel, instants beyond the calendar,
 * reversed and duplicated segments, and hostile row sets for the plan (duplicates, ties, a two-day
 * move, backward moves, a far-off row). Kept out of the upstream-port classes so their counts stay
 * exact.
 *
 * Every expected value was measured on upstream's pinned Swift build (Foundation's Gregorian
 * calendar in the named zone). A sweep of 19 521 instants (every 15 minutes ±1 s across ±30 h of each
 * 2026 transition) agreed with `java.time` on every one; the table below keeps the instants either
 * side of each transition, each midnight and each noon.
 */
class SleepNightKeyHazardTest {

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()
    private fun s(epoch: Long): Instant = Instant.ofEpochSecond(epoch)
    private val ny: ZoneId = ZoneId.of("America/New_York")
    private val utc: ZoneId = ZoneOffset.UTC

    /** Measured upstream: zone, instant (epoch s), key of a night ending then (= its start of day), whether it ends in the wake window. */
    private val dstRows = """
            America/New_York 1772859599 1772773200 0
            America/New_York 1772859600 1772859600 1
            America/New_York 1772902799 1772859600 1
            America/New_York 1772902800 1772859600 0
            America/New_York 1772945999 1772859600 0
            America/New_York 1772946000 1772946000 1
            America/New_York 1772953199 1772946000 1
            America/New_York 1772953200 1772946000 1
            America/New_York 1772953201 1772946000 1
            America/New_York 1772985599 1772946000 1
            America/New_York 1772985600 1772946000 0
            America/New_York 1773028799 1772946000 0
            America/New_York 1773028800 1773028800 1
            America/New_York 1793419199 1793332800 0
            America/New_York 1793419200 1793419200 1
            America/New_York 1793462399 1793419200 1
            America/New_York 1793462400 1793419200 0
            America/New_York 1793505599 1793419200 0
            America/New_York 1793505600 1793505600 1
            America/New_York 1793512799 1793505600 1
            America/New_York 1793512800 1793505600 1
            America/New_York 1793512801 1793505600 1
            America/New_York 1793552399 1793505600 1
            America/New_York 1793552400 1793505600 0
            America/New_York 1793595599 1793505600 0
            America/New_York 1793595600 1793595600 1
            Europe/London 1774655999 1774569600 0
            Europe/London 1774656000 1774656000 1
            Europe/London 1774699199 1774656000 1
            Europe/London 1774699200 1774656000 0
            Europe/London 1774742399 1774656000 0
            Europe/London 1774742400 1774742400 1
            Europe/London 1774745999 1774742400 1
            Europe/London 1774746000 1774742400 1
            Europe/London 1774746001 1774742400 1
            Europe/London 1774781999 1774742400 1
            Europe/London 1774782000 1774742400 0
            Europe/London 1774825199 1774742400 0
            Europe/London 1774825200 1774825200 1
            Europe/London 1792796399 1792710000 0
            Europe/London 1792796400 1792796400 1
            Europe/London 1792839599 1792796400 1
            Europe/London 1792839600 1792796400 0
            Europe/London 1792882799 1792796400 0
            Europe/London 1792882800 1792882800 1
            Europe/London 1792889999 1792882800 1
            Europe/London 1792890000 1792882800 1
            Europe/London 1792890001 1792882800 1
            Europe/London 1792929599 1792882800 1
            Europe/London 1792929600 1792882800 0
            Europe/London 1792972799 1792882800 0
            Europe/London 1792972800 1792972800 1
            America/Santiago 1775271599 1775185200 0
            America/Santiago 1775271600 1775271600 1
            America/Santiago 1775314799 1775271600 1
            America/Santiago 1775314800 1775271600 0
            America/Santiago 1775357999 1775271600 0
            America/Santiago 1775358000 1775271600 0
            America/Santiago 1775358001 1775271600 0
            America/Santiago 1775361599 1775271600 0
            America/Santiago 1775361600 1775361600 1
            America/Santiago 1775404799 1775361600 1
            America/Santiago 1775404800 1775361600 0
            America/Santiago 1775447999 1775361600 0
            America/Santiago 1775448000 1775448000 1
            America/Santiago 1788580799 1788494400 0
            America/Santiago 1788580800 1788580800 1
            America/Santiago 1788623999 1788580800 1
            America/Santiago 1788624000 1788580800 0
            America/Santiago 1788667199 1788580800 0
            America/Santiago 1788667200 1788667200 1
            America/Santiago 1788667201 1788667200 1
            America/Santiago 1788706799 1788667200 1
            America/Santiago 1788706800 1788667200 0
            America/Santiago 1788749999 1788667200 0
            America/Santiago 1788750000 1788750000 1
            America/Havana 1772859599 1772773200 0
            America/Havana 1772859600 1772859600 1
            America/Havana 1772902799 1772859600 1
            America/Havana 1772902800 1772859600 0
            America/Havana 1772945999 1772859600 0
            America/Havana 1772946000 1772946000 1
            America/Havana 1772946001 1772946000 1
            America/Havana 1772985599 1772946000 1
            America/Havana 1772985600 1772946000 0
            America/Havana 1773028799 1772946000 0
            America/Havana 1773028800 1773028800 1
            America/Havana 1793419199 1793332800 0
            America/Havana 1793419200 1793419200 1
            America/Havana 1793462399 1793419200 1
            America/Havana 1793462400 1793419200 0
            America/Havana 1793505599 1793419200 0
            America/Havana 1793505600 1793505600 1
            America/Havana 1793509199 1793505600 1
            America/Havana 1793509200 1793505600 1
            America/Havana 1793509201 1793505600 1
            America/Havana 1793552399 1793505600 1
            America/Havana 1793552400 1793505600 0
            America/Havana 1793595599 1793505600 0
            America/Havana 1793595600 1793595600 1
            Asia/Beirut 1774648799 1774562400 0
            Asia/Beirut 1774648800 1774648800 1
            Asia/Beirut 1774691999 1774648800 1
            Asia/Beirut 1774692000 1774648800 0
            Asia/Beirut 1774735199 1774648800 0
            Asia/Beirut 1774735200 1774735200 1
            Asia/Beirut 1774735201 1774735200 1
            Asia/Beirut 1774774799 1774735200 1
            Asia/Beirut 1774774800 1774735200 0
            Asia/Beirut 1774817999 1774735200 0
            Asia/Beirut 1774818000 1774818000 1
            Asia/Beirut 1792789199 1792702800 0
            Asia/Beirut 1792789200 1792789200 1
            Asia/Beirut 1792832399 1792789200 1
            Asia/Beirut 1792832400 1792789200 0
            Asia/Beirut 1792875599 1792789200 0
            Asia/Beirut 1792875600 1792789200 0
            Asia/Beirut 1792875601 1792789200 0
            Asia/Beirut 1792879199 1792789200 0
            Asia/Beirut 1792879200 1792879200 1
            Asia/Beirut 1792922399 1792879200 1
            Asia/Beirut 1792922400 1792879200 0
            Asia/Beirut 1792965599 1792879200 0
            Asia/Beirut 1792965600 1792965600 1
            Australia/Lord_Howe 1775221199 1775134800 0
            Australia/Lord_Howe 1775221200 1775221200 1
            Australia/Lord_Howe 1775264399 1775221200 1
            Australia/Lord_Howe 1775264400 1775221200 0
            Australia/Lord_Howe 1775307599 1775221200 0
            Australia/Lord_Howe 1775307600 1775307600 1
            Australia/Lord_Howe 1775314799 1775307600 1
            Australia/Lord_Howe 1775314800 1775307600 1
            Australia/Lord_Howe 1775314801 1775307600 1
            Australia/Lord_Howe 1775352599 1775307600 1
            Australia/Lord_Howe 1775352600 1775307600 0
            Australia/Lord_Howe 1775395799 1775307600 0
            Australia/Lord_Howe 1775395800 1775395800 1
            Australia/Lord_Howe 1790947799 1790861400 0
            Australia/Lord_Howe 1790947800 1790947800 1
            Australia/Lord_Howe 1790990999 1790947800 1
            Australia/Lord_Howe 1790991000 1790947800 0
            Australia/Lord_Howe 1791034199 1790947800 0
            Australia/Lord_Howe 1791034200 1791034200 1
            Australia/Lord_Howe 1791041399 1791034200 1
            Australia/Lord_Howe 1791041400 1791034200 1
            Australia/Lord_Howe 1791041401 1791034200 1
            Australia/Lord_Howe 1791075599 1791034200 1
            Australia/Lord_Howe 1791075600 1791034200 0
            Australia/Lord_Howe 1791118799 1791034200 0
            Australia/Lord_Howe 1791118800 1791118800 1
            Pacific/Chatham 1775211299 1775124900 0
            Pacific/Chatham 1775211300 1775211300 1
            Pacific/Chatham 1775254499 1775211300 1
            Pacific/Chatham 1775254500 1775211300 0
            Pacific/Chatham 1775297699 1775211300 0
            Pacific/Chatham 1775297700 1775297700 1
            Pacific/Chatham 1775311199 1775297700 1
            Pacific/Chatham 1775311200 1775297700 1
            Pacific/Chatham 1775311201 1775297700 1
            Pacific/Chatham 1775344499 1775297700 1
            Pacific/Chatham 1775344500 1775297700 0
            Pacific/Chatham 1775387699 1775297700 0
            Pacific/Chatham 1775387700 1775387700 1
            Pacific/Chatham 1790334899 1790248500 0
            Pacific/Chatham 1790334900 1790334900 1
            Pacific/Chatham 1790378099 1790334900 1
            Pacific/Chatham 1790378100 1790334900 0
            Pacific/Chatham 1790421299 1790334900 0
            Pacific/Chatham 1790421300 1790421300 1
            Pacific/Chatham 1790431199 1790421300 1
            Pacific/Chatham 1790431200 1790421300 1
            Pacific/Chatham 1790431201 1790421300 1
            Pacific/Chatham 1790460899 1790421300 1
            Pacific/Chatham 1790460900 1790421300 0
            Pacific/Chatham 1790504099 1790421300 0
            Pacific/Chatham 1790504100 1790504100 1
            Asia/Kolkata 1781418599 1781375400 1
            Asia/Kolkata 1781418600 1781375400 0
            Asia/Kolkata 1781461799 1781375400 0
            Asia/Kolkata 1781461800 1781461800 1
            Asia/Kolkata 1781504999 1781461800 1
            Asia/Kolkata 1781505000 1781461800 0
            Asia/Kolkata 1781548199 1781461800 0
            Asia/Kolkata 1781548200 1781548200 1
            Asia/Kolkata 1781591399 1781548200 1
            Asia/Kolkata 1781591400 1781548200 0
            Asia/Kathmandu 1781417699 1781374500 1
            Asia/Kathmandu 1781417700 1781374500 0
            Asia/Kathmandu 1781460899 1781374500 0
            Asia/Kathmandu 1781460900 1781460900 1
            Asia/Kathmandu 1781504099 1781460900 1
            Asia/Kathmandu 1781504100 1781460900 0
            Asia/Kathmandu 1781547299 1781460900 0
            Asia/Kathmandu 1781547300 1781547300 1
            Asia/Kathmandu 1781590499 1781547300 1
            Asia/Kathmandu 1781590500 1781547300 0
            UTC 1781438399 1781395200 1
            UTC 1781438400 1781395200 0
            UTC 1781481599 1781395200 0
            UTC 1781481600 1781481600 1
            UTC 1781524799 1781481600 1
            UTC 1781524800 1781481600 0
            UTC 1781567999 1781481600 0
            UTC 1781568000 1781568000 1
            UTC 1781611199 1781568000 1
            UTC 1781611200 1781568000 0
            America/St_Johns 1772854199 1772767800 0
            America/St_Johns 1772854200 1772854200 1
            America/St_Johns 1772897399 1772854200 1
            America/St_Johns 1772897400 1772854200 0
            America/St_Johns 1772940599 1772854200 0
            America/St_Johns 1772940600 1772940600 1
            America/St_Johns 1772947799 1772940600 1
            America/St_Johns 1772947800 1772940600 1
            America/St_Johns 1772947801 1772940600 1
            America/St_Johns 1772980199 1772940600 1
            America/St_Johns 1772980200 1772940600 0
            America/St_Johns 1773023399 1772940600 0
            America/St_Johns 1773023400 1773023400 1
            America/St_Johns 1793413799 1793327400 0
            America/St_Johns 1793413800 1793413800 1
            America/St_Johns 1793456999 1793413800 1
            America/St_Johns 1793457000 1793413800 0
            America/St_Johns 1793500199 1793413800 0
            America/St_Johns 1793500200 1793500200 1
            America/St_Johns 1793507399 1793500200 1
            America/St_Johns 1793507400 1793500200 1
            America/St_Johns 1793507401 1793500200 1
            America/St_Johns 1793546999 1793500200 1
            America/St_Johns 1793547000 1793500200 0
            America/St_Johns 1793590199 1793500200 0
            America/St_Johns 1793590200 1793590200 1
    """

    /**
     * Both 2026 transitions in eleven zones. Includes Havana's spring-forward gap AT midnight (the day
     * starts at 01:00) and its fall-back hour that repeats midnight (the key is the FIRST midnight),
     * Santiago and Beirut's midnight transitions, Lord Howe's 30-minute shift, and offsets of :30
     * (Kolkata, St John's) and :45 (Kathmandu, Chatham).
     */
    @Test
    fun bothDstTransitionsKeyExactlyAsUpstreamInElevenZones() {
        val rows = dstRows.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(232, rows.size, "the measured table is complete")
        for (row in rows) {
            val f = row.split(' ')
            val zone = ZoneId.of(f[0])
            val t = s(f[1].toLong())
            val key = SleepNightKey.night(inBedStart = t.minusSeconds(8 * 3600), inBedEnd = t, zone = zone)
            assertEquals(s(f[2].toLong()), key, "key $row")
            assertEquals(f[3] == "1", SleepNightKey.endsInWakeWindow(t, zone), "wake window $row")
            // A row already filed under its correct key never moves.
            assertNull(SleepNightKey.rekeyed(storedNight = key!!, inBedStart = t.minusSeconds(8 * 3600), inBedEnd = t, zone = zone), "rekeyed $row")
        }
    }

    /** The distant-past sentinel: its start of day keeps New York's local mean time (-4:56:02), as upstream. */
    @Test
    fun theDistantPastSentinelKeysAndRekeysAsUpstream() {
        val dp = SleepEdit.DISTANT_PAST
        // Upstream: startOfDay(.distantPast) in New York = -62135838238 (printed 0000-12-31 there).
        assertEquals(s(-62_135_838_238), SleepNightKey.night(inBedStart = dp, inBedEnd = dp, zone = ny))
        assertEquals(s(-62_135_769_600), SleepNightKey.night(inBedStart = dp, inBedEnd = dp, zone = utc))
        // The write path with a sentinel end anchors on the real start.
        assertEquals(s(1_786_334_400), SleepNightKey.night(inBedStart = s(1_786_420_560), inBedEnd = dp, zone = ny))
        // rekeyed refuses a start AT the sentinel ...
        assertNull(SleepNightKey.rekeyed(storedNight = s(1_786_000_000), inBedStart = dp, inBedEnd = s(1_786_420_560), zone = ny))
        // ... but not one second after it: upstream's guard is strictly "after the distant past", so
        // such a row is moved to the sentinel's day (measured; kept for parity — no real row has it).
        assertEquals(
            s(-62_135_838_238),
            SleepNightKey.rekeyed(storedNight = s(1_786_000_000), inBedStart = dp.plusSeconds(1), inBedEnd = dp.plusSeconds(3601), zone = ny),
        )
        assertNull(SleepNightKey.rekeyed(storedNight = dp.plusSeconds(1), inBedStart = dp.plusSeconds(1), inBedEnd = dp.plusSeconds(3601), zone = ny))
    }

    /**
     * Instants `java.time` cannot place in a zone (the last year of `Instant`'s range) give no key,
     * no wake-window claim, and a window there never moves its row. Upstream's Foundation calendar has stopped being a calendar
     * long before that (measured: instants of 9.2e15 s and 3.15e16 s both key to 15928153804800, the
     * year 506 713; -3.15e16 s keys to -210866803200, Julian day 0), so there is no upstream value to
     * match out there; the real calendar is used up to `java.time`'s limit.
     */
    @Test
    fun instantsBeyondTheCalendarGiveNoKeyAndAWindowThereNeverMoves() {
        val edge = Instant.MAX
        assertNull(SleepNightKey.night(inBedStart = edge.minusSeconds(3600), inBedEnd = edge, zone = utc))
        assertNull(SleepNightKey.night(inBedStart = edge, inBedEnd = edge, zone = ny))
        assertNull(SleepNightKey.night(listOf(SleepSegment(edge.minusSeconds(3600), edge, SleepStage.ASLEEP_CORE)), utc))
        assertFalse(SleepNightKey.endsInWakeWindow(edge, utc), "an unplaceable end never owns a key")
        assertFalse(SleepNightKey.endsInWakeWindow(Instant.MIN, utc))
        assertNull(SleepNightKey.rekeyed(storedNight = s(1_786_420_560), inBedStart = edge.minusSeconds(3600), inBedEnd = edge, zone = utc))
        // A stored key that is not a placeable day is never the correct key: the row moves to its real
        // key, as upstream moves a row stored at 3.2e16 s (measured: 1786406400).
        assertEquals(s(1_786_406_400), SleepNightKey.rekeyed(storedNight = edge, inBedStart = s(1_786_420_560), inBedEnd = s(1_786_440_000), zone = utc))
        // Far but placeable: the real calendar day, at midnight UTC.
        val far = s(31_500_000_000_000_000)
        val key = assertNotNull(SleepNightKey.night(inBedStart = far.minusSeconds(3600), inBedEnd = far, zone = utc))
        assertEquals(0L, Math.floorMod(key.epochSecond, 86_400L))
        assertTrue(key <= far && far.epochSecond - key.epochSecond < 86_400)
    }

    @Test
    fun segmentEnvelopesOnHostileSegmentsAsUpstream() {
        // Measured upstream (New York): a reversed lone segment, a duplicated one, and an envelope whose
        // latest end belongs to a reversed segment all key to 2026-08-07 00:00 EDT.
        val reversed = listOf(SleepSegment(s(1_786_440_000), s(1_786_420_560), SleepStage.ASLEEP_CORE))
        assertEquals(s(1_786_420_800), SleepNightKey.night(reversed, ny))
        val dup = SleepSegment(s(1_786_420_560), s(1_786_450_000), SleepStage.ASLEEP_CORE)
        assertEquals(s(1_786_420_800), SleepNightKey.night(listOf(dup, dup), ny))
        val mixed = listOf(
            SleepSegment(s(1_786_420_560), s(1_786_430_000), SleepStage.ASLEEP_CORE),
            SleepSegment(s(1_786_500_000), s(1_786_410_000), SleepStage.AWAKE),
        )
        assertEquals(s(1_786_420_800), SleepNightKey.night(mixed, ny))
    }

    // MARK: the re-key plan on hostile row sets (every outcome measured upstream, New York unless stated)

    private fun row(night: Instant, start: Instant, end: Instant) = SleepNightRekeyPlan.Row(night, start, end)
    private fun describe(p: SleepNightRekeyPlan.Plan): String =
        "moves=" + p.moves.joinToString(",") { "${it.from.epochSecond}>${it.to.epochSecond}" } +
            " refused=" + p.refused.joinToString(",") { "${it.from.epochSecond}>${it.to.epochSecond}" }

    private val bedA = at(ny, 2026, 8, 4, 22, 26)
    private val wakeA = at(ny, 2026, 8, 5, 8, 59)
    private fun sod(t: Instant, zone: ZoneId): Instant = t.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    @Test
    fun duplicateRowsMoveOnceAndRefuseTheCopy() {
        val r = row(sod(bedA, ny), bedA, wakeA)
        assertEquals("moves=1785816000>1785902400 refused=1785816000>1785902400", describe(SleepNightRekeyPlan.plan(listOf(r, r), ny)))
    }

    @Test
    fun twoRowsOnOneStoredDayAreOrderedByTheirStoredInstantNotInputOrder() {
        val early = row(at(ny, 2026, 8, 4, 1, 0), bedA, wakeA)
        val late = row(at(ny, 2026, 8, 4, 23, 0), at(ny, 2026, 8, 4, 23, 0), at(ny, 2026, 8, 5, 7, 0))
        val expected = "moves=1785816000>1785902400 refused=1785816000>1785902400"
        assertEquals(expected, describe(SleepNightRekeyPlan.plan(listOf(early, late), ny)))
        assertEquals(expected, describe(SleepNightRekeyPlan.plan(listOf(late, early), ny)))
    }

    /** Equal stored instants are a tie in the newest-first sort; upstream's sort keeps input order (stable). */
    @Test
    fun tiedStoredInstantsKeepInputOrder() {
        val a = row(at(ny, 2026, 8, 4, 0, 0), bedA, wakeA)
        val b = row(at(ny, 2026, 8, 4, 0, 0), at(ny, 2026, 8, 4, 21, 0), at(ny, 2026, 8, 6, 7, 0))
        assertEquals("moves=1785816000>1785902400,1785816000>1785988800 refused=", describe(SleepNightRekeyPlan.plan(listOf(a, b), ny)))
        assertEquals("moves=1785816000>1785988800,1785816000>1785902400 refused=", describe(SleepNightRekeyPlan.plan(listOf(b, a), ny)))
    }

    /** A window that ends two days later wants a +2-day move; newest-first does not free that slot, so it is refused. */
    @Test
    fun aTwoDayMoveBehindAOneDayMoveIsRefused() {
        val twoDay = row(at(ny, 2026, 8, 4, 0, 0), at(ny, 2026, 8, 4, 21, 0), at(ny, 2026, 8, 6, 7, 0))
        val oneDay = row(at(ny, 2026, 8, 5, 0, 0), at(ny, 2026, 8, 5, 22, 0), at(ny, 2026, 8, 6, 6, 0))
        assertEquals("moves=1785902400>1785988800 refused=1785816000>1785988800", describe(SleepNightRekeyPlan.plan(listOf(twoDay, oneDay), ny)))
    }

    @Test
    fun backwardMovesAreAppliedAndASwapIsRefusedBothWays() {
        val back = row(at(ny, 2026, 8, 9, 0, 0), at(ny, 2026, 8, 6, 22, 0), at(ny, 2026, 8, 7, 6, 0))
        assertEquals("moves=1786248000>1786075200 refused=", describe(SleepNightRekeyPlan.plan(listOf(back), ny)))
        val a = row(at(ny, 2026, 8, 9, 0, 0), at(ny, 2026, 8, 7, 22, 0), at(ny, 2026, 8, 8, 6, 0))
        val b = row(at(ny, 2026, 8, 8, 0, 0), at(ny, 2026, 8, 8, 22, 0), at(ny, 2026, 8, 9, 6, 0))
        assertEquals("moves= refused=1786248000>1786161600,1786161600>1786248000", describe(SleepNightRekeyPlan.plan(listOf(a, b), ny)))
    }

    /** A row the calendar cannot place never moves and never blocks a real one (UTC). */
    @Test
    fun aRowBeyondTheCalendarNeverMovesNorBlocks() {
        val far = row(Instant.MAX, Instant.MAX.minusSeconds(3600), Instant.MAX)
        val real = row(sod(bedA, ny), bedA, wakeA)
        assertEquals("moves=1785801600>1785888000 refused=", describe(SleepNightRekeyPlan.plan(listOf(far, real), utc)))
        assertEquals("moves= refused=", describe(SleepNightRekeyPlan.plan(listOf(row(s(1_786_000_000), SleepEdit.DISTANT_PAST, wakeA)), ny)))
    }

    @Test
    fun bothLondonDstNightsAndHavanasRepeatedMidnightMoveOneCalendarDay() {
        val lon = ZoneId.of("Europe/London")
        val spring = at(lon, 2026, 3, 28, 23, 30)
        val fall = at(lon, 2026, 10, 24, 23, 30)
        val rows = listOf(
            row(sod(spring, lon), spring, at(lon, 2026, 3, 29, 7, 0)),
            row(sod(fall, lon), fall, at(lon, 2026, 10, 25, 7, 0)),
        )
        assertEquals("moves=1792796400>1792882800,1774656000>1774742400 refused=", describe(SleepNightRekeyPlan.plan(rows, lon)))
        // Havana repeats 00:00-00:59 on 2026-11-01; the day's key is its FIRST midnight (UTC-4).
        val hav = ZoneId.of("America/Havana")
        val hb = s(1_793_500_000)
        val hw = s(1_793_530_000)
        assertEquals("moves=1793419200>1793505600 refused=", describe(SleepNightRekeyPlan.plan(listOf(row(sod(hb, hav), hb, hw)), hav)))
    }
}
