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
 *
 * [replyTo], when set, plays the ring's notification answer to a write: given the write's plain
 * hex it returns the hex of the frame the ring sends back (or null for none), which is queued on
 * the fake's frames right after the write goes out — as the ring answers `06 xx 00` with
 * `86 <status> <xor>` (PROTOCOL.md §4).
 */
internal class TimedRingLink(val fake: FakeRingLink, private val now: () -> Long) : RingLink by fake {
    private val log = CopyOnWriteArrayList<TimedWrite>()

    /** The ring's frame answering a write, by the write's hex; null sends nothing. */
    @Volatile
    var replyTo: (String) -> String? = { null }

    /** Every write so far, oldest first. */
    val writes: List<TimedWrite> get() = log.toList()

    /** The times of the writes of [hex]. */
    fun timesOf(hex: String): List<Long> = writes.filter { it.hex == hex }.map { it.atMillis }

    /** Forgets the writes so far, so a test judges only what follows. */
    fun clear() = log.clear()

    override suspend fun send(command: ByteArray): SendResult {
        val written = command.toPlainHex()
        log += TimedWrite(now(), written)
        val result = fake.send(command)
        if (result == SendResult.Sent) replyTo(written)?.let { fake.emitFrame(hex(it)) }
        return result
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

    /** Back to idle, out of any `06` mode (PROTOCOL.md §4 table: `06 00 00` → `86 00 86`). */
    const val MODE_EXIT = "060000"
}

/**
 * The ring's answers to a `06 xx 00` mode write, as raw frames `86 <status> <xor>`. Accepted is
 * `86 00 86` (PROTOCOL.md §4). The refusal is `86 fd 7b`, the "not ready" reject upstream saw on a
 * real ring (upstream e3b1330's message and `ios/OpenCircuit/BLE/RingSession.swift:5299-5311`
 * @ b1c2fdd); `86 fc 7a` is "already in that mode", which upstream treats as accepted (same lines).
 */
internal object ModeReplies {
    const val ACCEPTED = "860086"
    const val REFUSED = "86fd7b"
    const val ALREADY = "86fc7a"
}
