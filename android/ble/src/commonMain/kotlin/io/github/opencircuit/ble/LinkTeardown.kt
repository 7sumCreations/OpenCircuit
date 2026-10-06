package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.HistoryDrainPlan

/**
 * One per torn-down connection, published on [RingLink.teardowns].
 *
 * Frames the link acknowledged to the ring are kept for [RingLink.frames]; frames still waiting
 * there when the connection is torn down are dropped and counted in [undeliveredFrames], so a
 * loss is visible, never silent.
 */
data class LinkTeardown(
    /** Why the connection was torn down. */
    val reason: HistoryDrainPlan.TeardownReason,
    /** How many received frames were never collected from [RingLink.frames]. */
    val undeliveredFrames: Int,
)
