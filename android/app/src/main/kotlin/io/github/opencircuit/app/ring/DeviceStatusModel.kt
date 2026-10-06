package io.github.opencircuit.app.ring

import io.github.opencircuit.ringkit.DeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the ring last reported about itself in a `0x10` / `0x87` descriptor. */
data class DeviceStatusState(
    /** Ring battery, 1…100 %, or null before the first descriptor with a valid battery byte. */
    val batteryPercent: Int? = null,
)

/**
 * Keeps the ring's status from the descriptors the frame dispatcher hands it. Decoding is
 * `:ringkit`'s [DeviceStatus]; a field that fails its decoder's guard keeps the last good value.
 */
class DeviceStatusModel {
    private val stateFlow = MutableStateFlow(DeviceStatusState())

    /** The latest status; starts empty. */
    val state: StateFlow<DeviceStatusState> = stateFlow.asStateFlow()

    /** Takes one descriptor frame (opcode `0x10` or `0x87`); anything else changes nothing. */
    fun onDescriptor(frame: ByteArray) {
        val battery = DeviceStatus.battery(frame) ?: return
        stateFlow.update { it.copy(batteryPercent = battery) }
    }
}
