package io.github.opencircuit.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a link did, step by step, for a "connection details" screen: the phone is not on a
 * cable, so this is where a user or a tester sees how the last connections went.
 *
 * Not part of [RingLink]: a link that keeps these offers them as well, and the app reaches them
 * with `(link as? LinkDiagnostics)`. Every link built by this module's factories does.
 *
 * Nothing in an entry identifies the ring or the wearer: no address, no MAC, no System ID and no
 * frame bytes, only step names, states, counts, times and characteristic numbers.
 */
interface LinkDiagnostics {
    /**
     * The latest entries, oldest first: at most the last 64 of the link's life, across its
     * connections. A new list is published with every entry; read the latest, no collector
     * rule applies.
     */
    val diagnostics: StateFlow<List<LinkDiagnostic>>
}

/** One thing the link did or saw. */
data class LinkDiagnostic(
    /** Milliseconds since the connection attempt it belongs to started (`connect()` or a reconnect). */
    val sinceConnectMillis: Long,
    /** What happened, in a few plain words (for example "MTU finished"). */
    val event: String,
    /** More about it (for example "granted 247"); empty when there is nothing to add. */
    val detail: String,
)

/** The last [capacity] diagnostics, oldest first, published as one list. Safe from any thread. */
internal class DiagnosticLog(private val capacity: Int = CAPACITY) {
    private val entries = MutableStateFlow<List<LinkDiagnostic>>(emptyList())

    /** The entries, oldest first. */
    val flow: StateFlow<List<LinkDiagnostic>> = entries.asStateFlow()

    /** Appends [diagnostic], dropping the oldest entry once [capacity] are kept. */
    fun add(diagnostic: LinkDiagnostic) {
        entries.update { (it + diagnostic).takeLast(capacity) }
    }

    companion object {
        /** How many entries a link keeps. */
        const val CAPACITY = 64
    }
}
