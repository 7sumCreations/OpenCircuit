package io.github.opencircuit.app

import io.github.opencircuit.ringkit.BulkRecord
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * The kept seven-night backlog (`ringkit/src/test/resources/backlog-7-nights/backlog.txt`: seven
 * synthetic nights stitched into one counter-continuous run, guarded in `:ringkit` by
 * `BacklogSevenNightsFixtureTest`) as the ring would send it: `0x4c` pages of six records each,
 * assembled on the raw byte path — header countdown = the records still queued after the page,
 * XOR trailer computed here. Nothing goes through the app's or `:ringkit`'s own builders.
 */
internal object BacklogPages {

    /** The fixture's zone: every night in it is placed on UTC wall-clock time. */
    val ZONE: ZoneOffset = ZoneOffset.UTC

    /** The wake day (start, UTC) of each of the seven nights, oldest first: 2026-06-08 … 2026-06-14. */
    val WAKE_DAYS: List<Instant> = (8..14).map { Instant.parse("2026-06-%02dT00:00:00Z".format(java.util.Locale.ROOT, it)) }

    /** Every record of the backlog, raw 23 bytes each, oldest first (3,654 of them). */
    val records: List<ByteArray> by lazy {
        // Gradle runs the module's tests from the module directory.
        val file = File("../ringkit/src/test/resources/backlog-7-nights/backlog.txt")
        check(file.isFile) { "the kept backlog fixture is missing: ${file.absolutePath}" }
        file.readLines(Charsets.UTF_8).filter { it.startsWith("r ") }.map { hex(it.substring(2)) }
            .also { check(it.size == 3654) { "the backlog holds 3,654 records, read ${it.size}" } }
    }

    /** The counter of a raw record (big-endian `[0:4]`). */
    fun counter(record: ByteArray): Long =
        ((record[0].toLong() and 0xFF) shl 24) or ((record[1].toLong() and 0xFF) shl 16) or
            ((record[2].toLong() and 0xFF) shl 8) or (record[3].toLong() and 0xFF)

    /** The wall-clock date of a raw record, through the one decoder the app itself uses. */
    fun date(record: ByteArray): Instant = BulkRecord.of(record)!!.date()

    /** [of] as `0x4c` pages of [perPage] records, oldest first, each counting down what is still queued after it. */
    fun pages(of: List<ByteArray> = records, perPage: Int = 6): List<ByteArray> {
        val chunks = of.chunked(perPage)
        return chunks.mapIndexed { i, chunk ->
            val queuedAfter = chunks.drop(i + 1).sumOf { it.size }
            val body = ArrayList<Byte>()
            body += 0x4C.toByte()
            body += ((queuedAfter ushr 8) and 0xFF).toByte()
            body += (queuedAfter and 0xFF).toByte()
            chunk.forEach { body += it.toList() }
            val xor = body.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
            (body + xor.toByte()).toByteArray()
        }
    }
}
