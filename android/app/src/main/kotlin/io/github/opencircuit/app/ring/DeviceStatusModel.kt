package io.github.opencircuit.app.ring

import io.github.opencircuit.ringkit.BatteryTTE
import io.github.opencircuit.ringkit.ChargingInference
import io.github.opencircuit.ringkit.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** What the ring last reported about itself in its `0x10` / `0x87` descriptors. */
data class DeviceStatusState(
    /** Ring battery, 1…100 %, or null before the first descriptor with a valid battery byte. */
    val batteryPercent: Int? = null,
    /** The latest descriptor's state byte `[2]` is `0x04`: the ring sits on its charger. */
    val onCharger: Boolean = false,
    /** The last few distinct battery readings rise strictly (`ChargingInference`): probably charging. */
    val chargingInferred: Boolean = false,
    /** The charging case's battery while the ring is docked in it; null when it is not. */
    val caseBattery: DeviceStatus.CaseBattery? = null,
    /** Seconds until 100 % from this charge's readings, or null while there are too few. */
    val timeToFullSeconds: Double? = null,
    /** Seconds until 0 % from the discharge readings, or null while there are too few. */
    val timeToEmptySeconds: Double? = null,
    /** How old the battery reading is once it is out of date (120 s without one); null while fresh. */
    val batteryAgeMillis: Long? = null,
    /** How many descriptors carried a valid battery: the witness that a status request was answered. */
    val batteryReadings: Int = 0,
) {
    /**
     * The battery is filling, by the state byte or by the inference: the time line counts to full,
     * not to empty. Only [onCharger] may block Measure or show the charger hint; the inference can
     * be a one-point jitter on a worn ring (PORTING D-241).
     */
    val towardFull: Boolean get() = onCharger || chargingInferred
}

/**
 * Keeps the ring's status from the descriptors the frame dispatcher hands it
 * (`ios/OpenCircuit/BLE/RingSession.swift:5030-5105` @ b1c2fdd). Decoding is `:ringkit`'s
 * [DeviceStatus]; a field that fails its decoder's guard keeps its last good value, except the
 * case battery, which clears as soon as a descriptor says the ring left the case.
 *
 * The battery readings behind the charging inference and the two time estimates are kept in
 * memory for the session only; nothing is saved. Their instants come from [monotonicMillis] (only
 * the time between readings matters, and that clock never jumps). Once 120 s pass without a
 * battery reading, [DeviceStatusState.batteryAgeMillis] says how old it is, updated each minute.
 */
class DeviceStatusModel(private val scope: CoroutineScope, private val monotonicMillis: () -> Long) {
    private val lock = Any()
    private val stateFlow = MutableStateFlow(DeviceStatusState())

    // Guarded by `lock`.
    private val trend = ArrayList<Int>()
    private var dischargeHistory: List<BatteryTTE.Sample> = emptyList()
    private var chargeHistory: List<BatteryTTE.Sample> = emptyList()
    private var staleJob: Job? = null

    /** The latest status; starts empty. */
    val state: StateFlow<DeviceStatusState> = stateFlow.asStateFlow()

    /** Takes one descriptor frame (opcode `0x10` or `0x87`); anything else changes nothing. */
    fun onDescriptor(frame: ByteArray): Unit = synchronized(lock) {
        val onCharger = DeviceStatus.isOnCharger(frame) ?: return
        var next = stateFlow.value.copy(onCharger = onCharger, caseBattery = DeviceStatus.caseBattery(frame))
        val battery = DeviceStatus.battery(frame)
        if (battery != null) {
            val now = monotonicMillis()
            val at = Instant.ofEpochMilli(now)
            // A rolling window of distinct readings (RS:5076-5081).
            if (trend.lastOrNull() != battery) {
                trend += battery
                if (trend.size > TREND_CAPACITY) trend.removeAt(0)
            }
            // Both histories are folded with the decoded charging byte, as upstream (RS:5085-5097).
            dischargeHistory = BatteryTTE.record(dischargeHistory, battery, at, charging = onCharger)
            chargeHistory = BatteryTTE.recordCharge(chargeHistory, battery, at, charging = onCharger)
            next = next.copy(
                batteryPercent = battery,
                chargingInferred = ChargingInference.inferred(trend),
                timeToFullSeconds = BatteryTTE.timeToFull(chargeHistory, at),
                timeToEmptySeconds = BatteryTTE.timeToEmpty(dischargeHistory, at),
                batteryAgeMillis = null,
                batteryReadings = next.batteryReadings + 1,
            )
            restartStaleTimer(readAt = now)
        }
        stateFlow.value = next
    }

    private fun restartStaleTimer(readAt: Long) {
        staleJob?.cancel()
        staleJob = scope.launch {
            delay(STALE_AFTER_MILLIS)
            while (true) {
                synchronized(lock) {
                    // `synchronized` is no cancellation point: a timer a fresh reading cancelled while
                    // this waited for the lock must not age that reading. Cancels happen under the lock.
                    if (!isActive) return@launch
                    stateFlow.value = stateFlow.value.copy(batteryAgeMillis = monotonicMillis() - readAt)
                }
                delay(AGE_STEP_MILLIS)
            }
        }
    }

    private companion object {
        /** No battery reading for this long and it is out of date (`batteryStaleAfter`, RS:187). */
        const val STALE_AFTER_MILLIS = 120_000L

        /** The age shown is in minutes, so it is recounted once a minute. */
        const val AGE_STEP_MILLIS = 60_000L

        /** Distinct battery readings kept for the charging inference (RS:213). */
        const val TREND_CAPACITY = 4
    }
}
