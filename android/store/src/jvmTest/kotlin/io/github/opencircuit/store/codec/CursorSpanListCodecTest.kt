package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.CursorSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stored form of a detected-workout span ledger (the resolved and the notified spans).
 *
 * Kotlin-only. Upstream's `CursorSpan` is synthesized Codable over two `UInt32`s
 * (ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/AutomaticWorkoutDetection.swift:72 @ b1c2fdd),
 * read with `try? JSONDecoder().decode([CursorSpan].self, …)` (ios/OpenCircuit/BLE/RingSession.swift:3168),
 * so one bad span makes the whole ledger unreadable and a value outside 0…0xFFFFFFFF is unreadable.
 */
class CursorSpanListCodecTest {

    @Test
    fun theStoredFormIsEachSpansEndsInOrder() {
        assertEquals(
            """[{"end":20,"start":10},{"end":4294967295,"start":0}]""",
            CursorSpanListCodec.encode(listOf(CursorSpan(10, 20), CursorSpan(0, 0xFFFF_FFFFL))),
        )
    }

    @Test
    fun spansRoundTripInTheirOrder() {
        val spans = listOf(CursorSpan(500), CursorSpan(10, 20), CursorSpan(0, 0xFFFF_FFFFL))
        assertEquals(spans, readable(CursorSpanListCodec.decode(CursorSpanListCodec.encode(spans))))
        assertEquals(emptyList(), readable(CursorSpanListCodec.decode(CursorSpanListCodec.encode(emptyList()))))
    }

    @Test
    fun anEndBeforeItsStartIsKeptAsStored() {
        assertEquals(listOf(CursorSpan(9, 5)), readable(CursorSpanListCodec.decode("""[{"end":5,"start":9}]""")))
    }

    @Test
    fun oneBadSpanMakesTheWholeLedgerUnreadable() {
        for (raw in listOf(
            """[{"end":20,"start":10},{"start":3}]""",
            """[{"end":20}]""",
            """[{"end":4294967296,"start":0}]""",
            """[{"end":5,"start":-1}]""",
            """[{"end":5.5,"start":1}]""",
            """[{"end":"5","start":1}]""",
            """[{"end":null,"start":1}]""",
            """{"end":5,"start":1}""",
            """[5]""",
        )) {
            assertUnreadable(CursorSpanListCodec.decode(raw), raw)
        }
    }
}
