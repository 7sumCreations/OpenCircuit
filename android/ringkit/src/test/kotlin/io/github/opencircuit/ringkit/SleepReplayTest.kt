package io.github.opencircuit.ringkit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * COMMAND-LINE ENTRY POINTS for the sleep replay harness (see `SleepReplay.kt` for the
 * production-parity contract this rests on). From `android/`:
 *
 *     # measure every night in the corpus and print the table (the SLEEP corpus variable)
 *     <SLEEP corpus variable>=<corpus-dir> ./gradlew --no-daemon :ringkit:test --rerun \
 *         --tests "io.github.opencircuit.ringkit.SleepReplayTest.measureCorpus" -i
 *
 *     # the fidelity proof and the input-sensitivity sweep (the FIDELITY corpus variable)
 *     <FIDELITY corpus variable>=<fidelity-dir> ./gradlew --no-daemon :ringkit:test --rerun \
 *         --tests "io.github.opencircuit.ringkit.SleepReplayTest*" -i
 *
 * The variable names are the entries of `SleepReplay.Corpus` (upstream's names, unchanged).
 * With neither set every test SKIPS **LOUDLY** — `SleepReplay.requireCorpus` aborts through a JUnit
 * assumption, so JUnit reports "skipped", never "passed". These entry points used to `return` early
 * upstream, which scores as a PASS, so the fidelity *proof* printed a green tick having asserted
 * nothing on every machine without a corpus. Nothing here reads or writes the repository.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepReplayTests.swift
 * (@ b1c2fdd) — its 3 tests. Upstream holds them in three classes (measure, fidelity,
 * input-sensitivity); here they are one class, so the file keeps one name and its count.
 */
class SleepReplayTest {

    // MARK: - Measure

    @Test
    fun measureCorpus() {
        val dir = SleepReplay.requireCorpus(SleepReplay.Corpus.SLEEP, purpose = "the corpus measurement table (SleepReplayTest.measureCorpus)")
        val nights = SleepReplay.loadManifest(dir)
        val results = mutableListOf<ReplayResult>()
        val failures = mutableListOf<String>()
        var summaryOnly = 0
        for (n in nights) {
            try {
                results += SleepReplay.measure(n, dir)
            } catch (_: SleepReplay.ReplayError.NoRecords) {
                summaryOnly += 1
            } catch (e: Exception) {
                failures += "${n.id}: $e"
            }
        }

        println("\n=== SLEEP REPLAY — ${dir.path}")
        println("=== ${nights.size} manifest rows: ${results.size} replayable, $summaryOnly summary-only (no records), ${failures.size} failed to load\n")
        println(SleepReplay.header())
        for (r in results.sortedBy { it.id }) println(SleepReplay.row(r))
        println("\n" + SleepReplay.legend())

        // Aggregates over the labelled subset only, stated as what they are.
        val onsetErrs = results.mapNotNull { it.labelOnsetErrorMin }
        val wakeErrs = results.mapNotNull { it.labelWakeErrorMin }
        fun stats(v: List<Long>, name: String): String {
            if (v.isEmpty()) return "$name: no labelled nights"
            val sortedAbs = v.map { abs(it) }.sorted()
            val mae = sortedAbs.sum().toDouble() / sortedAbs.size
            val bias = v.sum().toDouble() / v.size
            return "$name: n=${v.size}  median|err|=${sortedAbs[sortedAbs.size / 2]} min  MAE=${SleepReplay.fixed(mae, 1)} min  " +
                "worst=${sortedAbs.last()} min  bias=${SleepReplay.fixed(bias, 1, plus = true)} min"
        }
        println("\n" + stats(onsetErrs, "onset vs label"))
        println(stats(wakeErrs, "wake  vs label"))
        println("⚠️  Labelled nights are the nights someone thought were WRONG. These are \"how wrong when wrong\", never overall accuracy.")

        val staged = results.filter { it.inBedStart != null }
        println("\nstaged a night: ${staged.size}/${results.size}   returned nothing: ${results.size - staged.size}")
        if (failures.isNotEmpty()) {
            println("\nLOAD FAILURES (${failures.size}):")
            for (f in failures) println("  $f")
        }
        // A corpus that stages nothing at all means the harness is broken, not the data.
        assertFalse(results.isEmpty(), "the corpus produced no measurable nights at all")
    }

    // MARK: - Fidelity

    /**
     * THE PROOF. Asserts — per night, per field — that replaying the stored bytes returns what the
     * app actually persisted. Only fields the manifest lists in `stored.fidelity` are asserted; a row
     * whose input provably differs from what staging saw declares an EMPTY list and is reported, never
     * asserted.
     *
     * ⚠️ REPLAYED AT `observedGapCoverageCut = 0`, DELIBERATELY. Every stored value in upstream's
     * fidelity corpus was persisted by a build that PREDATES the observed-gap guard, so the parity
     * question ("does the harness transcribe the production path?") must run the code the fixtures
     * came out of. Upstream measured what that hides: at the shipped default its fidelity corpus fails
     * 4 assertions on one night (the intended behaviour change) and passes the other four. A green
     * run here therefore does NOT say the guard reproduces production.
     */
    @Test
    fun reproducesStoredWindowAndMinutes() {
        val dir = SleepReplay.requireCorpus(
            SleepReplay.Corpus.FIDELITY,
            purpose = "the production-parity FIDELITY PROOF (SleepReplayTest.reproducesStoredWindowAndMinutes)",
            consequence = "This is the single check that says the harness reproduces what the app actually stored; " +
                "unrun, every measurement taken with the harness is unbacked.",
        )
        val nights = SleepReplay.loadManifest(dir)
        var asserted = 0
        println("\n=== SLEEP REPLAY FIDELITY — ${dir.path}")
        println(
            "=== replayed at observedGapAbsorbCoverageCut = $FIXTURE_PROVENANCE_CUT (the fixtures' provenance; shipped default is " +
                "${BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT})",
        )
        if (FIXTURE_PROVENANCE_CUT != BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT) {
            println("=== ⚠️  this proves the HARNESS matches the PRE-GUARD production path. It says NOTHING about whether the shipped default matches a device.\n")
        }

        for (n in nights) {
            val r = SleepReplay.measure(n, dir, observedGapCoverageCut = FIXTURE_PROVENANCE_CUT)
            val zone = n.zone
            val tol = n.stored.windowPrecisionSec.toDouble()
            println(
                "--- ${n.id}   build ${n.appBuild ?: "?"}  ${n.ring ?: ""}  ${n.inputProvenance ?: "?"}  records ${r.recordsLoaded}" +
                    " → after 30 h retention ${r.recordsAfterRetention} → night-scoped ${r.nightScopedRecords}",
            )
            println("    parity: ${n.codeParity ?: "unstated"}")
            if (n.stored.fidelity.isEmpty()) println("    NOT A FIDELITY TARGET — ${n.inputCaveat ?: "no reason recorded"}")
            fun line(name: String, got: String, want: String, ok: Boolean?) {
                val mark = when (ok) { null -> "   "; true -> " ✓ "; false -> " ✗ " }
                println("   $mark${SleepReplay.pad(name, 12)} harness ${SleepReplay.pad(got, 20)} stored $want")
            }

            // --- window
            for ((field, got, want) in listOf(
                Triple("inBedStart", r.inBedStart, n.stored.inBedStart),
                Triple("inBedEnd", r.inBedEnd, n.stored.inBedEnd),
            )) {
                if (want == null) continue
                val assertThis = field in n.stored.fidelity
                val ok = got?.let { abs(SleepReplay.secondsBetween(want, it)) <= tol }
                line(field, SleepReplay.clock(got, zone), SleepReplay.clock(want, zone), if (assertThis) (ok ?: false) else null)
                if (!assertThis) continue
                asserted += 1
                val g = assertNotNull(got, "${n.id}: staging returned NO $field at all")
                assertEquals(
                    SleepReplay.epochSeconds(want), SleepReplay.epochSeconds(g), tol,
                    "${n.id}.$field: harness ${SleepReplay.clock(g, zone)} vs stored ${SleepReplay.clock(want, zone)} " +
                        "(Δ ${ReplayResult.delta(g, want)} min). The harness does NOT reproduce production for this night — " +
                        "no measurement taken with it can be trusted until this is explained.",
                )
            }

            // --- minutes
            for ((field, got, want) in listOf(
                Triple("asleepMin", r.asleepMin, n.stored.asleepMin),
                Triple("awakeMin", r.awakeMin, n.stored.awakeMin),
                Triple("deepMin", r.deepMin, n.stored.deepMin),
                Triple("remMin", r.remMin, n.stored.remMin),
                Triple("lightMin", r.lightMin, n.stored.lightMin),
            )) {
                if (want == null) continue
                val assertThis = field in n.stored.fidelity
                line(field, got.toString(), want.toString(), if (assertThis) got == want else null)
                if (!assertThis) continue
                asserted += 1
                assertEquals(want, got, "${n.id}.$field: harness $got vs stored $want (Δ ${got - want} min)")
            }
            val note = n.stored.minutesNote
            if (note != null && n.stored.asleepMin == null) println("        (minutes: $note)")

            // --- the EDIT overlay: an edited night stores SleepEdit.recompute's minutes, not staging's.
            val e = n.stored.edit
            if (e != null) {
                val times = SleepEdit.Times(e.inBedStart, e.sleepOnset, e.sleepWake)
                val m = SleepStaging.summary(SleepEdit.recompute(r.segments, times)).minutes
                println(
                    "    edit overlay: SleepEdit.recompute(bed ${SleepReplay.clock(e.inBedStart, zone)}, " +
                        "onset ${SleepReplay.clock(e.sleepOnset, zone)}, wake ${SleepReplay.clock(e.sleepWake, zone)})",
                )
                println("       onset provenance: ${e.onsetProvenance ?: "unstated"}")
                for ((field, got, want) in listOf(
                    Triple("asleepMin", m.asleep, e.asleepMin),
                    Triple("awakeMin", m.awake, e.awakeMin),
                    Triple("deepMin", m.deep, e.deepMin),
                    Triple("remMin", m.rem, e.remMin),
                    Triple("lightMin", m.light, e.lightMin),
                )) {
                    if (want == null) continue
                    val assertThis = field in e.fidelity
                    line("edit.$field", got.toString(), want.toString(), if (assertThis) got == want else null)
                    if (!assertThis) continue
                    asserted += 1
                    assertEquals(want, got, "${n.id}.edit.$field: harness $got vs stored $want (Δ ${got - want} min)")
                }
            }
            println("")
        }
        assertTrue(asserted > 0, "no manifest row declared any fidelity target — the proof is vacuous")
        println("=== asserted $asserted field(s) across ${nights.size} corpus night(s)")
    }

    // MARK: - Input sensitivity

    /**
     * The harness cannot reconstruct two of production's inputs exactly (the session temperature
     * log and the personal deep-HR baseline). Rather than assume they are inert, measure it: if a
     * sweep moves a number, that number carries an error bar and every later claim must say so.
     */
    @Test
    fun temperatureAndBaselineSensitivity() {
        val dir = SleepReplay.requireCorpus(SleepReplay.Corpus.FIDELITY, purpose = "the input-sensitivity sweep (SleepReplayTest.temperatureAndBaselineSensitivity)")
        val nights = SleepReplay.loadManifest(dir)
        println("\n=== INPUT SENSITIVITY — the two inputs a .b64 file cannot carry\n")

        for (n in nights) {
            val manifestTemps = n.temperatureSamples()
            val withTemps = SleepReplay.measure(n, dir, temperaturesOverride = manifestTemps)
            val noTemps = SleepReplay.measure(n, dir, temperaturesOverride = emptyList())
            println("--- ${n.id}  (${manifestTemps.size} reconstructed temperature samples)")
            println("    wearTemperatureSamples()=manifest : " + SleepReplay.row(withTemps))
            println("    wearTemperatureSamples()=[]       : " + SleepReplay.row(noTemps))
            println("    temperature-inert: ${same(withTemps, noTemps)}")

            for (b in listOf(null, 45.0, 50.0, 55.0, 60.0, 65.0, 70.0)) {
                val r = SleepReplay.measure(n, dir, deepHRBaselineOverride = SleepReplay.BaselineOverride(b))
                println(SleepReplay.pad("    deepHRBaseline=${b?.let { SleepReplay.fixed(it, 0) } ?: "nil"}", 28) + ": " + SleepReplay.row(r))
            }
            println("")
        }
    }

    private fun same(a: ReplayResult, b: ReplayResult): Boolean =
        a.inBedStart == b.inBedStart && a.inBedEnd == b.inBedEnd && a.onset == b.onset && a.wake == b.wake &&
            a.asleepMin == b.asleepMin && a.awakeMin == b.awakeMin && a.deepMin == b.deepMin && a.remMin == b.remMin &&
            a.lightMin == b.lightMin

    private companion object {
        /** The cut the stored fixtures were produced at. Bump only alongside re-captured fixtures. */
        const val FIXTURE_PROVENANCE_CUT: Double = 0.0
    }
}
