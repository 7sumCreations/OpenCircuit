package io.github.opencircuit.app.health

import io.github.opencircuit.app.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Today's one-time notice that Health Connect export is now off by default, shown to installs that
 * used Health Connect under 0.3.0 (when writing was not a switch) until the user dismisses it,
 * opens Settings from it, or uses the switch.
 *
 * "Used Health Connect" is [usedBefore]: the store's "permission screen shown" flag, which 0.3.0
 * set when its permission screen answered — a store read, never a Health Connect call. A fresh
 * install cannot have it without first turning the switch ON, and using the switch dismisses the
 * notice, so a fresh install never sees it; and with writing saved ON it is never shown (it would
 * be false). Dismissed is read synchronously from [prefs] first: a
 * dismissed notice never appears, not even for a frame, and the store is not read again.
 */
class HealthUpgradeNotice(
    private val prefs: AppPrefs,
    usedBefore: suspend () -> Boolean,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    /**
     * The store answer lands on a background thread and Dismiss on the main one: both decide and
     * publish under this lock, so a dismissal can never fall between the answer's check and its
     * write (which would bring a dismissed notice back).
     */
    private val lock = Any()

    /** Dismissed in this run, saved or not; checked again when the store answers. Guarded by [lock]. */
    private var dismissed = prefs.healthUpgradeNoticeDismissed

    private val shown = MutableStateFlow(false)

    /** Today draws the notice while this is true. */
    val visible: StateFlow<Boolean> = shown.asStateFlow()

    init {
        // Writing already ON: "export is now off" would be false (an earlier dismissal may not have been saved).
        if (!dismissed && !prefs.healthWriteEnabled) {
            scope.launch {
                val used = try {
                    usedBefore()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A courtesy, never a blocker: without the answer there is no notice.
                    log("Could not read whether Health Connect was used before: ${e::class.java.simpleName}")
                    false
                }
                // A dismissal made while the store was being read wins over its answer.
                synchronized(lock) { if (used && !dismissed) shown.value = true }
            }
        }
    }

    /** Hides the notice for good; for this run even when it could not be saved. */
    fun dismiss() {
        synchronized(lock) {
            dismissed = true
            shown.value = false
        }
        if (!prefs.healthUpgradeNoticeDismissed && !prefs.setHealthUpgradeNoticeDismissed()) {
            log("Could not save that the Health Connect notice was dismissed; it may show again next launch")
        }
    }
}
