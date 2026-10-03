package io.github.opencircuit.store.codec

import io.github.opencircuit.store.EnergyLedgerDay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/**
 * The stored form of one day's active-energy write ledger:
 * `{"anchorEnd":<ms>,"bucketKcal":[…],"bucketSeedDay":<ms>,"carryKcal":…,"day":<ms>,"dayTZ":"<zone>",
 * "savedKcal":…,"workoutCreditedDay":<ms>,"workoutCreditedKcal":…,"writtenKcal":…}`, the three
 * optional dates left out when absent.
 *
 * Upstream keeps these fields as ten separate `UserDefaults` keys
 * (ios/OpenCircuit/Health/HealthKitWriter.swift:1262-1286 @ b1c2fdd); here they are one value, so a
 * day's state is written whole or not at all. Reading fails closed where `ActiveEnergyLedger.plan`
 * refuses to plan (PORTING D-79): a NaN, infinite or negative watermark, or a NaN or infinite
 * carry, workout credit, saved or written total, makes the day unreadable — never a fresh day,
 * which would pay the whole day again. A negative carry, credit or total is kept (the plan counts
 * it as zero, as upstream). An unknown zone is unreadable.
 */
object EnergyLedgerDayCodec {

    /** The stored text, or null when a value could not be read back (NaN, infinite, a negative watermark). */
    fun encode(day: EnergyLedgerDay): String? {
        if (day.bucketKcal.any { !it.isFinite() || it < 0 }) return null
        val doubles = listOf(day.carryKcal, day.writtenKcal, day.savedKcal, day.workoutCreditedKcal)
        if (doubles.any { !it.isFinite() }) return null
        return jsonObjectOf(
            "anchorEnd" to day.anchorEnd?.json(),
            "bucketKcal" to JsonArray(day.bucketKcal.map(::JsonPrimitive)),
            "bucketSeedDay" to day.bucketSeedDay?.json(),
            "carryKcal" to JsonPrimitive(day.carryKcal),
            "day" to day.day.json(),
            "dayTZ" to JsonPrimitive(day.zone.id),
            "savedKcal" to JsonPrimitive(day.savedKcal),
            "workoutCreditedDay" to day.workoutCreditedDay?.json(),
            "workoutCreditedKcal" to JsonPrimitive(day.workoutCreditedKcal),
            "writtenKcal" to JsonPrimitive(day.writtenKcal),
        ).toString()
    }

    fun decode(text: String): Decoded<EnergyLedgerDay> = readStored(text) { root ->
        val o = root.obj()
        EnergyLedgerDay(
            day = o.required("day").instant(),
            zone = ZoneId.of(o.required("dayTZ").string()),
            anchorEnd = o.optional("anchorEnd")?.instant(),
            bucketKcal = o.required("bucketKcal").array().map { e ->
                e.double().also { if (it < 0) unreadable("negative watermark $it") }
            },
            carryKcal = o.required("carryKcal").double(),
            writtenKcal = o.required("writtenKcal").double(),
            savedKcal = o.required("savedKcal").double(),
            bucketSeedDay = o.optional("bucketSeedDay")?.instant(),
            workoutCreditedDay = o.optional("workoutCreditedDay")?.instant(),
            workoutCreditedKcal = o.required("workoutCreditedKcal").double(),
        )
    }
}
