package io.github.opencircuit.ble

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.time.Duration

/** Every state [link] publishes from now on, in order (the current one first). */
internal fun TestScope.recordStates(link: RingLink): List<LinkState> {
    val seen = mutableListOf<LinkState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.state.collect { seen += it } }
    return seen
}

/** Every teardown [link] publishes from now on (this is the one collector of `teardowns`). */
internal fun TestScope.recordTeardowns(link: RingLink): List<LinkTeardown> {
    val seen = mutableListOf<LinkTeardown>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.teardowns.collect { seen += it } }
    return seen
}

/** Every frame [link] delivers from now on, as lower-case hex (this is the one collector of `frames`). */
internal fun TestScope.recordFrames(link: RingLink): List<String> {
    val seen = mutableListOf<String>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.frames.collect { seen += it.hexString() } }
    return seen
}

/**
 * Moves virtual time forward by [millis] and then runs everything due at the new time.
 * `advanceTimeBy` alone leaves the tasks due exactly at the end untouched.
 */
internal fun TestScope.advance(millis: Long) {
    advanceTimeBy(millis)
    runCurrent()
}

/** The state of a link waiting [seconds] before its [attempt]-th reconnect. */
internal fun reconnecting(attempt: Int, seconds: Long): LinkState = LinkState.Reconnecting(attempt, Duration.ofSeconds(seconds))

/** A ring that answers every operation but sends no notification unless the test makes it. */
internal fun quietRing(): FakeGatt.Script = Fixtures.acceptingRing().copy(challengeFrame = null)

internal fun ByteArray.hexString(): String =
    joinToString(" ") { b -> "0123456789abcdef".let { d -> "${d[(b.toInt() shr 4) and 0xF]}${d[b.toInt() and 0xF]}" } }
