package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the replay harness's corpus LOADER — the manifest reader,
 * the records-file decoder, the date and zone parsers and the report formatter. Upstream's corpus
 * tests only ever run on a well-formed private corpus, so none of this is covered there. Kept out
 * of the upstream-port classes so their counts stay exact.
 *
 * The one rule every case here serves: a malformed corpus FAILS, loudly, with a harness error. It
 * never skips (that would read as "no corpus present") and never quietly measures something other
 * than what the manifest says (that would read as a result). Where the expected value is upstream's,
 * it was measured with Foundation on this machine (JSONSerialization, `Data(base64Encoded:options:)`
 * with `.ignoreUnknownCharacters`, ISO8601DateFormatter, `TimeZone(identifier:)`,
 * `TimeZone(secondsFromGMT:)`, `String(contentsOf:encoding:)`); where Kotlin refuses an input
 * Foundation reads leniently, the test says so and `PORTING.md` records it.
 */
class SleepReplayHazardTest {

    private fun tempDir(): File = Files.createTempDirectory("replay-hazard").toFile()

    private fun <T> inDir(body: (File) -> T): T {
        val dir = tempDir()
        try {
            return body(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A raw 23-byte record whose big-endian counter is [counter]; every other byte zero. */
    private fun rawRecord(counter: Long): ByteArray {
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter ushr 24).toByte()
        b[1] = (counter ushr 16).toByte()
        b[2] = (counter ushr 8).toByte()
        b[3] = counter.toByte()
        return b
    }

    private fun stream(vararg counters: Long): ByteArray = counters.fold(ByteArray(0)) { acc, c -> acc + rawRecord(c) }

    private fun harnessRow(id: String = "n1", records: String = "night.b64", extra: String = ""): String =
        """{"id":"$id","timeZone":"Europe/Madrid","records":"$records"$extra}"""

    private fun manifest(dir: File, vararg rows: String) {
        File(dir, "manifest.json").writeText("""{"nights":[${rows.joinToString(",")}]}""")
    }

    // --- the corpus directory and the manifest -------------------------------------------------

    @Test
    fun anEmptyDirectoryOrAFileIsNoCorpusAndFailsLoudly() {
        inDir { dir ->
            assertFailsWith<SleepReplay.ReplayError.NoManifest> { SleepReplay.loadManifest(dir) }
            val file = File(dir, "not-a-dir.txt").apply { writeText("not a corpus") }
            assertFailsWith<SleepReplay.ReplayError.NoManifest> { SleepReplay.loadManifest(file) }
        }
    }

    @Test
    fun aMalformedManifestFailsLoudly() {
        val malformed = listOf(
            "", // empty file
            "{\"nights\":[", // truncated
            "{\"nights\":[{}]} x", // trailing garbage (Foundation: error)
            "[]", // root not an object (Foundation reads it; upstream then says no manifest)
            "{\"nights\":{}}", // nights not an array
            "{\"nights\":[1]}", // a row that is not an object (Foundation's cast fails: no manifest)
            "{\"nights\":[{\"id\":\"x\"},null]}", // likewise for a null row
            "{'nights':[]}", // not JSON
            "{\"nights\":[{\"a\":NaN}]}", // not JSON (Foundation: error)
            "{\"nights\":[{\"a\":01}]}", // leading zero (Foundation: error)
            "{\"nights\":[{\"a\":1.}]}", // bare decimal point (Foundation: error)
            "{\"nights\":[{\"a\":\"\\ud800\"}]}", // lone surrogate escape (Foundation: error)
            "{\"nights\":[{\"a\":1,,}]}", // double comma (Foundation: error)
            "{\"nights\":[{\"a\":1E400}]}", // a number no Double holds (Foundation: error)
            "{\"nights\":[{\"a\":\"x\ty\"}]}", // raw control character in a string (Foundation: error)
            "\u000B{\"nights\":[]}", // non-JSON whitespace (Foundation: error)
        )
        for (text in malformed) {
            inDir { dir ->
                File(dir, "manifest.json").writeText(text)
                val e = runCatching { SleepReplay.loadManifest(dir) }.exceptionOrNull()
                assertTrue(
                    e is SleepReplay.ReplayError.NoManifest || e is SleepReplay.ReplayError.BadManifest,
                    "manifest ${text.take(40)} must fail with a harness error, got $e",
                )
            }
        }
    }

    @Test
    fun invalidUtf8InTheManifestFailsRatherThanBeingReplaced() {
        inDir { dir ->
            File(dir, "manifest.json").writeBytes("{\"nights\":[{\"id\":\"".toByteArray() + byteArrayOf(0xff.toByte()) + "\"}]}".toByteArray())
            assertFailsWith<SleepReplay.ReplayError.BadManifest> { SleepReplay.loadManifest(dir) }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun aDeeplyNestedManifestIsRefusedWithoutBlowingTheStack() {
        // Foundation refuses 1000 levels (measured); a recursive reader must not overflow the stack.
        for (depth in listOf(1_000, 200_000)) {
            inDir { dir -> // an otherwise valid row: without the bound it would be read (1000) or overflow (200000)
                manifest(dir, harnessRow(extra = ",\"x\":" + "[".repeat(depth) + "]".repeat(depth)))
                assertFailsWith<SleepReplay.ReplayError.BadManifest> { SleepReplay.loadManifest(dir) }
            }
        }
        inDir { dir -> // well inside the bound: read normally
            manifest(dir, harnessRow(extra = ",\"x\":" + "[".repeat(100) + "]".repeat(100)))
            assertEquals(listOf("n1"), SleepReplay.loadManifest(dir).map { it.id })
        }
    }

    @Test
    fun foundationsJsonLeniencesThatRealManifestsUseAreKept() {
        inDir { dir -> // trailing commas before a closing bracket, a byte-order mark, surrounding blanks
            File(dir, "manifest.json").writeText("\uFEFF {\"nights\":[{\"id\":\"n1\",\"timeZone\":\"UTC\",\"records\":\"a.b64\",},],} ")
            assertEquals(listOf("n1"), SleepReplay.loadManifest(dir).map { it.id })
        }
        inDir { dir -> // a duplicated key keeps its FIRST value (measured)
            manifest(dir, """{"id":"first","id":"second","timeZone":"UTC"}""")
            assertEquals(listOf("first"), SleepReplay.loadManifest(dir).map { it.id })
        }
        inDir { dir -> // an empty nights array is a valid manifest with no rows
            manifest(dir)
            assertEquals(emptyList(), SleepReplay.loadManifest(dir))
        }
    }

    @Test
    fun manifestNumbersBridgeAsFoundationsCastsDo() {
        // Measured: `as? Int` takes integers, integral doubles and booleans (true is 1), refuses 5.5,
        // 1e30 and anything past Int64; `as? Double` takes every number and boolean but refuses an
        // integer it cannot hold exactly (Int64.max); `as? Bool` takes booleans and the numbers 0/1.
        fun stored(json: String): ReplayNight.Stored = inDir { dir ->
            manifest(dir, harnessRow(extra = ",\"stored\":{\"asleepMin\":$json,\"windowPrecisionSec\":$json,\"isManuallyEdited\":$json}"))
            SleepReplay.loadManifest(dir).single().stored
        }
        data class Cast(val json: String, val int: Long?, val bool: Boolean?)
        val cases = listOf(
            Cast("5", 5, null), Cast("5.0", 5, null), Cast("5e0", 5, null), Cast("5.5", null, null),
            Cast("true", 1, true), Cast("false", 0, false), Cast("1", 1, true), Cast("0", 0, false),
            Cast("1.0", 1, true), Cast("2", 2, null), Cast("-3", -3, null), Cast("-0", 0, false),
            Cast("1e30", null, null), Cast("1e-400", 0, false), Cast("9223372036854775807", Long.MAX_VALUE, null),
            Cast("9223372036854775808", null, null), Cast("\"5\"", null, null), Cast("null", null, null),
        )
        for (c in cases) {
            val s = stored(c.json)
            assertEquals(c.int, s.asleepMin, "as? Int of ${c.json}")
            assertEquals(c.int ?: 1L, s.windowPrecisionSec, "windowPrecisionSec of ${c.json} (default 1)")
            assertEquals(c.bool, s.isManuallyEdited, "as? Bool of ${c.json}")
        }
        fun baseline(json: String): Double? = inDir { dir ->
            manifest(dir, harnessRow(extra = ",\"deepHRBaselineBPM\":$json"))
            SleepReplay.loadManifest(dir).single().deepHRBaselineBPM
        }
        assertEquals(55.0, baseline("55"))
        assertEquals(55.5, baseline("55.5"))
        assertEquals(1.0, baseline("true"))
        assertEquals(1e30, baseline("1e30"))
        assertNull(baseline("9223372036854775807")) // not exactly representable: Foundation refuses
        assertEquals(9.223372036854775807E18, baseline("9223372036854775807.0"))
        assertNull(baseline("\"55\""))
    }

    // --- dates -------------------------------------------------------------------------------

    @Test
    fun wellFormedDatesParseExactlyAsFoundationDoes() {
        // Measured instants; Foundation keeps three fractional digits, truncated.
        val cases = mapOf(
            "2026-08-19T22:18:36Z" to Instant.ofEpochSecond(1_787_177_916),
            "2026-08-19T22:18:36z" to Instant.ofEpochSecond(1_787_177_916),
            "2026-08-19T22:18:36+02:00" to Instant.ofEpochSecond(1_787_170_716),
            "2026-08-19T22:18:36+0200" to Instant.ofEpochSecond(1_787_170_716),
            "2026-08-19T22:18:36+02" to Instant.ofEpochSecond(1_787_170_716),
            "2026-08-19T22:18:36-00:00" to Instant.ofEpochSecond(1_787_177_916),
            "2026-08-19T22:18:36+14:00" to Instant.ofEpochSecond(1_787_127_516),
            "2026-08-19T22:18:36.5Z" to Instant.ofEpochSecond(1_787_177_916, 500_000_000),
            "2026-08-19T22:18:36.12Z" to Instant.ofEpochSecond(1_787_177_916, 120_000_000),
            "2026-08-19T22:18:36.1235Z" to Instant.ofEpochSecond(1_787_177_916, 123_000_000),
            "2026-08-19T22:18:36.9999Z" to Instant.ofEpochSecond(1_787_177_916, 999_000_000),
            "2026-08-19T22:18:36.0005Z" to Instant.ofEpochSecond(1_787_177_916),
            "2026-08-19T22:18:36.123456789Z" to Instant.ofEpochSecond(1_787_177_916, 123_000_000),
            "1969-12-31T23:59:59.5Z" to Instant.ofEpochSecond(-1, 500_000_000),
            "1969-12-31T23:59:59.9999Z" to Instant.ofEpochSecond(-1, 999_000_000),
            "1582-10-15T00:00:00Z" to Instant.ofEpochSecond(-12_219_292_800), // the first Gregorian day: both calendars agree
            " 2026-08-19T22:18:36Z" to Instant.ofEpochSecond(1_787_177_916),
            "2026-08-19T22:18:36Z " to Instant.ofEpochSecond(1_787_177_916),
        )
        for ((text, want) in cases) assertEquals(want, SleepReplay.date(text), text)
        assertNull(SleepReplay.date(null))
        assertNull(SleepReplay.date(""))
    }

    @Test
    fun malformedDatesFailLoudlyEvenWhereFoundationGuesses() {
        // Foundation answers nil for the first group; for the second it GUESSES (a 5-digit year, a
        // fullwidth digit, a one-digit month, 24:00, 30 February, a +25:00 offset all yield a date).
        // A corpus manifest is machine-written, so any of these is corruption: refuse, never guess.
        val foundationRefuses = listOf(
            "2026-08-19T22:18Z", "2026-08-19 22:18:36Z", "2026-08-19T22:18:36", "2026-08-19t22:18:36Z",
            "2026-08-19T22:18:60Z", "2026-08-19T22:18:36.Z", "+2026-08-19T22:18:36Z", "2026-08-19T22:18:36,5Z",
        )
        val foundationGuesses = listOf(
            "12026-08-19T22:18:36Z", "\uFF12026-08-19T22:18:36Z", "2026-8-19T22:18:36Z", "2026-08-19T24:00:00Z",
            "2026-02-30T00:00:00Z", "2026-08-19T22:18:36+25:00", "\u0662\u0660\u0662\u0666-08-19T22:18:36Z",
            // Foundation reads dates before 1582-10-15 on the Julian calendar (measured: 0000-01-01 is
            // -62 167 392 000 s, two days from the proleptic Gregorian answer): refuse, never disagree.
            "0000-01-01T00:00:00Z", "1582-10-14T23:59:59Z",
        )
        for (text in foundationRefuses + foundationGuesses) {
            assertFailsWith<SleepReplay.ReplayError.BadDate>(text) { SleepReplay.date(text) }
        }
    }

    @Test
    fun aBadDateAnywhereInARowFailsTheWholeManifest() {
        inDir { dir ->
            manifest(dir, harnessRow(extra = ",\"inputTruncateAfter\":\"yesterday\""))
            assertFailsWith<SleepReplay.ReplayError.BadDate> { SleepReplay.loadManifest(dir) }
        }
        inDir { dir -> // a temperature row with a bad time is not silently dropped
            manifest(dir, harnessRow(extra = ",\"temperatures\":[{\"t\":\"later\",\"c\":33.0}]"))
            assertFailsWith<SleepReplay.ReplayError.BadDate> { SleepReplay.loadManifest(dir) }
        }
    }

    // --- time zones --------------------------------------------------------------------------

    @Test
    fun aRowsZoneResolvesAsFoundationsOrIsRefused() {
        fun zoneOf(fields: String): ZoneId = inDir { dir ->
            manifest(dir, """{"id":"z","records":"a.b64"$fields}""")
            SleepReplay.loadManifest(dir).single().zone
        }
        fun refused(fields: String) = inDir { dir ->
            manifest(dir, """{"id":"z","records":"a.b64"$fields}""")
            assertFailsWith<SleepReplay.ReplayError.NoTimeZone>(fields) { SleepReplay.loadManifest(dir) }
        }
        // Names Foundation resolves (measured).
        for (name in listOf("Europe/Madrid", "UTC", "GMT", "CET", "Etc/GMT-2", "Asia/Calcutta", "America/Argentina/Buenos_Aires")) {
            assertEquals(name, zoneOf(",\"timeZone\":\"$name\"").id, name)
        }
        // The tz database's fixed legacy zones: Foundation resolves them (measured, no daylight time); java.time has no region.
        for ((name, seconds) in listOf("EST" to -18_000, "MST" to -25_200, "HST" to -36_000)) {
            val z = zoneOf(",\"timeZone\":\"$name\"")
            assertEquals(seconds, z.rules.getOffset(Instant.EPOCH).totalSeconds, name)
            assertEquals(seconds, z.rules.getOffset(Instant.ofEpochSecond(1_787_177_916)).totalSeconds, name)
        }
        assertEquals(3600, zoneOf(",\"timeZone\":\"GMT+1\"").rules.getOffset(Instant.EPOCH).totalSeconds)
        assertEquals(3600, zoneOf(",\"timeZone\":\"UTC+1\"").rules.getOffset(Instant.EPOCH).totalSeconds)
        // `timeZone` wins over `timeZoneIdentifier`; an invalid `timeZone` does NOT fall back to it.
        assertEquals("Asia/Tokyo", zoneOf(",\"timeZoneIdentifier\":\"Asia/Tokyo\"").id)
        assertEquals("Europe/Madrid", zoneOf(",\"timeZone\":\"Europe/Madrid\",\"timeZoneIdentifier\":\"Asia/Tokyo\"").id)
        // Names Foundation refuses fall back to the offset, else the row is refused.
        for (bad in listOf("Z", "+01:00", "europe/madrid", "", "Mars/Olympus")) {
            assertEquals(ZoneOffset.ofHours(2), zoneOf(",\"timeZone\":\"$bad\",\"timeZoneOffsetSeconds\":7200"), bad)
            refused(",\"timeZone\":\"$bad\"")
        }
        refused(",\"timeZone\":\"Mars/Olympus\",\"timeZoneIdentifier\":\"Asia/Tokyo\"")
        // Offsets: Foundation accepts |seconds| <= 18 h and refuses beyond (measured at ±64 801).
        assertEquals(ZoneOffset.ofTotalSeconds(64_800), zoneOf(",\"timeZoneOffsetSeconds\":64800"))
        assertEquals(ZoneOffset.ofTotalSeconds(-64_800), zoneOf(",\"timeZoneOffsetSeconds\":-64800"))
        assertEquals(ZoneOffset.ofTotalSeconds(59), zoneOf(",\"timeZoneOffsetSeconds\":59"))
        for (off in listOf("64801", "-64801", "86400", "9223372036854775807", "3600.5", "\"3600\"")) refused(",\"timeZoneOffsetSeconds\":$off")
        refused("")
        // Kotlin-only refusal: Foundation maps these abbreviations to regions of its own choosing
        // (measured: CST is US Central, IST India, BST +6 h); a row naming one is refused, never guessed.
        for (abbreviation in listOf("PST", "CST", "IST", "BST")) refused(",\"timeZone\":\"$abbreviation\"")
    }

    // --- the records file --------------------------------------------------------------------

    @Test
    fun recordsFileBase64DecodesExactlyAsFoundationDoes() {
        // Foundation `Data(base64Encoded:options: .ignoreUnknownCharacters)`, measured. null = nil.
        val cases = mapOf(
            "" to "", "QUJD" to "65,66,67", "QUJ" to null, "QUI=" to "65,66", "QQ==" to "65", "QQ=" to null,
            "QQ" to null, "Q" to null, "QU JD" to "65,66,67", "QUJD\nRUZH" to "65,66,67,69,70,71",
            "QU=JD" to null, "QUJD====" to "65,66,67", "QUJDRQ==RUZH" to "65,66,67,69", "-_-_" to "",
            "QUJD\u00e9" to "65,66,67", "Q\u0000UJD" to "65,66,67", "QUI=QUI=" to "65,66", "====" to null,
            "=QUJD" to null, "QUJD=" to "65,66,67", "QUJ=" to "65,66", "QR==" to "65", "QUJ\uFF24" to null,
            "QUJD=RUZH" to null, "QQ===" to "65", "QQ==X" to "65", "QUI==" to "65,66", "QUI=X" to "65,66",
            "=" to null, "==" to null, "QUJD==" to "65,66,67", "QQ=A" to null, "QUJD=A" to null,
            "QUJD==RUZH" to null, "QUJDQQ==" to "65,66,67,65", "QUJDQQ===" to "65,66,67,65", "QUJDQQ=" to null,
            "QQ= =" to "65", "QQ\n==" to "65", "QUJD QUJ" to null, "QUJD\r\nQUJ=" to "65,66,67,65,66",
            // Text with nothing BUT ignored characters: empty when its UTF-8 length is a multiple of
            // four, else refused (measured; it held on all 305 such strings of a 3000-string sweep).
            "\n" to null, "-" to null, "--" to null, "éé" to "", "-é " to "", "éé " to null, "-   " to "",
        )
        for ((text, want) in cases) {
            val got = SleepReplay.decodeBase64(text)?.joinToString(",") { (it.toInt() and 0xff).toString() }
            assertEquals(want, got, "base64 of ${text.map { it.code.toString(16) }}")
        }
    }

    @Test
    fun recordsTextIsTrimmedWithFoundationsWhitespaceSetBeforeDecoding() {
        // Measured: trimmed — U+0009…U+000D, space, U+0085, U+00A0, U+1680, U+2000, U+200B, U+2028,
        // U+2029, U+3000; kept — U+001C, U+001F (Java's isWhitespace would trim both) and U+FEFF.
        for (c in listOf('\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', ' ', ' ', ' ', '​', ' ', ' ', '　')) {
            assertEquals("Q", SleepReplay.trimWhitespacesAndNewlines("${c}Q$c"), "U+%04X".format(java.util.Locale.ROOT, c.code))
        }
        for (c in listOf('\u001C', '\u001F', '﻿')) {
            assertEquals("${c}Q$c", SleepReplay.trimWhitespacesAndNewlines("${c}Q$c"), "U+%04X".format(java.util.Locale.ROOT, c.code))
        }
        inDir { dir -> // a records file holding only a newline is an empty night, not bad base64
            manifest(dir, harnessRow())
            File(dir, "night.b64").writeText("\n")
            assertEquals(emptyList(), SleepReplay.loadRecords(SleepReplay.loadManifest(dir).single(), dir))
        }
    }

    @Test
    fun aRecordsFileFailsLoudlyWhenItCannotBeRead() {
        inDir { dir ->
            manifest(dir, harnessRow(), harnessRow(id = "n2", records = ""), harnessRow(id = "n3", records = "missing.b64"))
            val (good, summaryOnly, missing) = SleepReplay.loadManifest(dir)
            assertFailsWith<SleepReplay.ReplayError.NoRecords> { SleepReplay.loadRecords(summaryOnly, dir) }
            assertFailsWith<SleepReplay.ReplayError.UnreadableRecords> { SleepReplay.loadRecords(missing, dir) }
            File(dir, "night.b64").writeText("QUJ") // not base64 Foundation accepts
            assertFailsWith<SleepReplay.ReplayError.BadBase64> { SleepReplay.loadRecords(good, dir) }
            // Invalid UTF-8: Foundation's String(contentsOf:encoding:.utf8) throws (measured); a
            // replacing decoder would drop the byte and read the rest as a clean night.
            val bytes = Base64.getEncoder().encode(stream(1_000, 1_150))
            File(dir, "night.b64").writeBytes(bytes.copyOfRange(0, 8) + byteArrayOf(0xff.toByte()) + bytes.copyOfRange(8, bytes.size))
            assertFailsWith<SleepReplay.ReplayError.UnreadableRecords> { SleepReplay.loadRecords(good, dir) }
        }
    }

    @Test
    fun aTruncatedRecordStreamDropsOnlyThePartialTailAsOnDevice() {
        inDir { dir ->
            manifest(dir, harnessRow())
            val night = SleepReplay.loadManifest(dir).single()
            val whole = stream(1_000, 1_150, 1_300)
            File(dir, "night.b64").writeText(Base64.getMimeEncoder().encodeToString(whole.copyOfRange(0, whole.size - 5)) + "\n")
            assertEquals(listOf(1_000L, 1_150L), SleepReplay.loadRecords(night, dir).map { it.counter })
            File(dir, "night.b64").writeText("")
            assertEquals(emptyList(), SleepReplay.loadRecords(night, dir))
        }
    }

    @Test
    fun truncateAfterKeepsRecordsAtOrBeforeTheCut() {
        inDir { dir ->
            val cut = Instant.ofEpochSecond(1_150 + Command.SYNC_EPOCH)
            manifest(dir, harnessRow(extra = ",\"inputTruncateAfter\":\"$cut\""))
            File(dir, "night.b64").writeText(Base64.getEncoder().encodeToString(stream(1_000, 1_150, 1_300)))
            assertEquals(listOf(1_000L, 1_150L), SleepReplay.loadRecords(SleepReplay.loadManifest(dir).single(), dir).map { it.counter })
        }
    }

    // --- a wrongly typed nested field is read as absent, exactly as Foundation's casts read it ---

    @Test
    fun aWronglyTypedNestedFieldReadsAsAbsentAsUpstreamsCastDoes() {
        inDir { dir ->
            // One non-object temperature entry fails `as? [[String: Any]]` for the WHOLE list.
            manifest(dir, harnessRow(extra = ",\"temperatures\":[{\"t\":\"2026-08-19T22:18:36Z\",\"c\":33.5},5]"))
            assertEquals(emptyList(), SleepReplay.loadManifest(dir).single().temperatures)
        }
        inDir { dir ->
            manifest(dir, harnessRow(extra = ",\"temperatures\":[{\"t\":\"2026-08-19T22:18:36Z\",\"c\":33},{\"t\":\"2026-08-19T22:20:00Z\",\"c\":\"x\"}]"))
            assertEquals(
                listOf(ReplayNight.Temperature(Instant.ofEpochSecond(1_787_177_916), 33.0)),
                SleepReplay.loadManifest(dir).single().temperatures,
            )
        }
        inDir { dir -> // a fidelity list with a non-string member is no list at all (asserts nothing)
            manifest(dir, harnessRow(extra = ",\"stored\":{\"fidelity\":[\"inBedStart\",3]}"))
            assertEquals(emptyList(), SleepReplay.loadManifest(dir).single().stored.fidelity)
        }
        inDir { dir -> // an edit missing one anchor is no edit
            manifest(dir, harnessRow(extra = ",\"stored\":{\"edit\":{\"inBedStart\":\"2026-08-19T22:00:00Z\",\"sleepOnset\":\"2026-08-19T22:30:00Z\"}}"))
            assertNull(SleepReplay.loadManifest(dir).single().stored.edit)
        }
    }

    // --- the report never throws on what staging can hand it ----------------------------------

    @Test
    fun reportFormattingPrintsNonFiniteValuesAsCDoesInsteadOfThrowing() {
        assertEquals("nan", SleepReplay.fixed(Double.NaN, 3))
        assertEquals("inf", SleepReplay.fixed(Double.POSITIVE_INFINITY, 3))
        assertEquals("-inf", SleepReplay.fixed(Double.NEGATIVE_INFINITY, 1))
        assertEquals("-0.0", SleepReplay.fixed(-0.04, 1)) // C keeps the sign of a negative that rounds to zero
        assertEquals("0.125", SleepReplay.fixed(0.125, 3))
        assertEquals("0.12", SleepReplay.fixed(0.125, 2)) // exact binary tie: to even, as printf
        assertEquals("0.2", SleepReplay.fixed(0.25, 1))
        assertEquals("0.3", SleepReplay.fixed(0.35, 1)) // 0.35 is just below the tie in binary
        assertEquals("1000000000000000019884624838656.0000", SleepReplay.fixed(1e30, 4))
        assertEquals("—", SleepReplay.clock(null, ZoneOffset.UTC))
        assertEquals("08-19 22:18:36", SleepReplay.clock(Instant.ofEpochSecond(1_787_177_916), ZoneOffset.UTC))
        // Foundation's ISO8601DateFormatter output: `Z` at offset zero, `±HH:MM` otherwise.
        assertEquals("2026-08-19T22:18:36Z", SleepReplay.iso(Instant.ofEpochSecond(1_787_177_916), ZoneOffset.UTC))
        assertEquals("2026-08-20T00:18:36+02:00", SleepReplay.iso(Instant.ofEpochSecond(1_787_177_916), ZoneId.of("Europe/Madrid")))
        assertEquals("", SleepReplay.iso(null, ZoneOffset.UTC))
    }
}
