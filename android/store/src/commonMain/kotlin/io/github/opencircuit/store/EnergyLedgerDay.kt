package io.github.opencircuit.store

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

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
 * - [bucketKcal]: each bucket's watermark by ordinal from [day] (`hk.activeEnergy.bucketKcal`);
 *   copied in, so a later change to the caller's list changes nothing here.
 * - [carryKcal]: debt carried from buckets whose energy fell (`hk.activeEnergy.carryKcal`).
 * - [writtenKcal]: the day's written total (`hk.activeEnergy.writtenKcal`).
 * - [savedKcal]: what was actually saved to the health store today (`hk.activeEnergy.savedKcal`).
 * - [bucketSeedDay]: the day the watermarks were seeded for (`hk.activeEnergy.bucketSeedDay`), if any.
 * - [workoutCreditedDay] / [workoutCreditedKcal]: workout energy already credited, and its day
 *   (`hk.activeEnergy.workoutCreditedDay` / `.workoutCreditedKcal`).
 *
 * Two ledgers are equal when every field is; doubles compare as `Double.equals` (so -0.0 is not 0.0).
 */
class EnergyLedgerDay(
    val day: Instant,
    val zone: ZoneId,
    val anchorEnd: Instant?,
    bucketKcal: List<Double>,
    val carryKcal: Double,
    val writtenKcal: Double,
    val savedKcal: Double,
    val bucketSeedDay: Instant?,
    val workoutCreditedDay: Instant?,
    val workoutCreditedKcal: Double,
) {
    val bucketKcal: List<Double> = Collections.unmodifiableList(ArrayList(bucketKcal))

    /** The calendar date this ledger belongs to: [day] in [zone]. */
    val localDate: LocalDate get() = day.atZone(zone).toLocalDate()

    fun copy(
        day: Instant = this.day,
        zone: ZoneId = this.zone,
        anchorEnd: Instant? = this.anchorEnd,
        bucketKcal: List<Double> = this.bucketKcal,
        carryKcal: Double = this.carryKcal,
        writtenKcal: Double = this.writtenKcal,
        savedKcal: Double = this.savedKcal,
        bucketSeedDay: Instant? = this.bucketSeedDay,
        workoutCreditedDay: Instant? = this.workoutCreditedDay,
        workoutCreditedKcal: Double = this.workoutCreditedKcal,
    ): EnergyLedgerDay = EnergyLedgerDay(
        day, zone, anchorEnd, bucketKcal, carryKcal, writtenKcal, savedKcal, bucketSeedDay, workoutCreditedDay, workoutCreditedKcal,
    )

    private fun fields(): List<Any?> = listOf(
        day, zone, anchorEnd, bucketKcal, carryKcal, writtenKcal, savedKcal, bucketSeedDay, workoutCreditedDay, workoutCreditedKcal,
    )

    override fun equals(other: Any?): Boolean = other is EnergyLedgerDay && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        "EnergyLedgerDay(day=$day, zone=$zone, anchorEnd=$anchorEnd, bucketKcal=$bucketKcal, carryKcal=$carryKcal, " +
            "writtenKcal=$writtenKcal, savedKcal=$savedKcal, bucketSeedDay=$bucketSeedDay, " +
            "workoutCreditedDay=$workoutCreditedDay, workoutCreditedKcal=$workoutCreditedKcal)"
}
