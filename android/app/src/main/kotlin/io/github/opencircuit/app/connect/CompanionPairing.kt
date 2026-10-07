package io.github.opencircuit.app.connect

import android.app.Activity
import android.companion.CompanionDeviceManager
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.session.Disassociator
import io.github.opencircuit.ble.RememberedRing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One companion-device association the app holds: its id and the address it names (null when it names none). */
data class CompanionAssociation(val id: Int, val address: String?)

/** Android's "Allow OpenCircuit to access …?" sheet, ready for the activity to show. Opaque here. */
interface PairingSheet

/** What CDM reports while it looks for the ring (on the main thread). */
interface AssociationCallback {
    /** The ring was found; the sheet is ready. */
    fun onPending(sheet: PairingSheet)

    /** The request failed (before the sheet, or as the sheet closes). */
    fun onFailure(message: CharSequence?)
}

/**
 * Android's companion-device manager as the app uses it: an adapter over `CompanionDeviceManager`
 * on the phone, a fake in the JVM tests. Every call may throw (a missing manifest feature, a
 * revoked permission); [CompanionPairing] catches and reports.
 */
interface CompanionPort {
    /** The phone declares `android.software.companion_device_setup`. */
    val available: Boolean

    /** The app's associations. */
    fun associations(): List<CompanionAssociation>

    /** Removes the association [id]. The phone's bond, if any, is untouched. */
    fun disassociate(id: Int)

    /** The phone is bonded to [address]. */
    fun isBonded(address: String): Boolean

    /** Asks CDM for one association with exactly [ring] (its address and name, no device profile). */
    fun associate(ring: RememberedRing, callback: AssociationCallback)
}

/** Why CDM could not help, so the app connected anyway (Android's own pairing prompt follows). */
enum class FallbackReason(val words: String) {
    FEATURE_MISSING("this phone has no companion-device support"),
    REQUEST_REFUSED("Android refused the request"),
    FAILED_BEFORE_SHEET("the request failed before the sheet"),
    SHEET_NOT_SHOWN("the sheet could not be shown"),
    DISCOVERY_TIMEOUT("the sheet didn't find the ring in time"),
    INTERNAL_ERROR("the sheet reported an internal error"),
    SECURITY_ERROR("the sheet reported a security error"),
    UNKNOWN_RESULT("the sheet returned an unknown result"),
    USER_CHOSE_PLAIN_BOND("you chose to pair without the system sheet"),
}

/** How a pairing request ended. */
sealed interface PairingOutcome {
    /** Whether the ring is remembered and connected. */
    val connects: Boolean

    /** The user allowed it. */
    data object Approved : PairingOutcome {
        override val connects = true
    }

    /** The user refused (result 1) or closed the sheet (result 0): nothing is remembered or connected. */
    data class Declined(val resultCode: Int) : PairingOutcome {
        override val connects = false
    }

    /** CDM could not help: the ring is remembered and connected anyway. */
    data class Fallback(val reason: FallbackReason, val resultCode: Int? = null) : PairingOutcome {
        override val connects = true
    }
}

/**
 * Pairing through Android's companion-device manager before the first bond. No upstream
 * equivalent: iOS bonds through CoreBluetooth without one (PORTING D-250).
 *
 * An association approved less than ten minutes before the app asks to bond lets Android bond
 * without a consent prompt. So, for the ring the user chose: remove stale associations for its
 * address (a stale one found first defeats the window) unless the phone is already bonded to it,
 * then ask CDM for exactly that ring and let the activity show the sheet CDM returns. The answer
 * is the sheet's result code (on API 34–35 the failure callback carries no code):
 * allowed → [PairingOutcome.Approved]; refused or closed → [PairingOutcome.Declined]; anything
 * else, and every failure before the sheet → [PairingOutcome.Fallback]. The association id is
 * never kept: associations are looked up by address each time. Main-thread only.
 */
class CompanionPairing(private val port: CompanionPort, private val log: (String) -> Unit) : Disassociator {

    private class Request(val onOutcome: (PairingOutcome) -> Unit) {
        var sheetReady = false
    }

    private var request: Request? = null
    private val outcomeFlow = MutableStateFlow<PairingOutcome?>(null)

    /** How the last pairing request ended, for Connection details; null before the first. */
    val lastOutcome: StateFlow<PairingOutcome?> = outcomeFlow.asStateFlow()

    /**
     * Asks CDM for [ring]. [showSheet] runs when the sheet is ready (the activity shows it and
     * reports back through [onSheetResult] or [onSheetNotShown]); [onOutcome] runs once, at the end.
     * A request still waiting is abandoned.
     */
    fun begin(ring: RememberedRing, showSheet: (PairingSheet) -> Unit, onOutcome: (PairingOutcome) -> Unit) {
        val current = Request(onOutcome)
        request = current
        if (!port.available) {
            finish(current, PairingOutcome.Fallback(FallbackReason.FEATURE_MISSING))
            return
        }
        removeStale(ring.address)
        try {
            port.associate(
                ring,
                object : AssociationCallback {
                    override fun onPending(sheet: PairingSheet) {
                        if (request !== current || current.sheetReady) return
                        current.sheetReady = true
                        showSheet(sheet)
                    }

                    // After the sheet, Android calls this first and then sets the result code: wait for the code.
                    override fun onFailure(message: CharSequence?) {
                        if (request !== current || current.sheetReady) return
                        log("Companion-device request failed before the sheet")
                        finish(current, PairingOutcome.Fallback(FallbackReason.FAILED_BEFORE_SHEET))
                    }
                },
            )
        } catch (e: RuntimeException) {
            log("Companion-device request refused: ${e::class.simpleName}")
            finish(current, PairingOutcome.Fallback(FallbackReason.REQUEST_REFUSED))
        }
    }

    /** The sheet closed with [resultCode]. Ignored when no request is waiting for it. */
    fun onSheetResult(resultCode: Int) {
        val current = request ?: return
        finish(current, outcomeOf(resultCode))
    }

    /** The activity could not show the sheet. */
    fun onSheetNotShown() {
        val current = request ?: return
        log("The companion-device sheet could not be shown")
        finish(current, PairingOutcome.Fallback(FallbackReason.SHEET_NOT_SHOWN))
    }

    /**
     * The user chose to pair without the sheet: recorded as a [FallbackReason.USER_CHOSE_PLAIN_BOND]
     * fallback. [afterSheet]: they cancelled the sheet first, whose result code is kept; otherwise
     * the sheet was never asked for this ring and no code is (an earlier pairing's is not).
     */
    fun userChosePlainBond(afterSheet: Boolean) {
        request = null
        val code = if (afterSheet) (outcomeFlow.value as? PairingOutcome.Declined)?.resultCode else null
        outcomeFlow.value = PairingOutcome.Fallback(FallbackReason.USER_CHOSE_PLAIN_BOND, code)
    }

    /** Abandons a waiting request: a late answer changes nothing. */
    fun abandon() {
        request = null
    }

    /** Removes every association for [address], bonded or not (forgetting the ring). The bond is untouched. */
    override fun disassociate(address: String) {
        if (!port.available) return
        try {
            port.associations().filter { it.address != null && RingAddress.same(it.address, address) }.forEach { port.disassociate(it.id) }
        } catch (e: RuntimeException) {
            log("Could not remove the ring's companion-device association: ${e::class.simpleName}")
        }
    }

    /** Hygiene before asking: stale associations for [address] go, unless the phone is bonded to it already. */
    private fun removeStale(address: String) {
        try {
            if (port.isBonded(address)) return
            port.associations().filter { it.address != null && RingAddress.same(it.address, address) }.forEach { port.disassociate(it.id) }
        } catch (e: RuntimeException) {
            log("Could not check old companion-device associations: ${e::class.simpleName}")
        }
    }

    private fun finish(current: Request, outcome: PairingOutcome) {
        if (request !== current) return
        request = null
        outcomeFlow.value = outcome
        current.onOutcome(outcome)
    }

    private fun outcomeOf(resultCode: Int): PairingOutcome = when (resultCode) {
        Activity.RESULT_OK -> PairingOutcome.Approved
        CompanionDeviceManager.RESULT_USER_REJECTED, Activity.RESULT_CANCELED -> PairingOutcome.Declined(resultCode)
        CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT -> PairingOutcome.Fallback(FallbackReason.DISCOVERY_TIMEOUT, resultCode)
        CompanionDeviceManager.RESULT_INTERNAL_ERROR -> PairingOutcome.Fallback(FallbackReason.INTERNAL_ERROR, resultCode)
        CompanionDeviceManager.RESULT_SECURITY_ERROR -> PairingOutcome.Fallback(FallbackReason.SECURITY_ERROR, resultCode)
        else -> PairingOutcome.Fallback(FallbackReason.UNKNOWN_RESULT, resultCode)
    }
}
