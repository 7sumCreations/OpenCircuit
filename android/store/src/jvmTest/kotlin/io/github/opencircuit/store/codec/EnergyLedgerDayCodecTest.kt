package io.github.opencircuit.store.codec

import io.github.opencircuit.store.EnergyLedgerDay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The stored form of one day's active-energy write ledger.
 *
 * Kotlin-only. Upstream keeps the fields as ten `UserDefaults` keys
 * (ios/OpenCircuit/Health/HealthKitWriter.swift:1262-1286 @ b1c2fdd); here they are one value.
 * Reading fails closed exactly where `ActiveEnergyLedger.plan` refuses to plan (PORTING D-79): a
 * NaN, infinite or negative watermark, or a NaN or infinite carry, workout credit or saved total,
 * makes the day unreadable, so a damaged ledger can never be read as a fresh day and paid again.
 * A negative carry, credit or saved total is kept: the plan counts it as zero, as upstream.
 */
class EnergyLedgerDayCodecTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val dayStart = Instant.parse("2026-10-02T18:30:00Z") // 00:00 on 3 October in Kolkata

    private val day = EnergyLedgerDay(
        day = dayStart,
        zone = kolkata,
        anchorEnd = Instant.parse("2026-10-03T04:15:00.250Z"),
        bucketKcal = listOf(0.0, 12.5, 40.25),
        carryKcal = 3.5,
        writtenKcal = 52.75,
        savedKcal = 50.0,
        bucketSeedDay = dayStart,
        workoutCreditedDay = dayStart,
        workoutCreditedKcal = 120.0,
    )

    private val fresh = EnergyLedgerDay(dayStart, ZoneId.of("UTC"), null, emptyList(), 0.0, 0.0, 0.0, null, null, 0.0)

    private fun with(key: String, value: String?): String {
        val m = LinkedHashMap(Json.parseToJsonElement(assertNotNull(EnergyLedgerDayCodec.encode(day))).jsonObject)
        if (value == null) m.remove(key) else m[key] = Json.parseToJsonElement(value)
        return JsonObject(m).toString()
    }

    @Test
    fun theStoredFormIsOneObjectWithTheTenFields() {
        assertEquals(
            """{"anchorEnd":1791000900250,"bucketKcal":[0.0,12.5,40.25],"bucketSeedDay":1790965800000,"carryKcal":3.5,""" +
                """"day":1790965800000,"dayTZ":"Asia/Kolkata","savedKcal":50.0,"workoutCreditedDay":1790965800000,""" +
                """"workoutCreditedKcal":120.0,"writtenKcal":52.75}""",
            EnergyLedgerDayCodec.encode(day),
        )
    }

    @Test
    fun aDayRoundTripsAndKnowsItsLocalDate() {
        val read = readable(EnergyLedgerDayCodec.decode(assertNotNull(EnergyLedgerDayCodec.encode(day))))
        assertEquals(day, read)
        assertEquals(LocalDate.of(2026, 10, 3), read.localDate)
        assertEquals(fresh, readable(EnergyLedgerDayCodec.decode(assertNotNull(EnergyLedgerDayCodec.encode(fresh)))))
    }

    @Test
    fun theOptionalDatesMayBeMissingOrNull() {
        for (key in listOf("anchorEnd", "bucketSeedDay", "workoutCreditedDay")) {
            for (v in listOf(null, "null")) {
                val read = readable(EnergyLedgerDayCodec.decode(with(key, v)))
                assertNull(
                    when (key) {
                        "anchorEnd" -> read.anchorEnd
                        "bucketSeedDay" -> read.bucketSeedDay
                        else -> read.workoutCreditedDay
                    },
                    "$key = $v",
                )
            }
        }
    }

    @Test
    fun eachMissingRequiredKeyIsUnreadable() {
        for (key in listOf("bucketKcal", "carryKcal", "day", "dayTZ", "savedKcal", "workoutCreditedKcal", "writtenKcal")) {
            val raw = with(key, null)
            assertUnreadable(EnergyLedgerDayCodec.decode(raw), raw, key)
        }
    }

    @Test
    fun aValueThePlanWouldRefuseMakesTheDayUnreadable() {
        for ((key, v) in listOf(
            "bucketKcal" to "[1.0,-0.5]",
            "bucketKcal" to "[1e400]",
            "bucketKcal" to "[\"NaN\"]",
            "carryKcal" to "1e400",
            "carryKcal" to "-1e400",
            "workoutCreditedKcal" to "\"NaN\"",
            "savedKcal" to "-1e400",
            "writtenKcal" to "1e400",
            "dayTZ" to "\"Mars/Olympus_Mons\"",
            "dayTZ" to "5",
            "day" to "1.5",
        )) {
            val raw = with(key, v)
            assertUnreadable(EnergyLedgerDayCodec.decode(raw), raw, "$key = $v")
        }
    }

    @Test
    fun aNegativeCarryCreditOrSavedTotalIsKept() {
        assertEquals(-4.0, readable(EnergyLedgerDayCodec.decode(with("carryKcal", "-4.0"))).carryKcal)
        assertEquals(-30.0, readable(EnergyLedgerDayCodec.decode(with("workoutCreditedKcal", "-30"))).workoutCreditedKcal)
        assertEquals(-1.0, readable(EnergyLedgerDayCodec.decode(with("savedKcal", "-1"))).savedKcal)
        // A watermark of exactly zero is a real watermark.
        assertEquals(listOf(0.0), readable(EnergyLedgerDayCodec.decode(with("bucketKcal", "[0]"))).bucketKcal)
    }

    @Test
    fun theWatermarksAreCopiedInSoALaterChangeToTheCallersListChangesNothing() {
        val marks = mutableListOf(1.0, 2.0)
        val held = day.copy(bucketKcal = marks)
        val before = EnergyLedgerDayCodec.encode(held)
        marks[0] = -5.0
        marks += 9.0
        assertEquals(listOf(1.0, 2.0), held.bucketKcal)
        assertEquals(before, EnergyLedgerDayCodec.encode(held))
    }

    @Test
    fun aValueTheStoredFormCouldNotReadBackIsNotEncoded() {
        assertNull(EnergyLedgerDayCodec.encode(day.copy(bucketKcal = listOf(1.0, -0.5))))
        assertNull(EnergyLedgerDayCodec.encode(day.copy(bucketKcal = listOf(Double.NaN))))
        assertNull(EnergyLedgerDayCodec.encode(day.copy(carryKcal = Double.POSITIVE_INFINITY)))
        assertNull(EnergyLedgerDayCodec.encode(day.copy(writtenKcal = Double.NaN)))
        assertNull(EnergyLedgerDayCodec.encode(day.copy(savedKcal = Double.NEGATIVE_INFINITY)))
        assertNull(EnergyLedgerDayCodec.encode(day.copy(workoutCreditedKcal = Double.NaN)))
        assertNotNull(EnergyLedgerDayCodec.encode(day.copy(carryKcal = -2.0)))
    }
}
