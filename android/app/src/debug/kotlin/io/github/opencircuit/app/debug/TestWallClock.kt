package io.github.opencircuit.app.debug

/**
 * The debug build's wall clock: the system clock unless an instrumented test has set a time. Once
 * set, the time keeps moving forward from that moment at the real rate, so ages and timeouts still
 * run. Every reader asks per call, so a time set after the app started is seen at once. The
 * release build has no such clock: its `VariantLinks` hands the app the system clock directly.
 */
object TestWallClock {

    private class Pinned(val millis: Long, val setAtNanos: Long)

    @Volatile
    private var pinned: Pinned? = null

    /** The wall clock in milliseconds: the pinned time plus the real time passed since, else the system's. */
    fun nowMillis(): Long {
        val p = pinned ?: return System.currentTimeMillis()
        return p.millis + (System.nanoTime() - p.setAtNanos) / NANOS_PER_MILLI
    }

    /** From now on the clock reads [millis] and moves on from there. */
    fun pinTo(millis: Long) {
        pinned = Pinned(millis, System.nanoTime())
    }

    /** Back to the system clock. */
    fun reset() {
        pinned = null
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
