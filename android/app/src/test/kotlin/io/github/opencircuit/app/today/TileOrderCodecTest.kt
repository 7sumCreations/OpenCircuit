package io.github.opencircuit.app.today

import io.github.opencircuit.app.today.TodayTile.Metric
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The saved tile order: the same rules as upstream's section order (`DashboardSectionOrder.decode`)
 * applied to Today's eight tiles — unknown and duplicate ids dropped in place, every tile not named
 * appended in its canonical place at the end, a damaged or missing value read as the default — and
 * one-step moves for the Edit order buttons.
 */
class TileOrderCodecTest {
    private val canonical = Metric.entries.toList()

    @Test
    fun nothingSavedIsTheCanonicalOrder() {
        assertEquals(canonical, TileOrder.decode(null))
        assertEquals(canonical, TileOrder.decode(""))
    }

    @Test
    fun aSavedOrderOfAllEightRoundTrips() {
        val order = canonical.reversed()
        assertEquals(order, TileOrder.decode(TileOrder.encode(order)))
        assertEquals("steps,sleep,skin_temp,respiratory_rate,spo2,resting_hr,hrv,heart_rate", TileOrder.encode(order))
    }

    @Test
    fun unknownAndDuplicateIdsAreDroppedInPlace() {
        assertEquals(
            listOf(Metric.STEPS, Metric.HRV, Metric.HEART_RATE, Metric.RESTING_HR, Metric.SPO2, Metric.RESPIRATORY_RATE, Metric.SKIN_TEMP, Metric.SLEEP),
            TileOrder.decode("steps,strain,hrv,steps,HEART_RATE,,heart_rate"),
            "unknown (strain, an empty id, a wrong case) and the second steps leave no gap",
        )
    }

    @Test
    fun tilesTheSavedOrderDoesNotNameAreAppendedInCanonicalOrder() {
        // An order saved before a tile existed: the new tiles go at the end, in their canonical order.
        assertEquals(
            listOf(Metric.SLEEP, Metric.SPO2, Metric.HEART_RATE, Metric.HRV, Metric.RESTING_HR, Metric.RESPIRATORY_RATE, Metric.SKIN_TEMP, Metric.STEPS),
            TileOrder.decode("sleep,spo2"),
        )
    }

    @Test
    fun aDamagedValueReadsAsTheDefault() {
        for (damaged in listOf("{\"tiles\":[1,2]}", "   ", ",,,", "héart_rate;steps")) {
            assertEquals(canonical, TileOrder.decode(damaged), damaged)
        }
    }

    @Test
    fun aMoveIsOneStepAndStopsAtEitherEnd() {
        assertEquals(listOf(Metric.HRV, Metric.HEART_RATE) + canonical.drop(2), TileOrder.moved(canonical, Metric.HRV, -1))
        assertEquals(listOf(Metric.HRV, Metric.HEART_RATE) + canonical.drop(2), TileOrder.moved(canonical, Metric.HEART_RATE, +1))
        assertEquals(canonical, TileOrder.moved(canonical, Metric.HEART_RATE, -1), "the first cannot go up")
        assertEquals(canonical, TileOrder.moved(canonical, Metric.STEPS, +1), "the last cannot go down")
    }
}
