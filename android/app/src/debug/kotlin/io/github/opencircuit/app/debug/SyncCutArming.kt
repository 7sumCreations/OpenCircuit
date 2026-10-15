package io.github.opencircuit.app.debug

import io.github.opencircuit.app.sync.SyncFaultInjector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Debug build only: the "Cut the next sync after N pages" test action's state. Arming it makes the
 * next sync drop the link right after its Nth page is stored and acknowledged (the drain takes the
 * armed count at its start, [takeCutAfterPages], which disarms it), so the next normal sync's log
 * shows whether the ring resumed where the acknowledged pages left it (continuity: contiguous /
 * overlap, or a gap). The release build has no such class: its sessions get [SyncFaultInjector.NONE].
 */
class SyncCutArming : SyncFaultInjector {
    private val armedFlow = MutableStateFlow<Int?>(null)

    /** The page count the next sync is cut after, or null when not armed. */
    val armed: StateFlow<Int?> = armedFlow.asStateFlow()

    /** Arms the cut after [pages] pages (at least 1). */
    fun arm(pages: Int) {
        require(pages >= 1) { "a cut needs at least one page" }
        armedFlow.value = pages
    }

    /** Disarms it without a sync. */
    fun disarm() {
        armedFlow.value = null
    }

    override fun takeCutAfterPages(): Int? = armedFlow.getAndUpdate { null }

    companion object {
        /** The page counts the Connection details action offers. */
        val PRESETS: List<Int> = listOf(5, 20, 50)
    }
}
