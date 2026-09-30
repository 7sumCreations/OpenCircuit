package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `0x4c` page-corruption discriminator. The ack for a `0x4c` page is unconditional and
 * advances the ring's shared resume pointer, so an undecodable page is lost history. An empty
 * record list is NOT a corruption test: `BulkSleep.recordsFromPage` returns nothing both for a
 * corrupt page and for a valid page carrying no whole record; `Frame.parse` separates the two.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/CorruptPage4CDiscriminatorTests.swift
 * (@ b1c2fdd) — all 4 tests. `realPage` is the same real, XOR-valid FR02.018 frame `BulkSleepTest`
 * uses. Synthetic pages are sealed by [testXor] here, never by the production `Frame.xorTrailer`.
 */
class CorruptPage4CDiscriminatorTest {

    // :25-29
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    /** Test-only XOR of every byte (the response trailer rule, ../docs/PROTOCOL.md §3). */
    private fun testXor(b: ByteArray): Int = b.fold(0) { acc, x -> acc xor (x.toInt() and 0xFF) }

    /** :32 — append the correct XOR trailer to a body, so the frame is genuinely valid. */
    private fun sealed(body: ByteArray): ByteArray = body + bytes(testXor(body))

    /** :35-37 — exactly the predicate the session's frame handler applies to a `0x4c` page. */
    private fun isCorrupt(page: ByteArray): Boolean =
        BulkSleep.recordsFromPage(page).isEmpty() && Frame.parse(page) == null

    @Test
    fun emptyRecordsIsNotEvidenceOfCorruption() { // :41-58
        // `4c 00 26` + trailer: a structurally sound page whose body carries no record at all.
        val recordless = sealed(bytes(0x4C, 0x00, 0x26))
        val corrupted = hex(realPage)
        corrupted[corrupted.size - 1] = (corrupted.u8(corrupted.size - 1) xor 0xFF).toByte() // break the trailer

        // Both decode to nothing — so `isEmpty` alone cannot tell a lost page from an empty one.
        assertTrue(BulkSleep.recordsFromPage(recordless).isEmpty())
        assertTrue(BulkSleep.recordsFromPage(corrupted).isEmpty())

        // `Frame.parse` is what separates them, and it is the only thing that does.
        assertNotNull(Frame.parse(recordless), "a record-less page is still a VALID frame")
        assertNull(Frame.parse(corrupted), "a broken XOR trailer must not parse")

        assertFalse(isCorrupt(recordless), "an empty-but-valid page must NOT be counted as lost")
        assertTrue(isCorrupt(corrupted), "a page acked with a broken trailer IS lost history")
    }

    @Test
    fun validPageWithRecordsIsNeverCounted() { // :60-64
        val page = hex(realPage)
        assertEquals(6, BulkSleep.recordsFromPage(page).size)
        assertFalse(isCorrupt(page))
    }

    @Test
    fun validPageWithOnlyAPartialRecordIsNotCorrupt() { // :69-74
        // Dropping a trailing partial chunk is a decode policy, not a transport failure.
        val partial = sealed(bytes(0x4C, 0x00, 0x26) + ByteArray(10))
        assertTrue(BulkSleep.recordsFromPage(partial).isEmpty(), "10 B < one 23-byte record")
        assertNotNull(Frame.parse(partial))
        assertFalse(isCorrupt(partial))
    }

    @Test
    fun handlerCaseMatchesThePageResponseOpcode() { // :79-82
        assertEquals(0x4C, Frame.responseId(Opcode.PAGE_4C))
        assertEquals(0x4C, hex(realPage).u8(0))
    }
}
