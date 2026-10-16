package io.github.opencircuit.app.health

import io.github.opencircuit.app.ring.RingDataPresenter
import io.github.opencircuit.health.HealthConnectPort
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthSdkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** What Health Connect says about this app: whether it can be used, and the WRITE permissions granted. */
data class HealthAccessState(
    /** Usable on this phone for this user; null until first read. */
    val available: Boolean? = null,
    /** The app's WRITE permissions the user has granted (empty when not available). */
    val granted: Set<HealthPermission> = emptySet(),
)

/**
 * Reads [HealthAccessState] from Health Connect, on [scope] (the app's process scope). The app
 * reads it at launch, each time the screen resumes (a grant or a revoke made in Health Connect's
 * own settings has no callback) and after the permission screen answers. A read that fails keeps
 * what was known and is logged.
 */
class HealthAccess(private val port: HealthConnectPort, private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private val stateFlow = MutableStateFlow(HealthAccessState())

    /** The last state read. */
    val state: StateFlow<HealthAccessState> = stateFlow.asStateFlow()

    /** Reads the state again; returns at once. */
    fun refresh(): Job = scope.launch {
        try {
            val available = port.sdkStatus() == HealthSdkStatus.AVAILABLE
            stateFlow.value = HealthAccessState(available, if (available) port.grantedWrites() else emptySet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Health Connect permissions could not be read: ${e::class.java.simpleName}")
        }
    }
}

/** What the card's button does. */
enum class HealthCardAction {
    /** Shows Health Connect's own permission screen. */
    CONNECT,

    /** Writes now. */
    WRITE_NOW,
}

/** The card as drawn. */
data class HealthCardUi(
    /** "Not connected", "Connected" or "Health Connect is not available". */
    val headline: String,
    /** The line under it, or null. */
    val body: String? = null,
    /** Label and value pairs ("Last written" · "just now"). */
    val rows: List<Pair<String, String>> = emptyList(),
    /** The button's label, or null for no button. */
    val button: String? = null,
    /** What the button does; null when it does nothing. */
    val action: HealthCardAction? = null,
    /** Whether the button can be tapped. */
    val buttonEnabled: Boolean = false,
)

/** Where the Ring screen's card reads from, and its Write now. */
class HealthCardSources(
    val access: StateFlow<HealthAccessState>,
    val flush: StateFlow<HealthFlushState>,
    val writeNow: () -> Unit,
)

/** Everything the card is drawn from. */
data class HealthCardInput(val access: HealthAccessState, val flush: HealthFlushState, val now: Instant)

/**
 * The card's states (copy from the Ring screen mockup): not available; not connected (nothing
 * granted, or not read yet) with Connect Health Connect; connected with the last write and Write
 * to Health Connect now.
 */
object HealthCardPresenter {
    fun present(input: HealthCardInput): HealthCardUi {
        val access = input.access
        if (access.available == false) {
            return HealthCardUi(
                headline = "Health Connect is not available",
                body = "Install or enable Health Connect in system settings, then come back.",
                button = CONNECT,
                action = null,
                buttonEnabled = false,
            )
        }
        if (access.granted.isEmpty()) {
            return HealthCardUi(
                headline = "Not connected",
                body = "Writes your ring data to Health Connect on this phone. Nothing leaves the phone.",
                button = CONNECT,
                action = HealthCardAction.CONNECT,
                buttonEnabled = true,
            )
        }
        val last = input.flush.last
        val rows = if (last == null) {
            listOf("Last written" to "Not yet")
        } else {
            listOf("Last written" to RingDataPresenter.ago(last.finishedAt, input.now), "Written this sync" to records(last.recordsWritten))
        }
        val running = input.flush.running
        return HealthCardUi(
            headline = "Connected",
            rows = rows,
            button = if (running) "Writing…" else "Write to Health Connect now",
            action = HealthCardAction.WRITE_NOW,
            buttonEnabled = !running,
        )
    }

    private const val CONNECT = "Connect Health Connect"

    private fun records(n: Int): String = "${String.format(Locale.ROOT, "%,d", n)} ${if (n == 1) "record" else "records"}"
}
