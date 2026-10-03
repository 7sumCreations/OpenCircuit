package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.ringkit.EpochArchive
import io.github.opencircuit.store.EpochArchiveMarks
import io.github.opencircuit.store.StoredEpochArchive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * The stored form of one ring's epoch archive and its drain facts:
 * `{"headAt":<ms>,"hrvPooling":"agree"|"disagree","lastDrainAt":<ms>,"records":"<hex>","unmovedDrains":…}`,
 * absent facts left out.
 *
 * Upstream (ios/OpenCircuit/Store/EpochArchiveStore.swift:37-44 @ b1c2fdd) keeps the raw bytes
 * `EpochArchive.encode` writes and each fact in its own `UserDefaults` key; here they are one
 * value. `records` is those bytes as lowercase hex with no separators, read back with
 * `EpochArchive.decode` (a trailing partial record is dropped, as upstream); hex in any other form
 * makes the archive unreadable. A drain fact that cannot be read reads as upstream's default for
 * a missing key — no date (as for a stored time of 0 or less), 0 drains, no verdict — and never
 * costs the archive, which the ring cannot send again.
 */
object EpochArchiveCodec {

    fun encode(archive: StoredEpochArchive): String {
        val m = archive.marks
        return jsonObjectOf(
            "headAt" to m.headAt?.json(),
            "hrvPooling" to m.hrvPooling?.let { JsonPrimitive(verdictName(it)) },
            "lastDrainAt" to m.lastDrainAt?.json(),
            "records" to JsonPrimitive(lowerHex(EpochArchive.encode(archive.records))),
            "unmovedDrains" to JsonPrimitive(m.unmovedDrains),
        ).toString()
    }

    fun decode(text: String): Decoded<StoredEpochArchive> = readStored(text) { root ->
        val o = root.obj()
        val records = EpochArchive.decode(bytesOf(o.required("records").string()))
        StoredEpochArchive(
            records,
            EpochArchiveMarks(
                lastDrainAt = o.lenientDate("lastDrainAt"),
                headAt = o.lenientDate("headAt"),
                unmovedDrains = orDefault(0) { o.optional("unmovedDrains")?.int() ?: 0 },
                hrvPooling = orDefault(null) { o.optional("hrvPooling")?.string()?.let(::verdictOf) },
            ),
        )
    }

    private fun verdictName(v: BulkSleep.HRVPooling): String = when (v) {
        BulkSleep.HRVPooling.AGREE -> "agree"
        BulkSleep.HRVPooling.DISAGREE -> "disagree"
        BulkSleep.HRVPooling.NO_EVIDENCE -> error("NO_EVIDENCE is never stored") // EpochArchiveMarks refuses it
    }

    /** Upstream's `loadHRVPoolingVerdict`: only the two decided verdicts; anything else is none. */
    private fun verdictOf(s: String): BulkSleep.HRVPooling? = when (s) {
        "agree" -> BulkSleep.HRVPooling.AGREE
        "disagree" -> BulkSleep.HRVPooling.DISAGREE
        else -> null
    }

    /** A stored time, or none when missing, unreadable, or 0 or less (upstream's `t > 0` check). */
    private fun JsonObject.lenientDate(key: String): Instant? =
        orDefault(null) { optional(key)?.long()?.takeIf { it > 0 }?.let(Instant::ofEpochMilli) }

    private inline fun <T> orDefault(default: T, read: () -> T): T =
        try {
            read()
        } catch (e: NotReadable) {
            default
        }

    private fun lowerHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(digits[v ushr 4]).append(digits[v and 0xf])
        }
        return sb.toString()
    }

    private fun bytesOf(hex: String): ByteArray {
        if (hex.length % 2 != 0) unreadable("odd-length hex")
        return ByteArray(hex.length / 2) { i -> ((lowerHexDigit(hex[2 * i]) shl 4) or lowerHexDigit(hex[2 * i + 1])).toByte() }
    }
}
