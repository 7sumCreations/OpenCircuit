package io.github.opencircuit.app

/**
 * History pages for the sync tests, assembled on the raw byte path: the real overnight `0x4c`
 * page (`ios/OpenCircuitKit/Sources/RingKitVerify/main.swift:305-309` @ b1c2fdd — 6 × 23-byte
 * records, counters `0c22a16b` … `0c22a459`, one epoch of 150 s apart) and copies of it with every
 * record moved whole epochs later, each with its own countdown and XOR trailer written by hand.
 * Nothing here goes through the app's or `:ringkit`'s own builders.
 */
internal object HistoryTestPages {

    /** The real page as captured: header `4c 00 26` (38 records still queued after it). */
    val REAL_PAGE: ByteArray = hex(
        "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
            "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
            "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
            "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc",
    )

    /** The six records' counters, as read off the captured bytes. */
    val REAL_COUNTERS: List<Long> = listOf(0x0c22a16bL, 0x0c22a201L, 0x0c22a297L, 0x0c22a32dL, 0x0c22a3c3L, 0x0c22a459L)

    private const val RECORD = 23
    private const val RECORDS_PER_PAGE = 6
    private const val EPOCH_SECONDS = 150L

    /**
     * Page [index] (0-based) of a [count]-page backlog: the real page's records moved
     * `index × 6` epochs later, header countdown = the records still queued after this page.
     */
    fun sleepPage(index: Int, count: Int): ByteArray {
        val shift = index * RECORDS_PER_PAGE * EPOCH_SECONDS
        val queuedAfter = (count - 1 - index) * RECORDS_PER_PAGE
        val body = ArrayList<Byte>()
        body += 0x4C.toByte()
        body += ((queuedAfter ushr 8) and 0xFF).toByte()
        body += (queuedAfter and 0xFF).toByte()
        for (r in 0 until RECORDS_PER_PAGE) {
            val record = REAL_PAGE.copyOfRange(3 + r * RECORD, 3 + (r + 1) * RECORD)
            val counter = REAL_COUNTERS[r] + shift
            record[0] = (counter ushr 24).toByte()
            record[1] = (counter ushr 16).toByte()
            record[2] = (counter ushr 8).toByte()
            record[3] = counter.toByte()
            body += record.toList()
        }
        val xor = body.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
        return (body + xor.toByte()).toByteArray()
    }

    /** The counters of page [index]'s records. */
    fun counters(index: Int): List<Long> = REAL_COUNTERS.map { it + index * RECORDS_PER_PAGE * EPOCH_SECONDS }

    /** A [count]-page backlog, oldest first. */
    fun backlog(count: Int): List<ByteArray> = (0 until count).map { sleepPage(it, count) }
}
