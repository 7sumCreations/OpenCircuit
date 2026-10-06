package io.github.opencircuit.app

import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.SendResult
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.CopyOnWriteArrayList

/** One write the app made, with the virtual time it was made at and its bytes as plain hex. */
internal data class TimedWrite(val atMillis: Long, val hex: String)

/**
 * The scripted fake link, with every write recorded together with the virtual time it was made
 * at (the fake itself records bytes only). Answers come from the fake ([FakeRingLink.answerSendsWith]).
 */
internal class TimedRingLink(val fake: FakeRingLink, private val now: () -> Long) : RingLink by fake {
    private val log = CopyOnWriteArrayList<TimedWrite>()

    /** Every write so far, oldest first. */
    val writes: List<TimedWrite> get() = log.toList()

    /** The times of the writes of [hex]. */
    fun timesOf(hex: String): List<Long> = writes.filter { it.hex == hex }.map { it.atMillis }

    /** Forgets the writes so far, so a test judges only what follows. */
    fun clear() = log.clear()

    override suspend fun send(command: ByteArray): SendResult {
        log += TimedWrite(now(), command.toPlainHex())
        return fake.send(command)
    }
}

/** A remembered ring with a placeholder address that names no real device. */
internal val testRing = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "Test ring")

/** A [TimedRingLink] over a fresh fake, on this test's virtual clock. */
internal fun TestScope.timedLink(): TimedRingLink = TimedRingLink(FakeRingLink(testRing)) { testScheduler.currentTime }

/**
 * Runs virtual time up to exactly [atMillis] and everything due then. `advanceTimeBy` alone stops
 * just short of work due at the end of the span, so `runCurrent` finishes the instant.
 */
internal fun TestScope.advanceTo(atMillis: Long) {
    val now = testScheduler.currentTime
    require(atMillis >= now) { "cannot go back from $now to $atMillis" }
    testScheduler.advanceTimeBy(atMillis - now)
    testScheduler.runCurrent()
}

/** The live-measure commands as typed from PROTOCOL.md §3, never read back from production code. */
internal object Wire {
    const val STATUS_QUERY = "d00000"
    const val HR_MODE = "060100"
    const val SPO2_MODE = "060200"
    const val FETCH = "070000"
    const val POLL = "950000"
}
