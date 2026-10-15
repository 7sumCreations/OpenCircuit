package io.github.opencircuit.ringkit

import java.security.MessageDigest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `BulkSleep.latestNightRecords` returns exactly what it returned before multi-night staging was
 * built on it. Each golden is the SHA-256 of the returned counters (one decimal per line), taken
 * from the function as it stood before `completeNights` factored its "no night" case out; the
 * differential test already pins the slice's size and ends against upstream per night, this pins
 * every counter on the inputs a multi-night peel feeds it: the whole backlog, the backlog cut at
 * each night boundary, an all-idle stretch (no night: every record, sorted, each counter once), and
 * a shuffled copy with duplicates.
 */
class LatestNightRecordsParityTest {

    private val backlog = BacklogSevenNights.load()

    private fun digest(records: List<BulkRecord>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (r in records) md.update("${r.counter}\n".toByteArray(Charsets.US_ASCII))
        return md.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    private fun latest(records: List<BulkRecord>, zone: ZoneId = backlog.zone) =
        BulkSleep.latestNightRecords(records, zone, temperatures = backlog.temps)

    private fun idle(): List<BulkRecord> =
        backlog.records.filter { it.counter > backlog.nights[2].last && it.counter < backlog.nights[3].first }

    private fun cases(): Map<String, List<BulkRecord>> {
        val out = LinkedHashMap<String, List<BulkRecord>>()
        out["backlog"] = latest(backlog.records)
        // Cut just before night k + 1 starts: the latest night of the prefix is night k.
        for (k in 1 until backlog.nights.size) {
            val cut = backlog.nights[k].first
            out["prefix-before-night-${k + 1}"] = latest(backlog.records.filter { it.counter < cut })
        }
        // Idle records between nights 3 and 4 only: no night — the input sorted, each counter once.
        out["idle-no-night"] = latest(idle().reversed() + idle().take(5))
        out["shuffled-duplicated"] = latest(backlog.records.shuffled(java.util.Random(7)) + backlog.records.take(40))
        out["backlog-new-york"] = latest(backlog.records, ZoneId.of("America/New_York"))
        return out
    }

    @Test
    fun latestNightRecordsIsByteIdenticalToItsPreMultiNightBehaviour() {
        val actual = cases().mapValues { (_, v) -> "${v.size} ${digest(v)}" }
        assertEquals(GOLDENS, actual)
    }

    @Test
    fun theNoNightCaseStillReturnsEveryRecordSortedOnce() {
        assertEquals(idle().map { it.counter }, latest(idle().reversed() + idle().take(5)).map { it.counter })
    }

    private companion object {
        // Taken from the function before the refactor (2026-10-08); the sizes are the spike's.
        val GOLDENS: Map<String, String> = linkedMapOf(
            "backlog" to "172 f03f0a992cfdbac88b605f430bb9d5b496634044485ecc9ee51fb259bc85fdf0",
            "prefix-before-night-2" to "222 c42c604505d551a79f724b4390d7e541d3ed3aab765d10a7c5e6e789c450cec6",
            "prefix-before-night-3" to "241 74d5af1fc7f100c6a769d260fff80a893da8a0a585c13e7fbb192bd58b0e43ad",
            "prefix-before-night-4" to "194 e3949f01c8b7741374d4b7cfe81b785768e361fea2ca06b8e9e8569413219e1f",
            "prefix-before-night-5" to "180 8a921a57d93e3407cd51abc63a21bad8451e3bbd223e0d8d6e0e8f50806adabf",
            "prefix-before-night-6" to "201 ccdc0a31b866245fe3e093ad460e99103c1ccec51b475382cde032a68db9db91",
            "prefix-before-night-7" to "209 0e5ddd0e776601245faea08fa0c064a27b2ae78bfc86c79fd873e8f3a00138cd",
            "idle-no-night" to "345 ba0f84fcaca639ed940cc0249264f544c876f21d5485124d037415a3539c5dd2",
            "shuffled-duplicated" to "172 f03f0a992cfdbac88b605f430bb9d5b496634044485ecc9ee51fb259bc85fdf0",
            "backlog-new-york" to "172 f03f0a992cfdbac88b605f430bb9d5b496634044485ecc9ee51fb259bc85fdf0",
        )
    }
}
