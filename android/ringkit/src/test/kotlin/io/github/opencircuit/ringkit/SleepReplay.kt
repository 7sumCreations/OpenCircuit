package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Assumptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// SLEEP REPLAY HARNESS — stage any night from raw bytes, from the command line.
//
// WHY THIS EXISTS. Every sleep-accuracy claim has to be MEASURED on real records, not argued about.
// This file is the measuring instrument: it takes a corpus of nights (base64 0x4c records + a
// manifest row) and runs the SAME staging pipeline the shipped app runs, then reports the detected
// window, the stage minutes and — where the night carries a stored summary or a ground-truth label —
// the signed error against it. No corpus lives in this repository, ever (it is real health data);
// a corpus test points at one through an environment variable and SKIPS without it.
//
// PRODUCTION PARITY. `SleepReplay.stage` transcribes upstream's app path line for line, as upstream's
// harness documents it (iOS app RingSession: commitDrainedRecords → overnightStagedSegments →
// persistSleepAndSteps):
//   1. union        = EpochArchive.merge(existing = [], incoming = records)   — dedup by counter,
//                     sort, 30 h retention prune (what the first drain after a cold launch does)
//   2. nightRecords = BulkSleep.latestNightRecords(union, zone, temperatures, …)
//   3. segments     = SleepStaging.classify(nightRecords, temperatures, tuning, baseline, …)
//   4. the overnight envelope gate: the in-bed envelope [lo, hi] is kept if
//      SleepWindow.isOvernightBlock(lo, hi, zone), else only if it passes again with
//      onsetIsUnobserved judged against the UNION (not the night slice); otherwise nothing
//   5. what gets STORED: SleepStaging.summary(segments), the min start / max end, and
//      SleepStaging.sleepWindow(segments).
//
// THE FOUR INPUTS PRODUCTION HAS THAT A RECORDS FILE DOES NOT — all manifest fields:
//   1. LOCAL TIME ZONE. Upstream's overnight test reads the PROCESS calendar, so its harness switched
//      the process time zone around each night (`withTimeZone`) and asserted the switch took. Here
//      every calendar question already takes an explicit zone, so the night's zone is PASSED — there
//      is no process state to switch, and nothing to assert.
//   2. the session's night temperature log (cannot be reconstructed exactly; `[]` is the honest default);
//   3. the personal deep-HR baseline (manifest `deepHRBaselineBPM`);
//   4. the archive snapshot (`inputProvenance` / `inputCaveat` / `inputTruncateAfter`).
//
// Deliberately NOT reproduced (none of it feeds the stored window or the stage minutes): the coarse
// card timeline, sleep extras, the score, naps, the health-store mirror, and the post-edit overlay.
//
// THE CORPUS GATE. `requireCorpus` is the ONLY way a test may open a corpus: unset or empty →
// a JUnit assumption aborts the test, which JUnit reports as SKIPPED (never passed); set to anything
// that is not a readable directory → a hard failure. `CorpusGateLoudnessTest` audits every test
// source for the ways around it. Every `OC_…` environment name the tests use is declared HERE, in
// [SleepReplay.Corpus] and [SleepReplay.Setting], and this is the only file that reads the environment.
//
// Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepReplay.swift (@ b1c2fdd).

/** One manifest row: a corpus night and everything the manifest says about it. */
data class ReplayNight(
    val id: String,
    val zone: ZoneId,
    val recordsFile: String,
    val dialect: String,
    val appBuild: String?,
    val ring: String?,
    val source: String?,
    val inputProvenance: String?,
    val inputCaveat: String?,
    val codeParity: String?,
    /** Replay only records at or before this instant (the archive as it stood). Null = everything. */
    val inputTruncateAfter: Instant?,
    val inputTruncateReason: String?,
    val deepHRBaselineBPM: Double?,
    val temperatures: List<Temperature>,
    val stored: Stored,
    val label: Label?,
) {
    /** A night the user EDITED stores `SleepEdit.recompute` minutes; where its anchors are known they are checked end to end. */
    data class Edit(
        val inBedStart: Instant,
        val sleepOnset: Instant,
        val sleepWake: Instant,
        val onsetProvenance: String?,
        val asleepMin: Long?,
        val awakeMin: Long?,
        val deepMin: Long?,
        val remMin: Long?,
        val lightMin: Long?,
        val fidelity: List<String>,
    )

    data class Stored(
        val isManuallyEdited: Boolean? = null,
        val inBedStart: Instant? = null,
        val inBedEnd: Instant? = null,
        val sleepOnset: Instant? = null,
        val sleepWake: Instant? = null,
        val asleepMin: Long? = null,
        val awakeMin: Long? = null,
        val deepMin: Long? = null,
        val remMin: Long? = null,
        val lightMin: Long? = null,
        val windowPrecisionSec: Long = 1,
        /** Field names this row may be ASSERTED on. Empty = measured only. */
        val fidelity: List<String> = emptyList(),
        val minutesNote: String? = null,
        val edit: Edit? = null,
    )

    /** Ground truth, kept apart from [stored] on purpose: edits are a biased sample. */
    data class Label(val onset: Instant?, val wake: Instant?, val source: String?)

    /** One reconstructed temperature sample (time, °C). */
    data class Temperature(val t: Instant, val c: Double)

    fun temperatureSamples(): List<TemperatureSample> = temperatures.map { TemperatureSample(it.t, it.c) }
}

/** Everything one replayed night produces. A null instant means staging emitted nothing there. */
data class ReplayResult(
    val night: ReplayNight,
    val recordsLoaded: Int,
    val recordsAfterRetention: Int,
    val nightScopedRecords: Int,
    val segments: List<SleepSegment>,
    val inBedStart: Instant?,
    val inBedEnd: Instant?,
    val onset: Instant?,
    val wake: Instant?,
    val inBedMin: Long,
    val asleepMin: Long,
    val awakeMin: Long,
    val deepMin: Long,
    val remMin: Long,
    val lightMin: Long,
    val efficiency: Double,
) {
    val id: String get() = night.id
    val zone: ZoneId get() = night.zone

    val storedStartDeltaMin: Long? get() = delta(inBedStart, night.stored.inBedStart)
    val storedEndDeltaMin: Long? get() = delta(inBedEnd, night.stored.inBedEnd)
    val labelOnsetErrorMin: Long? get() = delta(onset, night.label?.onset)
    val labelWakeErrorMin: Long? get() = delta(wake, night.label?.wake)

    companion object {
        /** Signed whole minutes, detected − reference, rounded half away from zero. Positive = placed LATE. */
        fun delta(detected: Instant?, reference: Instant?): Long? {
            if (detected == null || reference == null) return null
            return roundHalfAwayFromZero(SleepReplay.secondsBetween(reference, detected) / 60).toLong()
        }
    }
}

object SleepReplay {

    /** Why a corpus could not be replayed. Every case FAILS the test; none of them is a skip. */
    sealed class ReplayError(message: String) : Exception(message) {
        class NoManifest(path: String) : ReplayError("no manifest.json under $path")

        /** Kotlin-side: upstream surfaces Foundation's own parse error here. */
        class BadManifest(path: String, detail: String) : ReplayError("$path is not a readable corpus manifest: $detail")

        class NoRecords(id: String) : ReplayError("corpus night $id: summary-only row, no records file — nothing to replay")

        /** Kotlin-side: upstream surfaces Foundation's own read error here (a missing file, or text that is not UTF-8). */
        class UnreadableRecords(id: String, detail: String) : ReplayError("corpus night $id: records file cannot be read: $detail")

        class BadBase64(id: String) : ReplayError("corpus night $id: records file is not valid base64")

        class NoTimeZone(id: String) : ReplayError(
            "corpus night $id: no timeZone/timeZoneIdentifier/timeZoneOffsetSeconds — " +
                "the 21:00/09:00 overnight cliff cannot be evaluated without one, so this row is refused",
        )

        class BadDate(text: String) : ReplayError("unparseable ISO-8601 timestamp '$text'")

        class CorpusDirectoryMissing(variable: String, path: String) : ReplayError(
            "$variable is set to '$path', which is not a readable directory. The corpus gate REFUSES to skip here: the " +
                "variable being set means someone intended to measure something, so a typo'd path must fail, not quietly report success.",
        )
    }

    /** Every corpus a test may open, with upstream's environment variable name. The ONLY place a corpus name is written. */
    enum class Corpus(val variable: String) {
        SLEEP("OC_SLEEP_CORPUS"),
        FIDELITY("OC_SLEEP_FIDELITY_CORPUS"),
        COVERAGE("OC_SLEEP_COVERAGE_CORPUS"),
        BASELINE("OC_SLEEP_BASELINE_CORPUS"),
        PROVENANCE("OC_SLEEP_PROVENANCE_CORPUS"),
        NOTICE("OC_SLEEP_NOTICE_CORPUS"),
        MONOTONICITY("OC_SLEEP_MONOTONICITY_CORPUS"),
    }

    /**
     * Optional, non-corpus settings. Unset means "the shipped default" or "no output file" — a real
     * measurement, never a skipped one — so these are read plainly, not through the gate.
     */
    enum class Setting(val variable: String) {
        BASELINE_OUT("OC_SLEEP_BASELINE_OUT"),
        PROBE_OUT("OC_SLEEP_PROBE_OUT"),
        ABSORB_CUT("OC_SLEEP_ABSORB_CUT"),
        MOTION_CUT("OC_SLEEP_MOTION_CUT"),
    }

    /** The raw value of an optional [Setting], or null. */
    fun setting(setting: Setting, environment: Map<String, String> = System.getenv()): String? = environment[setting.variable]

    // MARK: The corpus gate

    /**
     * Raw variable lookup. `private` ON PURPOSE: a visible nullable lookup re-opens `?: return`, the
     * hole that let a corpus-gated test report PASSED having asserted nothing. `CorpusGateLoudnessTest`
     * fails if this stops being private.
     */
    private fun dir(variable: String, environment: Map<String, String>): File? {
        val p = environment[variable]
        if (p.isNullOrEmpty()) return null
        val expanded = if (p == "~" || p.startsWith("~/")) System.getProperty("user.home") + p.substring(1) else p
        return File(expanded)
    }

    /**
     * THE ONLY WAY a test may open a corpus. Unset or empty → a JUnit assumption aborts the test, so
     * JUnit reports it SKIPPED, never passed; set to anything that is not a directory → a hard failure,
     * because the variable being set means somebody meant to measure.
     *
     * [environment] is injectable only so `CorpusGateLoudnessTest` can exercise both branches
     * without touching the process; corpus tests take the default.
     */
    fun requireCorpus(
        variable: String,
        purpose: String,
        consequence: String? = null,
        environment: Map<String, String> = System.getenv(),
    ): File {
        val found = dir(variable, environment)
        if (found == null) {
            val reason = "SKIPPED — NOTHING WAS MEASURED. $variable is unset, so $purpose DID NOT RUN. " +
                (consequence?.let { "$it " } ?: "") +
                "This is a skip, not a pass: do not quote this run as evidence of anything. " +
                "To actually run it, point $variable at a corpus directory (see docs/SLEEP_REPLAY_HARNESS.md §1)."
            println("[corpus-gate] $reason")
            Assumptions.assumeTrue(false, reason)
            throw IllegalStateException("unreachable: a false assumption always aborts the test")
        }
        if (!found.isDirectory) throw ReplayError.CorpusDirectoryMissing(variable, found.path)
        return found
    }

    /** [requireCorpus] for a named [Corpus]. */
    fun requireCorpus(
        corpus: Corpus,
        purpose: String,
        consequence: String? = null,
        environment: Map<String, String> = System.getenv(),
    ): File = requireCorpus(corpus.variable, purpose, consequence, environment)

    /**
     * [requireCorpus] for an entry point that accepts more than one corpus: the first one SET wins,
     * and the skip message names them all. Routed through the single gate so the skip stays loud.
     */
    fun requireCorpus(
        anyOf: List<Corpus>,
        purpose: String,
        consequence: String? = null,
        environment: Map<String, String> = System.getenv(),
    ): File {
        require(anyOf.isNotEmpty()) { "requireCorpus(anyOf) needs at least one corpus" }
        val set = anyOf.firstOrNull { environment[it.variable]?.isNotEmpty() == true }
            ?: return requireCorpus(
                anyOf[0].variable, purpose,
                (consequence?.let { "$it " } ?: "") + "Any of these would have run it: ${anyOf.joinToString(", ") { it.variable }}.",
                environment,
            )
        return requireCorpus(set.variable, purpose, consequence, environment)
    }

    // MARK: Dates

    private val isoDate = Regex(
        "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]+))?(Z|z|[+-][0-9]{2}(?::?[0-9]{2})?)",
    )

    /** The first Gregorian day: Foundation reads earlier dates on the Julian calendar, java.time on the proleptic Gregorian one. */
    private val GREGORIAN_START: LocalDate = LocalDate.of(1582, 10, 15)

    /**
     * An internet date-time (`2026-08-19T22:18:36Z`, `…+02:00`, `….123Z`), read as Foundation's
     * `ISO8601DateFormatter` reads it: fractional seconds truncated to milliseconds, surrounding blanks
     * ignored. Null or empty → null. Anything else throws [ReplayError.BadDate] — including the shapes
     * Foundation GUESSES at (a 5-digit year, a non-ASCII digit, a one-digit field, 24:00, 30 February,
     * an offset past 18 h) and dates before the Gregorian calendar began, where the two calendars
     * disagree: a corpus manifest is machine-written, so those are corruption, never a different answer.
     */
    fun date(text: String?): Instant? {
        if (text.isNullOrEmpty()) return null
        val m = isoDate.matchEntire(text.trim(' ')) ?: throw ReplayError.BadDate(text)
        val g = m.groupValues
        return try {
            val offset = when (val z = g[8]) {
                "Z", "z" -> ZoneOffset.UTC
                else -> {
                    val digits = z.substring(1).replace(":", "")
                    val hours = digits.substring(0, 2).toInt()
                    val minutes = if (digits.length > 2) digits.substring(2).toInt() else 0
                    if (hours > 18 || minutes > 59) throw ReplayError.BadDate(text)
                    val total = hours * 3600 + minutes * 60
                    ZoneOffset.ofTotalSeconds(if (z[0] == '-') -total else total)
                }
            }
            val millis = g[7].take(3).padEnd(3, '0').toInt()
            val day = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
            if (day < GREGORIAN_START) throw ReplayError.BadDate(text)
            day.atTime(LocalTime.of(g[4].toInt(), g[5].toInt(), g[6].toInt(), millis * 1_000_000)).toInstant(offset)
        } catch (_: DateTimeException) {
            throw ReplayError.BadDate(text)
        }
    }

    // MARK: Loading

    /**
     * The `nights` rows of `<dir>/manifest.json` as raw JSON objects (for the census columns the
     * [ReplayNight] model does not carry), or null when the manifest has no `nights` array of objects.
     * No manifest → [ReplayError.NoManifest]; unreadable JSON → [ReplayError.BadManifest].
     */
    fun rawManifestRows(dir: File): List<ReplayJson.Obj>? {
        val file = File(dir, "manifest.json")
        val bytes = try {
            if (!file.isFile) throw ReplayError.NoManifest(dir.path)
            file.readBytes()
        } catch (_: IOException) {
            throw ReplayError.NoManifest(dir.path)
        }
        val root = try {
            ReplayJson.parse(bytes)
        } catch (e: ReplayJson.SyntaxError) {
            throw ReplayError.BadManifest(file.path, e.message ?: "unreadable")
        }
        return root.asObject()?.get("nights")?.asObjectList()
    }

    /** Every manifest row as a [ReplayNight]. Any row that cannot be read fails the whole manifest. */
    fun loadManifest(dir: File): List<ReplayNight> {
        val rows = rawManifestRows(dir) ?: throw ReplayError.NoManifest(File(dir, "manifest.json").path)
        return rows.map { parse(it) }
    }

    /**
     * A row's zone as upstream resolves it: a named zone first (`timeZone`, else `timeZoneIdentifier`;
     * an unresolvable name does NOT fall through to the other name), then a fixed `timeZoneOffsetSeconds`
     * within ±18 h; null when neither resolves.
     */
    fun rowZone(row: ReplayJson.Obj): ZoneId? {
        val name = row.string("timeZone") ?: row.string("timeZoneIdentifier")
        if (name != null) foundationZone(name)?.let { return it }
        val off = row.long("timeZoneOffsetSeconds") ?: return null
        return if (off in -MAX_OFFSET_SECONDS..MAX_OFFSET_SECONDS) ZoneOffset.ofTotalSeconds(off.toInt()) else null
    }

    /**
     * A zone from an identifier Foundation's `TimeZone(identifier:)` would accept. java.time also
     * accepts bare offsets (`Z`, `+01:00`) as identifiers; Foundation does not, so they are refused.
     * The tz database's three fixed legacy zones (`EST`, `MST`, `HST`), which Foundation resolves and
     * java.time does not, map to their fixed offsets (measured). Kotlin-side: the other abbreviations
     * Foundation maps to a region of its own choosing (`PST`, `CST`, `IST`, `BST` — the last is +6 h
     * there) are refused rather than guessed.
     */
    private fun foundationZone(name: String): ZoneId? {
        if (name.isEmpty() || name == "Z" || name[0] == '+' || name[0] == '-') return null
        LEGACY_FIXED_ZONES[name]?.let { return it }
        return try {
            ZoneId.of(name)
        } catch (_: DateTimeException) {
            null
        }
    }

    private val LEGACY_FIXED_ZONES: Map<String, ZoneId> =
        mapOf("EST" to ZoneOffset.ofHours(-5), "MST" to ZoneOffset.ofHours(-7), "HST" to ZoneOffset.ofHours(-10))

    private const val MAX_OFFSET_SECONDS = 18L * 3600

    private fun parse(r: ReplayJson.Obj): ReplayNight {
        val isShared = r.has("ringId")
        val id = r.string("id") ?: listOfNotNull(r.string("ringId"), r.string("night")).joinToString("_")
        val zone = rowZone(r) ?: throw ReplayError.NoTimeZone(id)

        var stored = ReplayNight.Stored()
        var label: ReplayNight.Label? = null
        val recordsFile: String
        val appBuild: String?
        var deepHR: Double? = null
        val temps = mutableListOf<ReplayNight.Temperature>()

        if (isShared) {
            recordsFile = r.string("recordsFile") ?: ""
            appBuild = r["appBuilds"]?.asStringList()?.joinToString("/")
            // Shared-corpus rows are MEASURED, never asserted: they carry neither the temperature log nor
            // the personal baseline, and their record window is a fixed slice, not the app's archive.
            stored = ReplayNight.Stored(
                isManuallyEdited = r.bool("isManuallyEdited"),
                inBedStart = date(r.string("recordedInBedStart")),
                inBedEnd = date(r.string("recordedInBedEnd")),
                sleepOnset = date(r.string("recordedOnset")),
                sleepWake = date(r.string("recordedWake")),
                asleepMin = r.long("asleepMin"),
                awakeMin = r.long("awakeMin"),
                deepMin = r.long("deepMin"),
                remMin = r.long("remMin"),
                lightMin = r.long("lightMin"),
                windowPrecisionSec = if (r.string("edgePrecision") == "minutes") 60 else 1,
                fidelity = emptyList(),
                minutesNote = "shared corpus — measured only, see the parity block at the top of SleepReplay.kt",
            )
            if (r.bool("isLabelled") == true) {
                label = ReplayNight.Label(date(r.string("editedOnset")), date(r.string("editedWake")), "user sleep edit (>= 3 min correction)")
            }
        } else {
            recordsFile = r.string("records") ?: ""
            appBuild = r.long("appBuild")?.toString()
            deepHR = r.double("deepHRBaselineBPM")
            val s = r.obj("stored")
            if (s != null) {
                stored = ReplayNight.Stored(
                    isManuallyEdited = s.bool("isManuallyEdited"),
                    inBedStart = date(s.string("inBedStart")),
                    inBedEnd = date(s.string("inBedEnd")),
                    sleepOnset = date(s.string("sleepOnset")),
                    sleepWake = date(s.string("sleepWake")),
                    asleepMin = s.long("asleepMin"),
                    awakeMin = s.long("awakeMin"),
                    deepMin = s.long("deepMin"),
                    remMin = s.long("remMin"),
                    lightMin = s.long("lightMin"),
                    windowPrecisionSec = s.long("windowPrecisionSec") ?: 1,
                    fidelity = s["fidelity"]?.asStringList() ?: emptyList(),
                    minutesNote = s.string("minutesNote"),
                    edit = s.obj("edit")?.let { e -> edit(e) },
                )
            }
            val l = r.obj("label")
            if (l != null) label = ReplayNight.Label(date(l.string("onset")), date(l.string("wake")), l.string("source"))
            for (t in r["temperatures"]?.asObjectList() ?: emptyList()) {
                val d = date(t.string("t")) ?: continue
                val c = t.double("c") ?: continue
                temps += ReplayNight.Temperature(d, c)
            }
        }

        val ringObj = r.obj("ring")
        val ring = if (ringObj != null) {
            listOfNotNull(ringObj.string("generation"), ringObj.string("firmware")).joinToString(" ")
        } else {
            listOfNotNull(r.string("ringGeneration"), r.string("firmware")).joinToString(" ")
        }

        return ReplayNight(
            id = id, zone = zone, recordsFile = recordsFile, dialect = if (isShared) "shared" else "harness",
            appBuild = appBuild, ring = ring.ifEmpty { null }, source = r.string("source"),
            inputProvenance = r.string("inputProvenance"), inputCaveat = r.string("inputCaveat"), codeParity = r.string("codeParity"),
            inputTruncateAfter = date(r.string("inputTruncateAfter")), inputTruncateReason = r.string("inputTruncateReason"),
            deepHRBaselineBPM = deepHR, temperatures = temps, stored = stored, label = label,
        )
    }

    /** The edit overlay, or null unless all three anchors are present (read in order, as upstream's `if let` chain). */
    private fun edit(e: ReplayJson.Obj): ReplayNight.Edit? {
        val b = date(e.string("inBedStart")) ?: return null
        val o = date(e.string("sleepOnset")) ?: return null
        val w = date(e.string("sleepWake")) ?: return null
        return ReplayNight.Edit(
            inBedStart = b, sleepOnset = o, sleepWake = w, onsetProvenance = e.string("onsetProvenance"),
            asleepMin = e.long("asleepMin"), awakeMin = e.long("awakeMin"), deepMin = e.long("deepMin"),
            remMin = e.long("remMin"), lightMin = e.long("lightMin"),
            fidelity = e["fidelity"]?.asStringList() ?: emptyList(),
        )
    }

    /** [file] as UTF-8 text; malformed bytes THROW (as Foundation's `String(contentsOf:encoding:)`), never become U+FFFD. */
    fun readUtf8(file: File): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(file.readBytes()))
            .toString()

    /**
     * Foundation's `Data(base64Encoded:options: .ignoreUnknownCharacters)`, measured and reproduced
     * (including on 3000 random strings): every character outside the base64 alphabet and `=` is
     * ignored; a final group without its padding is refused; after a padded group anything further
     * is ignored; padding that begins a group is accepted only after data and only if nothing but more
     * padding follows; and text holding nothing BUT ignored characters decodes to empty only when its
     * UTF-8 length is a multiple of four (otherwise it is refused). Null = refused.
     */
    fun decodeBase64(text: String): ByteArray? {
        val out = ByteArrayOutputStream(text.length * 3 / 4)
        val chars = text.filter { it == '=' || base64Value(it) >= 0 }
        if (chars.isEmpty()) return if (text.toByteArray(Charsets.UTF_8).size % 4 == 0) ByteArray(0) else null
        val quad = IntArray(4)
        var n = 0
        var sawData = false
        var i = 0
        while (i < chars.length) {
            val c = chars[i]
            if (c != '=') {
                quad[n++] = base64Value(c)
                if (n == 4) {
                    out.write((quad[0] shl 2) or (quad[1] shr 4))
                    out.write(((quad[1] and 0xf) shl 4) or (quad[2] shr 2))
                    out.write(((quad[2] and 0x3) shl 6) or quad[3])
                    n = 0
                    sawData = true
                }
                i++
                continue
            }
            when (n) {
                0 -> return if (sawData && chars.substring(i).all { it == '=' }) out.toByteArray() else null
                1 -> return null
                2 -> {
                    if (chars.getOrNull(i + 1) != '=') return null
                    out.write((quad[0] shl 2) or (quad[1] shr 4))
                    return out.toByteArray()
                }
                else -> {
                    out.write((quad[0] shl 2) or (quad[1] shr 4))
                    out.write(((quad[1] and 0xf) shl 4) or (quad[2] shr 2))
                    return out.toByteArray()
                }
            }
        }
        return if (n == 0) out.toByteArray() else null
    }

    private fun base64Value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    /**
     * Foundation's `trimmingCharacters(in: .whitespacesAndNewlines)`, measured: Unicode space, line
     * and paragraph separators, U+0009…U+000D, U+0085 and U+200B (which Java files as a format
     * character); not U+001C…U+001F (which Java's `isWhitespace` would trim) nor U+FEFF.
     */
    fun trimWhitespacesAndNewlines(text: String): String {
        fun blank(c: Char): Boolean = c in '\u0009'..'\u000D' || c == '\u0085' || c == '​' ||
            Character.getType(c).toByte().let { it == Character.SPACE_SEPARATOR || it == Character.LINE_SEPARATOR || it == Character.PARAGRAPH_SEPARATOR }
        return text.trim(::blank)
    }

    /** A records file's bytes, read as upstream reads it: UTF-8 text, trimmed, base64-decoded. Null = not base64 Foundation accepts. */
    fun recordsBytes(file: File): ByteArray? = decodeBase64(trimWhitespacesAndNewlines(readUtf8(file)))

    /**
     * The records file → `BulkRecord`s, through the decoder the app uses for an archive blob; a
     * trailing partial record is dropped, exactly as on device. Honours `inputTruncateAfter`.
     */
    fun loadRecords(night: ReplayNight, dir: File): List<BulkRecord> {
        if (night.recordsFile.isEmpty()) throw ReplayError.NoRecords(night.id)
        val data = try {
            recordsBytes(File(dir, night.recordsFile))
        } catch (e: CharacterCodingException) {
            throw ReplayError.UnreadableRecords(night.id, "not UTF-8 text (${e.message})")
        } catch (e: IOException) {
            throw ReplayError.UnreadableRecords(night.id, e.toString())
        } ?: throw ReplayError.BadBase64(night.id)
        val all = EpochArchive.decode(data)
        val cut = night.inputTruncateAfter ?: return all
        return all.filter { !it.date().isAfter(cut) }
    }

    // MARK: The pipeline

    /** What [stage] hands back: the staged segments and the two record sets it staged from. */
    data class Staged(val segments: List<SleepSegment>, val union: List<BulkRecord>, val nightRecords: List<BulkRecord>)

    /**
     * Stage one night exactly as the app does (see the parity block at the top of this file), with
     * every calendar question judged in [zone]. [observedGapCoverageCut] is threaded through so a
     * candidate can be A/B'd against the shipped default on the same bytes.
     */
    fun stage(
        records: List<BulkRecord>,
        zone: ZoneId,
        temperatures: List<TemperatureSample> = emptyList(),
        deepHRBaseline: Double? = null,
        tuning: SleepStaging.Tuning = SleepStaging.Tuning.DEFAULT,
        observedGapCoverageCut: Double = BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT,
        motionPolicy: BulkSleep.MotionChannelPolicy = BulkSleep.MotionChannelPolicy.DEFAULT,
    ): Staged {
        // The archive store's merge, from empty: its sort and its 30 h retention prune.
        val union = EpochArchive.merge(existing = emptyList(), incoming = records)
        val nightRecords = BulkSleep.latestNightRecords(
            union, zone, temperatures = temperatures, observedGapCoverageCut = observedGapCoverageCut, motionPolicy = motionPolicy,
        )
        val baseline = deepHRBaseline?.let { SleepStaging.PersonalBaseline(it) }
        val segs = SleepStaging.classify(nightRecords, temperatures = temperatures, tuning = tuning, baseline = baseline, motionPolicy = motionPolicy)

        // The overnight envelope gate.
        val inBeds = segs.filter { it.stage == SleepStage.IN_BED }
        val lo = inBeds.minOfOrNull { it.start } ?: return Staged(segs, union, nightRecords)
        val hi = inBeds.maxOf { it.end }
        if (SleepWindow.isOvernightBlock(lo, hi, zone)) return Staged(segs, union, nightRecords)
        val onsetIsUnobserved = BulkSleep.onsetIsUnobserved(DateInterval(lo, maxOf(hi, lo)), union) // the UNION, not the slice
        val accepted = SleepWindow.isOvernightBlock(lo, hi, onsetIsUnobserved = onsetIsUnobserved, zone = zone)
        return Staged(if (accepted) segs else emptyList(), union, nightRecords)
    }

    /** Replace the manifest's deep-HR baseline for one measurement; [bpm] null means "no baseline". */
    data class BaselineOverride(val bpm: Double?)

    /**
     * Stage one manifest night and roll it up as the app stores it. The overrides exist for the
     * input-sensitivity sweep; leave them null to use exactly what the manifest declares.
     */
    fun measure(
        night: ReplayNight,
        dir: File,
        temperaturesOverride: List<TemperatureSample>? = null,
        deepHRBaselineOverride: BaselineOverride? = null,
        tuning: SleepStaging.Tuning = SleepStaging.Tuning.DEFAULT,
        observedGapCoverageCut: Double = BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT,
        motionPolicy: BulkSleep.MotionChannelPolicy = BulkSleep.MotionChannelPolicy.DEFAULT,
    ): ReplayResult {
        val records = loadRecords(night, dir)
        val temps = temperaturesOverride ?: night.temperatureSamples()
        val baseline = if (deepHRBaselineOverride != null) deepHRBaselineOverride.bpm else night.deepHRBaselineBPM
        val staged = stage(records, night.zone, temps, baseline, tuning, observedGapCoverageCut, motionPolicy)
        val segs = staged.segments
        val summary = SleepStaging.summary(segs)
        val sleep = SleepStaging.sleepWindow(segs)
        val m = summary.minutes
        return ReplayResult(
            night = night,
            recordsLoaded = records.size,
            recordsAfterRetention = staged.union.size,
            nightScopedRecords = staged.nightRecords.size,
            segments = segs,
            inBedStart = segs.minOfOrNull { it.start },
            inBedEnd = segs.maxOfOrNull { it.end },
            onset = sleep?.onset,
            wake = sleep?.wake,
            inBedMin = m.inBed, asleepMin = m.asleep, awakeMin = m.awake, deepMin = m.deep, remMin = m.rem, lightMin = m.light,
            efficiency = summary.efficiency,
        )
    }

    // MARK: Numbers and time

    /** `b − a` in seconds (nanosecond-exact difference, then a Double). */
    fun secondsBetween(a: Instant, b: Instant): Double = SleepStaging.seconds(Duration.between(a, b))

    /** Seconds since 1970 as a Double (Foundation's `timeIntervalSince1970`). */
    fun epochSeconds(t: Instant): Double = t.epochSecond + t.nano / 1e9

    /**
     * C's `%.Nf` (what Foundation's `String(format:)` prints): the exact binary value rounded half to
     * even, the sign of a negative that rounds to zero kept, `nan` / `inf` / `-inf` for non-finite
     * values (a BigDecimal would throw there). [width] right-aligns, [plus] forces a sign (`%+.Nf`).
     * Locale-free by construction.
     */
    fun fixed(v: Double, digits: Int, width: Int = 0, plus: Boolean = false): String {
        val body = when {
            v.isNaN() -> "nan"
            v.isInfinite() -> if (v > 0) "inf" else "-inf"
            else -> {
                val s = BigDecimal(v).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
                val negative = v < 0 || (v == 0.0 && 1.0 / v < 0)
                if (negative && !s.startsWith("-")) "-$s" else s
            }
        }
        val signed = if (plus && !body.startsWith("-")) "+$body" else body
        return signed.padStart(width)
    }

    /** Swift `Double(String)` for decimal text and `inf`/`infinity`/`nan` (any case, optional sign); hex floats are not read. */
    fun swiftDouble(text: String): Double? {
        val lower = text.lowercase(Locale.ROOT)
        val unsigned = lower.removePrefix("+").removePrefix("-")
        val negative = lower.startsWith("-")
        return when {
            unsigned == "inf" || unsigned == "infinity" -> if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            unsigned == "nan" -> Double.NaN
            DECIMAL.matches(text) -> text.toDouble()
            else -> null
        }
    }

    private val DECIMAL = Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?")
    private val INTEGER = Regex("[+-]?[0-9]+")

    /** Swift `Int(String)`: ASCII digits with an optional sign; null outside the Kotlin `Int` range. */
    fun swiftInt(text: String): Int? {
        if (!INTEGER.matches(text)) return null
        val l = text.toLongOrNull() ?: return null
        return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else null
    }

    /**
     * A JSON value printed the way upstream's baseline emitter prints a manifest field: booleans as
     * `true`/`false`, integers (and integral doubles) whole, other numbers `%.4f`, strings as they are,
     * anything else empty.
     */
    fun num(v: ReplayJson.Value?): String = when (v) {
        null -> ""
        is ReplayJson.Bool -> if (v.value) "true" else "false"
        is ReplayJson.Number -> v.asLong()?.toString() ?: v.asDouble()?.let { fixed(it, 4) } ?: ""
        is ReplayJson.Str -> v.value
        else -> ""
    }

    /** Best-effort `"\(element)"` of a JSON array element (strings as they are, booleans 1/0, numbers whole when integral). */
    fun describe(v: ReplayJson.Value): String = when (v) {
        is ReplayJson.Str -> v.value
        is ReplayJson.Bool -> if (v.value) "1" else "0"
        is ReplayJson.Number -> v.asLong()?.toString() ?: v.asDouble()?.toString() ?: v.text
        ReplayJson.Null -> "<null>"
        else -> ""
    }

    /**
     * The identifier Foundation prints for a zone: `GMT` for UTC, `GMT±HHMM` for a fixed offset
     * (rounded to the minute, as measured), the region identifier otherwise.
     */
    fun foundationIdentifier(zone: ZoneId): String {
        val fixedOffset: ZoneOffset? = when {
            zone is ZoneOffset -> zone
            zone.id == "UTC" || zone.id == "GMT" -> ZoneOffset.UTC
            zone.rules.isFixedOffset && (zone.id.startsWith("UTC") || zone.id.startsWith("GMT") || zone.id.startsWith("UT")) ->
                zone.rules.getOffset(Instant.EPOCH)
            else -> null
        }
        if (fixedOffset == null) return zone.id
        val minutes = roundHalfAwayFromZero(fixedOffset.totalSeconds / 60.0).toLong()
        if (minutes == 0L) return "GMT"
        val a = kotlin.math.abs(minutes)
        return "GMT" + (if (minutes < 0) "-" else "+") + (a / 60).toString().padStart(2, '0') + (a % 60).toString().padStart(2, '0')
    }

    // MARK: Reporting

    private val clockFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)
    private val isoFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    /** `MM-dd HH:mm:ss` in [zone], or `—` for no instant. */
    fun clock(d: Instant?, zone: ZoneId): String = d?.let { clockFormat.format(it.atZone(zone)) } ?: "—"

    /** An internet date-time in [zone] (`2026-08-04T07:36:34+02:00`, `…Z` at offset zero), or empty for no instant. */
    fun iso(d: Instant?, zone: ZoneId): String = d?.let { isoFormat.format(it.atZone(zone)) } ?: ""

    /** Swift `padding(toLength:withPad:" ",startingAt:0)`: pad with spaces, or cut, to exactly [n]. */
    fun pad(s: String, n: Int): String = if (s.length >= n) s.substring(0, n) else s + " ".repeat(n - s.length)

    private val columns = listOf(
        "night" to 22, "inBedStart" to 14, "inBedEnd" to 14, "onset" to 14, "wake" to 14,
        "inBed" to 5, "aslp" to 5, "awk" to 4, "deep" to 4, "rem" to 4, "light" to 5, "eff" to 5,
        "dStart" to 7, "dEnd" to 6, "dOnset" to 7, "dWake" to 6,
    )

    fun header(): String {
        val line = columns.joinToString(" ") { (name, width) -> pad(name, width) }
        return line + "\n" + "-".repeat(line.length)
    }

    fun row(r: ReplayResult): String {
        fun right(s: String, n: Int): String = if (s.length >= n) s else " ".repeat(n - s.length) + s
        fun signed(v: Long?): String = v?.let { (if (it > 0) "+" else "") + it } ?: "·"
        val zone = r.zone
        return listOf(
            pad(r.id, 22),
            pad(clock(r.inBedStart, zone), 14), pad(clock(r.inBedEnd, zone), 14),
            pad(clock(r.onset, zone), 14), pad(clock(r.wake, zone), 14),
            right(r.inBedMin.toString(), 5), right(r.asleepMin.toString(), 5),
            right(r.awakeMin.toString(), 4), right(r.deepMin.toString(), 4),
            right(r.remMin.toString(), 4), right(r.lightMin.toString(), 5),
            right(fixed(r.efficiency, 3), 5),
            right(signed(r.storedStartDeltaMin), 7), right(signed(r.storedEndDeltaMin), 6),
            right(signed(r.labelOnsetErrorMin), 7), right(signed(r.labelWakeErrorMin), 6),
        ).joinToString(" ")
    }

    fun legend(): String =
        "dStart/dEnd  = detected in-bed edge MINUS the app's stored RECORDED edge, signed minutes " +
            "(+ = later than the app placed it). '·' = the corpus row carries no stored window.\n" +
            "dOnset/dWake = detected sleep onset/wake MINUS the GROUND-TRUTH label, signed minutes " +
            "(+ = we placed it late). Labels are a biased sample — see SleepEditLabel.kt.\n" +
            "inBed/aslp/awk/deep/rem/light are whole minutes from SleepStaging.Summary.minutes; " +
            "eff = SleepStaging.Summary.efficiency."
}
