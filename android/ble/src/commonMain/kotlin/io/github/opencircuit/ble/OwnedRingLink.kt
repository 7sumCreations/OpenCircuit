package io.github.opencircuit.ble

import kotlinx.coroutines.Job

/**
 * A link that owns the scope its event loop runs in, so its holder can end it: the Android
 * factory's link, whose Bluetooth receivers stay registered until that scope ends.
 *
 * [close] ends the link for good: the connection is closed, every waiting `send` answers
 * `LINK_LOST`, the state shows `Idle`, whatever was registered for the link's life is released
 * (the scope's completion handlers run), and `connect()` does nothing from then on. Safe to call
 * more than once. Not part of [RingLink]; the app reaches it with `(link as? AutoCloseable)`.
 */
internal class OwnedRingLink(
    private val core: LinkCore,
    /** The job of the scope [core] runs in; cancelled by [close]. */
    private val owner: Job,
) : RingLink by core, LinkDiagnostics by core, AutoCloseable {

    override fun close() {
        core.disconnect()
        owner.cancel()
    }
}
