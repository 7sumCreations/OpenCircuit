package io.github.opencircuit.store

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The active-energy write ledger of one day, stored as one value so it commits whole.
 *
 * Upstream keeps these as ten separate `UserDefaults` keys, `hk.activeEnergy.*`
 * (ios/OpenCircuit/Health/HealthKitWriter.swift:1262-1286 @ b1c2fdd), each written on its own; here
 * a day's state is one stored value, the unit a re-plan hands back to `ActiveEnergyLedger.plan`.
 *
 * - [day]: start of the day the ledger counts (`hk.activeEnergy.day`), in [zone].
 * - [zone]: the zone [day] was computed in (`hk.activeEnergy.dayTZ`).
 * - [anchorEnd]: end of the last window written (`hk.activeEnergy.anchorEnd`), if any.
 * - [bucketKcal]: each bucket's watermark by ordinal from [day] (`hk.activeEnergy.bucketKcal`).
 * - [carryKcal]: debt carried from buckets whose energy fell (`hk.activeEnergy.carryKcal`).
 * - [writtenKcal]: the day's written total (`hk.activeEnergy.writtenKcal`).
 * - [savedKcal]: what was actually saved to the health store today (`hk.activeEnergy.savedKcal`).
 * - [bucketSeedDay]: the day the watermarks were seeded for (`hk.activeEnergy.bucketSeedDay`), if any.
 * - [workoutCreditedDay] / [workoutCreditedKcal]: workout energy already credited, and its day
 *   (`hk.activeEnergy.workoutCreditedDay` / `.workoutCreditedKcal`).
 */
data class EnergyLedgerDay(
    val day: Instant,
    val zone: ZoneId,
    val anchorEnd: Instant?,
    val bucketKcal: List<Double>,
    val carryKcal: Double,
    val writtenKcal: Double,
    val savedKcal: Double,
    val bucketSeedDay: Instant?,
    val workoutCreditedDay: Instant?,
    val workoutCreditedKcal: Double,
) {
    /** The calendar date this ledger belongs to: [day] in [zone]. */
    val localDate: LocalDate get() = day.atZone(zone).toLocalDate()
}
