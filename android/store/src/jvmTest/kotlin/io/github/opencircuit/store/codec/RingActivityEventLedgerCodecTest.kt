package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.RingActivityEventLedger
import io.github.opencircuit.ringkit.RingEvent
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The activity-event ledger's stored form beyond upstream's round trip.
 *
 * Kotlin-only. Upstream's ledger is synthesized Codable (`RingEventLog.swift` @ b1c2fdd): both
 * `events` and `overflow` are required, `RingEvent.type` / `value` are `UInt8` and `cursor` is
 * `UInt32`, and one bad event fails the whole ledger — measured on Swift 6.3.2. `Overflow.hidden`
 * is a 64-bit Swift `Int` held as Kotlin `Int`, so a count past 32 bits makes the ledger
 * unreadable. What an unreadable ledger costs (it loads empty) is the caller's rule.
 */
class RingActivityEventLedgerCodecTest {

    private val seen = Instant.ofEpochMilli(1_759_000_000_000L)

    private fun ledger(): RingActivityEventLedger = RingActivityEventLedger(
        events = linkedMapOf(
            "B" to listOf(RingEvent(0x10, 0x0f, 212_664_320L), RingEvent(0x10, 0x0a, 212_666_123L)),
            "A" to listOf(RingEvent(0x10, 0x0f, 0xFFFF_FFFFL)),
        ),
        overflow = linkedMapOf("B" to RingActivityEventLedger.Overflow(hidden = 15, seenAt = seen)),
    )

    @Test
    fun eventsAndOverflowForSeveralRingsRoundTrip() {
        assertEquals(ledger(), readable(RingActivityEventLedgerCodec.decode(RingActivityEventLedgerCodec.encode(ledger()))))
    }

    @Test
    fun theStoredFormIsTheSameWhateverOrderTheRingsWereAddedIn() {
        val reversed = RingActivityEventLedger(
            events = linkedMapOf("A" to ledger().events.getValue("A"), "B" to ledger().events.getValue("B")),
            overflow = ledger().overflow,
        )
        assertEquals(RingActivityEventLedgerCodec.encode(ledger()), RingActivityEventLedgerCodec.encode(reversed))
        assertEquals(
            """{"events":{"A":[{"cursor":4294967295,"type":16,"value":15}],""" +
                """"B":[{"cursor":212664320,"type":16,"value":15},{"cursor":212666123,"type":16,"value":10}]},""" +
                """"overflow":{"B":{"hidden":15,"seenAt":1759000000000}}}""",
            RingActivityEventLedgerCodec.encode(ledger()),
        )
    }

    @Test
    fun anEmptyLedgerWritesBothKeys() {
        assertEquals("""{"events":{},"overflow":{}}""", RingActivityEventLedgerCodec.encode(RingActivityEventLedger()))
        assertEquals(RingActivityEventLedger(), readable(RingActivityEventLedgerCodec.decode("""{"overflow":{},"events":{}}""")))
    }

    @Test
    fun aMissingKeyGarbageOrAValueOfTheWrongTypeIsUnreadable() {
        val valid = RingActivityEventLedgerCodec.encode(ledger())
        for (raw in listOf(
            """{"events":{"A":[{"cursor":1,"type":16,"value":15}]}}""",
            """{"overflow":{}}""",
            "\u0000\u0001\u0002",
            "\"" + valid.replace("\"", "\\\"") + "\"",
            "[]",
            """{"events":[],"overflow":{}}""",
            """{"events":{"A":[{"cursor":1,"type":16}]},"overflow":{}}""",
        )) {
            assertUnreadable(RingActivityEventLedgerCodec.decode(raw), raw)
        }
    }

    @Test
    fun aValuePastItsSwiftRangeMakesTheLedgerUnreadable() {
        for (raw in listOf(
            """{"events":{},"overflow":{"A":{"hidden":2147483648,"seenAt":0}}}""",
            """{"events":{"A":[{"cursor":4294967296,"type":16,"value":15}]},"overflow":{}}""",
            """{"events":{"A":[{"cursor":1,"type":256,"value":15}]},"overflow":{}}""",
            """{"events":{"A":[{"cursor":1,"type":16,"value":-1}]},"overflow":{}}""",
            """{"events":{"A":[{"cursor":1,"type":16,"value":15},{"cursor":-1,"type":16,"value":10}]},"overflow":{}}""",
        )) {
            assertUnreadable(RingActivityEventLedgerCodec.decode(raw), raw)
        }
    }
}
