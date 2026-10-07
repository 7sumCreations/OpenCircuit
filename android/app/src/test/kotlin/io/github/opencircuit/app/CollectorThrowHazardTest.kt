package io.github.opencircuit.app

import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.RememberedRing
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Measures what happens to the link's frame flow when the code collecting it throws without a
 * guard. The link's [io.github.opencircuit.ble.RingLink.frames] takes exactly one collector at a
 * time: a second collection while one runs throws, and a collector that stops leaves the frames
 * it did not take for the next collector.
 *
 * Measured: the throw ends the running collection, and the frame behind the failing one is not
 * lost but waits, undelivered, for a new collector. The session controller starts its collection
 * once and never starts another, so after such a throw every later frame of the session would
 * wait for a collector that never comes. That is why the frame dispatcher catches a handler's
 * exception per frame (PORTING.md D-231).
 */
class CollectorThrowHazardTest {

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "Test ring")

    @Test
    fun anUnguardedThrowEndsTheRunningCollectionAndTheFramesBehindItWaitUndelivered() = runTest {
        val link = FakeRingLink(ring)
        link.emitFrame(TestFrames.wornDescriptor)
        link.emitFrame(TestFrames.chargingDescriptor)
        val delivered = mutableListOf<ByteArray>()

        val thrown = assertFailsWith<IllegalStateException> {
            link.frames.collect {
                delivered += it
                throw IllegalStateException("a handler bug")
            }
        }
        link.emitFrame(TestFrames.liveHeartRate)
        runCurrent()

        assertEquals("a handler bug", thrown.message)
        assertEquals(1, delivered.size, "the frame queued behind the failing one was not delivered")
        assertContentEquals(TestFrames.wornDescriptor, delivered[0])
        // Nothing is collecting now: the frames wait in the link, so a fresh collector gets
        // them, in order. Only a collector that someone starts again would ever see them.
        val waiting = link.frames.take(2).toList()
        assertContentEquals(TestFrames.chargingDescriptor, waiting[0])
        assertContentEquals(TestFrames.liveHeartRate, waiting[1])
    }

    @Test
    fun aSecondCollectionWhileOneRunsStillFails() = runTest {
        val link = FakeRingLink(ring)
        backgroundScope.launch { link.frames.collect() }
        runCurrent()

        val second = assertFailsWith<IllegalStateException> { link.frames.collect() }

        assertEquals(true, second.message.orEmpty().contains("one may collect at a time"), "message: ${second.message}")
    }
}
