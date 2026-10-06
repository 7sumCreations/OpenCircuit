package io.github.opencircuit.ble

import kotlinx.coroutines.flow.Flow

/** What a ring scan reports while it runs (see [RingScanner.scan]). */
sealed interface ScanUpdate {
    /** The rings matched so far. */
    data class Found(
        /** Every ring matched so far, in the order they were first seen. */
        val rings: List<RememberedRing>,
    ) : ScanUpdate

    /** Exactly one ring was found and no other appeared for 2.5 s: it is selected, and the scan ends. */
    data class Selected(
        /** The selected ring. */
        val ring: RememberedRing,
    ) : ScanUpdate

    /**
     * More than one ring was found and none new appeared for 2.5 s: the user picks one. The scan
     * goes on until its collection is cancelled, so a ring that appears later is found and the
     * list is offered again.
     */
    data class Choose(
        /** The rings to pick from. */
        val rings: List<RememberedRing>,
    ) : ScanUpdate

    /** No ring was found within 15 s; the scan ends. */
    data object NoRingFound : ScanUpdate

    /** Android reported a scan error, or refused to start the scan; the scan ends. */
    data class Failed(
        /**
         * The `ScanCallback` error code, or -1 when Android refused to start the scan at all
         * (Bluetooth off, no scanner, the permission missing).
         */
        val errorCode: Int,
    ) : ScanUpdate
}

/** Finds rings nearby (upstream `ios/OpenCircuit/BLE/RingScanner.swift:264-431` @ b1c2fdd). */
interface RingScanner {
    /**
     * A cold flow: each collection starts one scan, and cancelling the collection stops it.
     * A ring matches by its advertised name or by advertising the ring's data service.
     * [ScanUpdate.Selected], [ScanUpdate.NoRingFound] and [ScanUpdate.Failed] end the flow (and
     * the scan); after [ScanUpdate.Choose] it goes on until the collection is cancelled.
     */
    fun scan(): Flow<ScanUpdate>
}
