package io.github.opencircuit.app.sync

/**
 * The seam the debug build's sync test action reaches the drain through (its UI and arming live
 * in the debug source set only; the release build passes [NONE], so a release sync is never cut).
 *
 * At each sync's drain start — the link is up and the first channel is about to open —
 * [HistoryDrainController] takes the armed cut once: null for none, or N to drop the link
 * (`RingLink.disconnect`, the sync then ends as for any lost link) right after the Nth page of that
 * sync was stored AND its acknowledgement written. Taking it disarms it.
 */
fun interface SyncFaultInjector {
    /** The page count to cut this sync after, or null; disarms what was armed. */
    fun takeCutAfterPages(): Int?

    companion object {
        /** Never cuts: the release build's, and every session built without one. */
        val NONE: SyncFaultInjector = SyncFaultInjector { null }
    }
}
