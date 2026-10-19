package io.github.opencircuit.app.health

import io.github.opencircuit.app.ring.RingDataPresenter
import io.github.opencircuit.health.HealthConnectPort
import io.github.opencircuit.health.HealthFeature
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthSdkStatus
import io.github.opencircuit.health.HealthShare
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.ShareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * What Health Connect and the store say about this app's access: whether Health Connect can be
 * used, the WRITE permissions granted, whether it offers skin temperature, whether its permission
 * screen was ever shown, and the stored write-failure map.
 */
data class HealthAccessState(
    /** Usable on this phone for this user; null until first read. */
    val available: Boolean? = null,
    /** The app's WRITE permissions the user has granted (empty when not available). */
    val granted: Set<HealthPermission> = emptySet(),
    /** Whether this phone's Health Connect offers skin temperature; null until read. */
    val skinTemperatureSupported: Boolean? = null,
    /** Whether Health Connect's permission screen was shown and answered, ever. */
    val asked: Boolean = false,
    /** The stored write-failure map, as read with the rest (a write's own result replaces it on the card). */
    val failures: Map<RecordType, Instant> = emptyMap(),
) {
    /** What the app asks for: everything, less skin temperature once the phone is known not to offer it. */
    val requested: Set<HealthPermission> get() = HealthShare.requested(skinTemperatureSupported != false)
}

/** What [HealthAccess] keeps in the app's store. */
interface HealthAccessMemory {
    /** Whether the permission screen was ever shown and answered. */
    suspend fun asked(): Boolean

    /** Records that the permission screen was shown and answered. */
    suspend fun markAsked()

    /** The stored write-failure map. */
    suspend fun failures(): Map<RecordType, Instant>
}

/**
 * Reads [HealthAccessState] from Health Connect and the store, on [scope] (the app's process
 * scope). The app reads it at launch, each time the screen resumes (a grant or a revoke made in
 * Health Connect's own settings has no callback) and after the permission screen answers. A read
 * that fails keeps what was known and is logged.
 */
class HealthAccess(
    private val port: HealthConnectPort,
    private val memory: HealthAccessMemory,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    private val stateFlow = MutableStateFlow(HealthAccessState())

    /**
     * One read at a time: a read started before the permission screen's answer was saved can
     * never finish after (and over) the read that follows the answer.
     */
    private val reading = Mutex()

    /** The last state read. */
    val state: StateFlow<HealthAccessState> = stateFlow.asStateFlow()

    /** The permissions to ask for now, as platform strings come from these. */
    fun requested(): Set<HealthPermission> = stateFlow.value.requested

    /** Reads the state again; returns at once. */
    fun refresh(): Job = scope.launch { read() }

    /** The permission screen answered: remembers that it was shown, then reads the grants again. Returns at once. */
    fun permissionScreenAnswered(): Job = scope.launch {
        try {
            memory.markAsked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Health Connect: could not remember that its permission screen was shown: ${e::class.java.simpleName}")
        }
        read()
    }

    private suspend fun read() = reading.withLock {
        try {
            val available = port.sdkStatus() == HealthSdkStatus.AVAILABLE
            stateFlow.value = HealthAccessState(
                available = available,
                granted = if (available) port.grantedWrites() else emptySet(),
                skinTemperatureSupported = if (available) port.featureAvailable(HealthFeature.SKIN_TEMPERATURE) else null,
                asked = memory.asked(),
                failures = memory.failures(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Health Connect permissions could not be read: ${e::class.java.simpleName}")
        }
    }
}

/** What the card's buttons do. */
enum class HealthCardAction {
    /** Shows Health Connect's own permission screen. */
    CONNECT,

    /** Lets the user change what is allowed (Health Connect's permission screen for now). */
    MANAGE,

    /** Writes now. */
    WRITE_NOW,
}

/** The card as drawn. */
data class HealthCardUi(
    /** "Not connected", "Connected" or "Health Connect is not available". */
    val headline: String,
    /** The line under it, or null. */
    val body: String? = null,
    /** The amber warning (types not allowed, writes that failed), or null. */
    val warning: String? = null,
    /** Label and value pairs ("Last written" · "just now"). */
    val rows: List<Pair<String, String>> = emptyList(),
    /** Plain lines under the rows ("Catching up…", the live-only line); never amber. */
    val notes: List<String> = emptyList(),
    /** The button's label, or null for no button. */
    val button: String? = null,
    /** What the button does; null when it does nothing. */
    val action: HealthCardAction? = null,
    /** Whether the button can be tapped. */
    val buttonEnabled: Boolean = false,
    /** A second button's label ("Change in Health Connect"), or null. */
    val secondButton: String? = null,
    /** What the second button does. */
    val secondAction: HealthCardAction? = null,
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
 * The card's states (copy from the Ring screen mockup): not available; not connected (nothing of
 * what the app asks for granted, or not read yet) with Connect Health Connect, or — once the
 * permission screen was answered — Open Health Connect settings; connected with the
 * last write and Write to Health Connect now — amber when some types are not allowed ("Not
 * allowed: …", with Change in Health Connect) or a type's writes failed ("Could not write …",
 * with Try again). Skin temperature on a phone without it, "catching up" after Health Connect's
 * rate limit stopped a write, and the live-only line are plain lines.
 */
object HealthCardPresenter {
    fun present(input: HealthCardInput): HealthCardUi {
        val access = input.access
        if (access.available == false) return notAvailable()
        val share = HealthShare.resolve(access.granted, access.requested)
        if (share == ShareState.Unauthorized) return notConnected(access)
        return connected(input, access, share)
    }

    /** Health Connect is not on this phone at all: there is nothing to connect to, and the card says so. */
    private fun notAvailable(): HealthCardUi = HealthCardUi(
        headline = "Health Connect is not available",
        body = "Install or enable Health Connect in system settings, then come back.",
        button = CONNECT,
        action = null,
        buttonEnabled = false,
    )

    /**
     * Nothing of what the app asks for is granted, or none of it has been read yet. Once the
     * permission screen was answered with nothing allowed, asking again may be refused without
     * showing anything (the user chose "don't ask again"): Health Connect's own settings for this
     * app are the way back.
     */
    private fun notConnected(access: HealthAccessState): HealthCardUi =
        if (access.asked) {
            HealthCardUi(
                headline = "Not connected",
                body = "Nothing is allowed yet. Choose what OpenCircuit may write in Health Connect's settings.",
                button = "Open Health Connect settings",
                action = HealthCardAction.MANAGE,
                buttonEnabled = true,
            )
        } else {
            HealthCardUi(
                headline = "Not connected",
                body = "Writes your ring data to Health Connect on this phone. Nothing leaves the phone.",
                button = CONNECT,
                action = HealthCardAction.CONNECT,
                buttonEnabled = true,
            )
        }

    /**
     * Connected: the last write's rows, what the last write could not write and what is not allowed,
     * the "catching up" and live-only lines, and the Write now button (Writing… while a write runs,
     * Try again after a failure, Change in Health Connect beside a partial grant).
     */
    private fun connected(input: HealthCardInput, access: HealthAccessState, share: ShareState): HealthCardUi {
        val last = input.flush.last
        val rows = mutableListOf<Pair<String, String>>()
        if (last == null) {
            rows += "Last written" to "Not yet"
        } else {
            rows += "Last written" to RingDataPresenter.ago(last.finishedAt, input.now)
            rows += "Written this sync" to records(last.recordsWritten)
        }
        if (access.skinTemperatureSupported == false) rows += "Skin temperature" to "Not supported on this phone"

        // The last write's map is the newest; before this launch's first write, the stored one.
        val failed = (last?.failureMap ?: access.failures).keys
        // A write that stopped before it could report names no type.
        val stopped = input.flush.error != null
        val warnings = buildList {
            if (stopped) add("Could not write to Health Connect. It will be retried on the next sync.")
            if (failed.isNotEmpty()) add("Could not write ${HealthShare.attentionNames(ShareState.Authorized, failed).joinToString()}. It will be retried on the next sync.")
            if (share is ShareState.Partial) add("Not allowed: ${HealthShare.names(share.denied).joinToString()}. The rest is being written.")
        }
        val running = input.flush.running
        // Health Connect's rate limit stopped the last write: not a failure, nothing is lost.
        val notes = listOfNotNull(CATCHING_UP.takeIf { last?.catchingUp == true }, LIVE_ONLY)
        return HealthCardUi(
            headline = "Connected",
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "),
            rows = rows,
            notes = notes,
            button = when {
                running -> "Writing…"
                failed.isNotEmpty() || stopped -> "Try again"
                else -> "Write to Health Connect now"
            },
            action = HealthCardAction.WRITE_NOW,
            buttonEnabled = !running,
            secondButton = if (share is ShareState.Partial) "Change in Health Connect" else null,
            secondAction = if (share is ShareState.Partial) HealthCardAction.MANAGE else null,
        )
    }

    private const val CONNECT = "Connect Health Connect"
    private const val CATCHING_UP = "Catching up: Health Connect paused the writes. The rest is written on the next sync."

    /** Steps and skin temperature come from the ring's live readings, stored only while it is connected. */
    private const val LIVE_ONLY = "Steps and skin temperature are recorded only while the ring is connected."

    private fun records(n: Int): String = "${String.format(Locale.ROOT, "%,d", n)} ${if (n == 1) "record" else "records"}"
}
