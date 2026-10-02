package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/BatteryTTETests.swift (@ b1c2fdd):
 * 26 of its 27 tests. `testSampleCodableRoundTrip` (`:154`) is not ported — it needs the samples'
 * stored form, which belongs to the storage epic (`PORTING.md` names it).
 *
 * Upstream's `timeToEmpty`, `estimatedDepletionDate` and `timeToFull` default `now` to the device
 * clock; here `now` is required. Where upstream relied on that default, each call names [t0]: the
 * time to empty and the time to full never read `now`, and the one test that does (the depletion
 * date) already passed its own.
 */
class BatteryTTETest {

    private val t0: Instant = Instant.ofEpochSecond(0)

    private fun sample(pct: Int, hours: Double): BatteryTTE.Sample =
        BatteryTTE.Sample(percent = pct, at = t0.plusMillis(Math.round(hours * 3_600_000)))

    private fun after(seconds: Double): Instant = t0.plusMillis(Math.round(seconds * 1_000))

    // MARK: - timeToEmpty

    @Test
    fun testCleanDischarge() {
        // 10 % drop in 1 hour starting at 100 % → rate = 10 %/hr → TTE = 90% / 10%/hr = 9 h
        val samples = listOf(sample(100, hours = 0.0), sample(90, hours = 1.0))
        val tte = assertNotNull(BatteryTTE.timeToEmpty(samples, now = after(1 * 3_600.0)))
        // Expected: 90 % / 10 %/hr × 3600 = 32 400 s (9 h)
        assertEquals(32_400.0, tte, 1.0)
    }

    @Test
    fun testNilOnRisingTrend() {
        // Strictly rising = charging → nil
        val samples = listOf(sample(80, hours = 0.0), sample(85, hours = 1.0), sample(90, hours = 2.0))
        assertNull(BatteryTTE.timeToEmpty(samples, now = t0))
    }

    @Test
    fun testNilOnFewerThanTwoSamples() {
        assertNull(BatteryTTE.timeToEmpty(emptyList(), now = t0))
        assertNull(BatteryTTE.timeToEmpty(listOf(sample(80, hours = 0.0)), now = t0))
    }

    @Test
    fun testNilOnImplausibleRate() {
        // 60 % drop in 1 hour → 60 %/hr > 50 %/hr threshold → nil
        val samples = listOf(sample(80, hours = 0.0), sample(20, hours = 1.0))
        assertNull(BatteryTTE.timeToEmpty(samples, now = t0))
    }

    @Test
    fun testNilOnSmallDrop() {
        // 1 % drop — below the noise floor (< 2 pp)
        val samples = listOf(sample(80, hours = 0.0), sample(79, hours = 1.0))
        assertNull(BatteryTTE.timeToEmpty(samples, now = t0))
    }

    @Test
    fun testRisingThenFallingResetsWindow() {
        // [70, 80, 75]: rises then falls. The window after the rise-reset is [80, 75].
        // Drop = 5 pp, elapsed = 1 h → rate = 5 %/hr → TTE = 75/5 × 3600 = 54 000 s
        val samples = listOf(sample(70, hours = 0.0), sample(80, hours = 1.0), sample(75, hours = 2.0))
        val tte = assertNotNull(BatteryTTE.timeToEmpty(samples, now = t0))
        assertEquals(54_000.0, tte, 1.0)
    }

    @Test
    fun testFlatSamplesSkipped() {
        // [90, 90, 88]: flat then drop of 2 pp. The flat sample doesn't break the window,
        // but the effective discharging window is [90@t0, 88@t2].
        // Actually in the algorithm, flat is skipped so window = [90@t0, 88@t2]:
        // drop=2, elapsed=2h → rate=1%/hr → TTE = 88/1 × 3600 = 316 800 s
        val samples = listOf(sample(90, hours = 0.0), sample(90, hours = 1.0), sample(88, hours = 2.0))
        val tte = BatteryTTE.timeToEmpty(samples, now = t0)
        assertNotNull(tte)
    }

    // MARK: - estimatedDepletionDate

    @Test
    fun testEstimatedDepletionDate() {
        val samples = listOf(sample(100, hours = 0.0), sample(90, hours = 1.0))
        val now = after(1 * 3_600.0) // now = end of last sample
        val depletion = assertNotNull(BatteryTTE.estimatedDepletionDate(samples, now = now))
        // TTE = 9 h → depletion at now + 9 h
        assertEquals(9 * 3_600.0, secondsBetween(now, depletion), 1.0)
    }

    @Test
    fun testEstimatedDepletionNilWhenNoTTE() {
        assertNull(BatteryTTE.estimatedDepletionDate(emptyList(), now = t0))
    }

    // MARK: - justReachedFull

    @Test
    fun testJustReachedFullFires() {
        assertTrue(BatteryTTE.justReachedFull(percent = 100, inferredCharging = true, wasFull = false))
    }

    @Test
    fun testJustReachedFullDoesNotFireIfAlreadyFull() {
        assertFalse(BatteryTTE.justReachedFull(percent = 100, inferredCharging = true, wasFull = true))
    }

    @Test
    fun testJustReachedFullDoesNotFireIfNotCharging() {
        assertFalse(BatteryTTE.justReachedFull(percent = 100, inferredCharging = false, wasFull = false))
    }

    @Test
    fun testJustReachedFullDoesNotFireBelow100() {
        assertFalse(BatteryTTE.justReachedFull(percent = 99, inferredCharging = true, wasFull = false))
    }

    // MARK: - record (robust history accumulation, #86)

    @Test
    fun testRecordAppendsDischargeStep() {
        val h = listOf(sample(80, hours = 0.0))
        val out = BatteryTTE.record(h, percent = 79, at = after(3_600.0), charging = false)
        assertEquals(listOf(80, 79), out.map { it.percent })
    }

    @Test
    fun testRecordIgnoresEqualAndSmallNoiseRise() {
        var h = listOf(sample(80, hours = 0.0), sample(79, hours = 1.0))
        // equal reading → ignored (keeps first-seen time)
        h = BatteryTTE.record(h, percent = 79, at = after(2 * 3_600.0), charging = false)
        assertEquals(listOf(80, 79), h.map { it.percent })
        // +1 pp jitter while NOT charging → ignored, slope preserved
        h = BatteryTTE.record(h, percent = 80, at = after(3 * 3_600.0), charging = false)
        assertEquals(listOf(80, 79), h.map { it.percent })
        // discharge continues cleanly afterwards
        h = BatteryTTE.record(h, percent = 78, at = after(4 * 3_600.0), charging = false)
        assertEquals(listOf(80, 79, 78), h.map { it.percent })
    }

    @Test
    fun testRecordResetsBaselineWhenCharging() {
        val h = listOf(sample(80, hours = 0.0), sample(70, hours = 5.0))
        val out = BatteryTTE.record(h, percent = 71, at = after(6 * 3_600.0), charging = true)
        assertEquals(listOf(71), out.map { it.percent }, "a charging frame invalidates the discharge slope")
    }

    @Test
    fun testRecordResetsOnMissedChargeJump() {
        val h = listOf(sample(60, hours = 0.0), sample(55, hours = 5.0))
        // +20 pp while the byte says not-charging → a charge we missed between frames → reset
        val out = BatteryTTE.record(h, percent = 75, at = after(6 * 3_600.0), charging = false)
        assertEquals(listOf(75), out.map { it.percent })
    }

    @Test
    fun testRecordPersistsAcrossReconnectIntoUsableEstimate() {
        // Simulate readings folded over hours, then confirm a clean TTE comes out — the
        // "survives reconnect" guarantee is that the array itself is the persisted state.
        var h: List<BatteryTTE.Sample> = emptyList()
        h = BatteryTTE.record(h, percent = 90, at = t0, charging = false)
        h = BatteryTTE.record(h, percent = 88, at = after(2 * 3_600.0), charging = false)
        val tte = assertNotNull(BatteryTTE.timeToEmpty(h, now = after(2 * 3_600.0)), "2 pp over 2 h → 1 %/hr → ~88 h left")
        assertEquals(88.0 * 3_600, tte, 60.0)
    }

    @Test
    fun testRecordPrunesByCap() {
        var h: List<BatteryTTE.Sample> = emptyList()
        for (i in 0 until 80) h = BatteryTTE.record(h, percent = 100 - i, at = after(i.toDouble() * 60), charging = false, cap = 60)
        assertEquals(60, h.size)
        assertEquals(21, h.lastOrNull()?.percent) // most-recent retained
    }

    @Test
    fun testRecordPrunesByAge() {
        val old = listOf(BatteryTTE.Sample(percent = 90, at = t0))
        // a reading 15 days later → the 14-day-old sample is pruned
        val out = BatteryTTE.record(old, percent = 89, at = after(15 * 86_400.0), charging = false)
        assertEquals(listOf(89), out.map { it.percent })
    }

    // MARK: - timeToFull + recordCharge (time-to-full, #61)

    @Test
    fun testTimeToFullCleanCharge() {
        // 66→74 % over 6 min = 80 %/hr; 26 % left → 26/80 h = 0.325 h = 1170 s
        val s = listOf(
            BatteryTTE.Sample(percent = 66, at = t0),
            BatteryTTE.Sample(percent = 74, at = after(6 * 60.0)),
        )
        val ttf = assertNotNull(BatteryTTE.timeToFull(s, now = after(6 * 60.0)))
        assertEquals(1170.0, ttf, 5.0)
    }

    @Test
    fun testTimeToFullZeroWhenAlreadyFull() {
        val s = listOf(sample(98, hours = 0.0), sample(100, hours = 0.5))
        assertEquals(0.0, BatteryTTE.timeToFull(s, now = t0))
    }

    @Test
    fun testTimeToFullNilOnFalling() {
        assertNull(BatteryTTE.timeToFull(listOf(sample(80, hours = 0.0), sample(78, hours = 1.0)), now = t0))
    }

    @Test
    fun testTimeToFullResetsWindowOnUnplug() {
        // rise, then a fall (unplug), then rise again → only the trailing rising run counts
        val s = listOf(
            sample(50, hours = 0.0), sample(60, hours = 0.2),
            sample(55, hours = 0.3), // unplug dip
            sample(57, hours = 0.4), sample(65, hours = 0.5),
        )
        val ttf = BatteryTTE.timeToFull(s, now = after(0.5 * 3_600))
        assertNotNull(ttf) // measured over 57→65 only
    }

    @Test
    fun testRecordChargeAccumulatesWhileChargingAndClearsWhenNot() {
        var h: List<BatteryTTE.Sample> = emptyList()
        h = BatteryTTE.recordCharge(h, percent = 66, at = t0, charging = true)
        h = BatteryTTE.recordCharge(h, percent = 68, at = after(60.0), charging = true)
        h = BatteryTTE.recordCharge(h, percent = 70, at = after(120.0), charging = true)
        assertEquals(listOf(66, 68, 70), h.map { it.percent })
        // Unplugged → charge history clears so a stale slope can't linger.
        h = BatteryTTE.recordCharge(h, percent = 70, at = after(180.0), charging = false)
        assertTrue(h.isEmpty())
    }

    @Test
    fun testRecordChargeIgnoresTinyDropResetsOnBigDrop() {
        var h = listOf(BatteryTTE.Sample(percent = 80, at = t0))
        h = BatteryTTE.recordCharge(h, percent = 79, at = after(30.0), charging = true) // tiny dip → ignore
        assertEquals(listOf(80), h.map { it.percent })
        h = BatteryTTE.recordCharge(h, percent = 76, at = after(60.0), charging = true) // ≥3 drop → reset
        assertEquals(listOf(76), h.map { it.percent })
    }
}
