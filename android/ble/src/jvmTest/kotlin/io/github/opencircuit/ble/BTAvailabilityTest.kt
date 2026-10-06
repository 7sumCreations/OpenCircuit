package io.github.opencircuit.ble

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure Bluetooth-availability mapping the app's connect card switches on: "Scan & connect",
 * "Turn on Bluetooth", "Allow in Settings", or the first permission prompt.
 *
 * Port of upstream `AT/BTAvailabilityTests.swift` (`AT/` = `ios/OpenCircuitTests/`, @ b1c2fdd):
 * all 6 tests, each citing its upstream line. Upstream's inputs map one to one onto the Android
 * types: `CBManagerAuthorization` `.notDetermined` / `.denied` / `.restricted` / `.allowedAlways`
 * → [BluetoothPermission] `NOT_DETERMINED` / `DENIED` / `RESTRICTED` / `GRANTED`; a `nil` central
 * state → `null`; `.poweredOn` / `.poweredOff` → [AdapterState] `ON` / `OFF`; the transient
 * `.unknown` / `.resetting` → `TURNING_ON` / `TURNING_OFF`. The expected values are upstream's.
 */
class BTAvailabilityTest {

    /** `AT/BTAvailabilityTests.swift:13`: not asked yet wins whatever the adapter says. */
    @Test
    fun notDeterminedRegardlessOfCentralState() {
        assertEquals(BTAvailability.NOT_DETERMINED, BTAvailability.of(BluetoothPermission.NOT_DETERMINED, null))
        assertEquals(BTAvailability.NOT_DETERMINED, BTAvailability.of(BluetoothPermission.NOT_DETERMINED, AdapterState.ON))
    }

    /** `AT/BTAvailabilityTests.swift:21`: denied and restricted both send the user to Settings. */
    @Test
    fun deniedAndRestrictedMapToDenied() {
        assertEquals(BTAvailability.DENIED, BTAvailability.of(BluetoothPermission.DENIED, AdapterState.ON))
        assertEquals(BTAvailability.DENIED, BTAvailability.of(BluetoothPermission.RESTRICTED, null))
    }

    /** `AT/BTAvailabilityTests.swift:30`: granted, adapter state not read yet → ready. */
    @Test
    fun authorizedNilCentralIsReady() {
        assertEquals(BTAvailability.READY, BTAvailability.of(BluetoothPermission.GRANTED, null))
    }

    /** `AT/BTAvailabilityTests.swift:36`: granted and the radio off → "Turn on Bluetooth". */
    @Test
    fun authorizedPoweredOffIsPoweredOff() {
        assertEquals(BTAvailability.POWERED_OFF, BTAvailability.of(BluetoothPermission.GRANTED, AdapterState.OFF))
    }

    /** `AT/BTAvailabilityTests.swift:42`: granted and on → ready to scan. */
    @Test
    fun authorizedPoweredOnIsReady() {
        assertEquals(BTAvailability.READY, BTAvailability.of(BluetoothPermission.GRANTED, AdapterState.ON))
    }

    /** `AT/BTAvailabilityTests.swift:49`: granted and mid-transition → ready, so the tap goes ahead. */
    @Test
    fun authorizedTransientStatesAreReady() {
        assertEquals(BTAvailability.READY, BTAvailability.of(BluetoothPermission.GRANTED, AdapterState.TURNING_ON))
        assertEquals(BTAvailability.READY, BTAvailability.of(BluetoothPermission.GRANTED, AdapterState.TURNING_OFF))
    }

    /**
     * Kotlin-only: every permission with every adapter state, `null` included (4 × 5 = 20 pairs),
     * against upstream's rule at `A/BLE/RingScanner.swift:181-197` written out as a table here.
     * Upstream's six tests leave 11 of these pairs unchecked.
     */
    @Test
    fun everyPermissionAndAdapterStatePairMapsAsUpstreamsRule() {
        val adapterStates: List<AdapterState?> = listOf(null) + AdapterState.entries
        assertEquals(listOf(null, AdapterState.ON, AdapterState.OFF, AdapterState.TURNING_ON, AdapterState.TURNING_OFF), adapterStates)
        // Columns: null, ON, OFF, TURNING_ON, TURNING_OFF.
        val expected = mapOf(
            BluetoothPermission.NOT_DETERMINED to List(5) { "NOT_DETERMINED" },
            BluetoothPermission.DENIED to List(5) { "DENIED" },
            BluetoothPermission.RESTRICTED to List(5) { "DENIED" },
            BluetoothPermission.GRANTED to listOf("READY", "READY", "POWERED_OFF", "READY", "READY"),
        )
        assertEquals(BluetoothPermission.entries.toSet(), expected.keys, "a permission value has no row")
        for ((permission, row) in expected) {
            val actual = adapterStates.map { BTAvailability.of(permission, it).name }
            assertEquals(row, actual, "$permission")
        }
    }

    /** Kotlin-only: the input and output types hold exactly the values the mapping was written for. */
    @Test
    fun theTypesHoldExactlyTheMappedValues() {
        assertEquals(listOf("NOT_DETERMINED", "DENIED", "RESTRICTED", "GRANTED"), BluetoothPermission.entries.map { it.name })
        assertEquals(listOf("ON", "OFF", "TURNING_ON", "TURNING_OFF"), AdapterState.entries.map { it.name })
        assertEquals(listOf("READY", "POWERED_OFF", "DENIED", "NOT_DETERMINED"), BTAvailability.entries.map { it.name })
    }
}
