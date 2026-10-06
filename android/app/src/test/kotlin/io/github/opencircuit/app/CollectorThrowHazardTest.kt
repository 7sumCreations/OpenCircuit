package io.github.opencircuit.app

import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.RememberedRing
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Measures what happens to the link's frame flow when the code collecting it throws without a
 * guard. The link's [io.github.opencircuit.ble.RingLink.frames] takes exactly one collection for
 * its lifetime, so the answer decides whether the app's frame handlers must be contained.
 *
 * Measured: the throw ends the collection, and the flow can never be collected again, so every
 * later frame of the session would reach no handler. That is why the frame dispatcher catches a
 * handler's exception per frame (PORTING.md D-231).
 */
class CollectorThrowHazardTest {

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "Test ring")

    @Test
    fun anUnguardedThrowEndsTheOnlyFrameCollectionAndItCannotBeRestarted() = runTest {
        val link = FakeRingLink(ring)
        link.emitFrame(TestFrames.wornDescriptor)
        link.emitFrame(TestFrames.chargingDescriptor)
        var delivered = 0

        val thrown = assertFailsWith<IllegalStateException> {
            link.frames.collect {
                delivered++
                throw IllegalStateException("a handler bug")
            }
        }

        assertEquals("a handler bug", thrown.message)
        assertEquals(1, delivered, "the frame queued behind the failing one was never delivered")
        val again = assertFailsWith<IllegalStateException> { link.frames.collect() }
        assertTrue(again.message.orEmpty().contains("just once"), "second collection: ${again.message}")
    }
}
