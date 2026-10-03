package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The five user-data and sleep tables carry every column of upstream's live (V7) models, with
 * upstream's defaults, and each refuses a second row for its natural key.
 *
 * The expected lists are transcribed property by property from upstream (@ b1c2fdd):
 * `StoredSleepSummary` (ios/OpenCircuit/Store/LocalStore.swift:86-230, 41 stored properties),
 * `StoredNap` (:695-746, 14), `StoredPeriodEntry` (ios/OpenCircuit/CycleStore.swift:15-41),
 * `StoredHeadacheEntry` and `StoredHeadacheRisk` (ios/OpenCircuit/Store/HeadacheStore.swift:30-61,
 * :126-160) — not copied from the entities. Mapping: a Swift `Date` is whole epoch milliseconds,
 * `Date.distantPast` ([SleepEdit.DISTANT_PAST]) is its default; `Bool` is 0 / 1; `[String]` / `[Int]`
 * is a JSON array in text; `Data` is a blob; an optional is a nullable column with no default.
 * Upstream's `updatedAt = Date()` / `computedAt = Date()` read the clock; here the default is
 * `distantPast` and every write states its time.
 */
class StoredUserTablesColumnsTest {

    /** One column: upstream property name (for the reader), column name, SQL type, NOT NULL, default. */
    private data class Col(val upstream: String, val name: String, val type: String, val notNull: Boolean, val default: String?)

    private fun date(upstream: String, name: String) = Col(upstream, name, "INTEGER", true, DP)
    private fun optDate(upstream: String, name: String) = Col(upstream, name, "INTEGER", false, null)
    private fun int(upstream: String, name: String, d: String = "0") = Col(upstream, name, "INTEGER", true, d)
    private fun bool(upstream: String, name: String) = Col(upstream, name, "INTEGER", true, "0")
    private fun real(upstream: String, name: String, d: String = "0") = Col(upstream, name, "REAL", true, d)
    private fun text(upstream: String, name: String, d: String = "''") = Col(upstream, name, "TEXT", true, d)
    private fun list(upstream: String, name: String) = Col(upstream, name, "TEXT", true, "'[]'")
    private fun data(upstream: String, name: String) = Col(upstream, name, "BLOB", true, "x''")
    private fun optData(upstream: String, name: String) = Col(upstream, name, "BLOB", false, null)

    private val sleepSummary = listOf(
        date("night", "night"),
        int("asleepMin", "asleep_min"), int("deepMin", "deep_min"), int("lightMin", "light_min"),
        int("remMin", "rem_min"), int("awakeMin", "awake_min"),
        real("efficiency", "efficiency"),
        date("inBedStart", "in_bed_start"), date("inBedEnd", "in_bed_end"),
        date("sleepOnset", "sleep_onset"), date("sleepWake", "sleep_wake"), date("updatedAt", "updated_at"),
        real("skinTempC", "skin_temp_c"),
        int("sleepScore", "sleep_score"), int("stressScore", "stress_score"), int("feelScore", "feel_score"),
        int("hrDeep", "hr_deep"), int("hrLight", "hr_light"), int("hrRem", "hr_rem"), int("hrAwake", "hr_awake"),
        list("movementLevels", "movement_levels"),
        data("hypnogramData", "hypnogram_data"),
        real("osaAvgSpO2", "osa_avg_spo2"), real("osaMinSpO2", "osa_min_spo2"),
        real("osaTimeBelow90Sec", "osa_time_below_90_sec"), real("osaODI", "osa_odi"),
        int("osaValidWindows", "osa_valid_windows"),
        date("editedInBedStart", "edited_in_bed_start"), date("editedInBedEnd", "edited_in_bed_end"),
        bool("isManuallyEdited", "is_manually_edited"),
        date("widenedRecordedInBedStart", "widened_recorded_in_bed_start"),
        date("widenedRecordedInBedEnd", "widened_recorded_in_bed_end"),
        date("widenedRecordedOnset", "widened_recorded_onset"),
        date("widenedRecordedWake", "widened_recorded_wake"),
        data("recordedHypnogramData", "recorded_hypnogram_data"),
        real("measuredAsleepSeconds", "measured_asleep_seconds", "-1"),
        real("assertedAsleepSeconds", "asserted_asleep_seconds", "-1"),
        real("coverageFraction", "coverage_fraction", "-1"),
        real("longestGapSeconds", "longest_gap_seconds", "-1"),
        real("measuredEfficiency", "measured_efficiency", "-1"),
        text("sleepBasis", "sleep_basis"),
    )

    private val nap = listOf(
        date("start", "start"), date("end", "end"), int("asleepMin", "asleep_min"),
        bool("isLongNap", "is_long_nap"), bool("healthWritten", "health_written"), date("updatedAt", "updated_at"),
        bool("isManuallyEdited", "is_manually_edited"), bool("isManuallyAdded", "is_manually_added"),
        optData("napSegmentsData", "nap_segments_data"),
        optDate("editedStart", "edited_start"), optDate("editedEnd", "edited_end"),
        optData("recordedNapSegmentsData", "recorded_nap_segments_data"),
        date("healthWrittenStart", "health_written_start"), date("healthWrittenEnd", "health_written_end"),
    )

    private val period = listOf(
        date("start", "start"), optDate("end", "end"), int("flowLevelRaw", "flow_level_raw", "2"),
        list("symptoms", "symptoms"), text("notes", "notes"), bool("healthWritten", "health_written"),
        list("hkSampleUUIDs", "hk_sample_uuids"), date("updatedAt", "updated_at"),
    )

    private val headache = listOf(
        date("onset", "onset"), optDate("end", "end"), int("severityRaw", "severity_raw"),
        list("symptoms", "symptoms"), list("customSymptoms", "custom_symptoms"), list("factors", "factors"),
        text("notes", "notes"), text("sourceRaw", "source_raw", "'user'"),
        Col("importedHKUUID", "imported_hk_uuid", "TEXT", false, null),
        bool("healthWritten", "health_written"), list("hkSampleUUIDs", "hk_sample_uuids"), date("updatedAt", "updated_at"),
    )

    private val risk = listOf(
        date("day", "day"), date("nightKey", "night_key"), real("index", "index"), int("bandRaw", "band_raw"),
        int("ringFeatureCount", "ring_feature_count"), real("coverageFraction", "coverage_fraction"),
        text("contributionsJSON", "contributions_json"), text("absentJSON", "absent_json"),
        date("computedAt", "computed_at"), optDate("sleepUpdatedAt", "sleep_updated_at"),
        bool("sleepRestaged", "sleep_restaged"), bool("alerted", "alerted"), bool("postUnlock", "post_unlock"),
        date("updatedAt", "updated_at"),
    )

    @Test
    fun theTranscriptionCountsMatchUpstream() {
        // The survey counted 41 summary properties (an upstream comment says 40; it is stale).
        assertEquals(listOf(41, 14, 8, 12, 14), listOf(sleepSummary, nap, period, headache, risk).map { it.size })
        assertEquals(DP, SleepEdit.DISTANT_PAST.toEpochMilli().toString())
    }

    @Test
    fun theSleepSummaryTableHasEveryUpstreamColumnWithItsDefault() = runBlocking<Unit> {
        assertColumns("stored_sleep_summary", sleepSummary)
    }

    @Test
    fun theNapTableHasEveryUpstreamColumnWithItsDefault() = runBlocking<Unit> {
        assertColumns("stored_nap", nap)
    }

    @Test
    fun thePeriodTableHasEveryUpstreamColumnWithItsDefault() = runBlocking<Unit> {
        assertColumns("stored_period_entry", period)
    }

    @Test
    fun theHeadacheTableHasEveryUpstreamColumnWithItsDefault() = runBlocking<Unit> {
        assertColumns("stored_headache_entry", headache)
    }

    @Test
    fun theRiskTableHasEveryUpstreamColumnWithItsDefault() = runBlocking<Unit> {
        assertColumns("stored_headache_risk", risk)
    }

    @Test
    fun eachTableIsUniqueOnItsUpstreamKeyAndOnNothingElse() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val expected = mapOf(
                "stored_sleep_summary" to listOf("night"),
                "stored_nap" to listOf("start"),
                "stored_period_entry" to listOf("start"),
                "stored_headache_entry" to listOf("onset"),
                "stored_headache_risk" to listOf("day"),
            )
            for ((table, key) in expected) {
                val unique = db.queryRaw(
                    "SELECT ii.name FROM pragma_index_list('$table') il, pragma_index_info(il.name) ii " +
                        "WHERE il.\"unique\" = 1 AND il.origin = 'c' ORDER BY ii.name",
                )
                assertEquals(key, unique, "$table unique columns")
            }
        }
    }

    @Test
    fun aRowInsertedWithOnlyItsKeyTakesUpstreamsDefaults() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.execRaw("INSERT INTO stored_headache_entry (onset) VALUES (1000)")
            assertEquals(
                listOf("0|[]|[]|[]||user|NULL|0|[]|$DP"),
                db.queryRaw(
                    "SELECT severity_raw, symptoms, custom_symptoms, factors, notes, source_raw, " +
                        "ifnull(imported_hk_uuid, 'NULL'), health_written, hk_sample_uuids, updated_at FROM stored_headache_entry",
                ),
            )
        }
    }

    private suspend fun assertColumns(table: String, expected: List<Col>) {
        withInMemoryStore { db ->
            val actual = db.queryRaw(
                "SELECT name, type, \"notnull\", ifnull(dflt_value, 'NULL') FROM pragma_table_info('$table') " +
                    "WHERE name <> 'id' ORDER BY cid",
            )
            assertEquals(
                expected.map { "${it.name}|${it.type}|${if (it.notNull) 1 else 0}|${it.default ?: "NULL"}" },
                actual,
                "$table columns (upstream order)",
            )
            // The row id is the primary key, and nothing else is.
            assertEquals(listOf("id"), db.queryRaw("SELECT name FROM pragma_table_info('$table') WHERE pk > 0"))
        }
    }

    private companion object {
        /**
         * Foundation's `Date.distantPast` (−62 135 769 600 s since 1970: January 1 of year 1 in the
         * Julian calendar, two days before the proleptic Gregorian `0001-01-01`) in epoch milliseconds.
         */
        const val DP = "-62135769600000"
    }
}
