package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.HistoryDrainPlan

/**
 * One per torn-down connection, published on [RingLink.teardowns].
 *
 * Frames are kept for [RingLink.frames]; frames still waiting there when the connection is torn
 * down are dropped and counted in [undeliveredFrames], so a loss is visible, never silent.
 * History pages this connection delivered (or still held) that nobody acknowledged are counted in
 * [pagesUnacknowledged]: the ring still has them and offers them again, so they are expected
 * re-offers, not losses.
 */
data class LinkTeardown(
    /** Why the connection was torn down. */
    val reason: HistoryDrainPlan.TeardownReason,
    /** How many received frames were never collected from [RingLink.frames]. */
    val undeliveredFrames: Int,
    /** How many history pages of this connection were never acknowledged with [RingLink.acknowledge]. */
    val pagesUnacknowledged: Int = 0,
)
