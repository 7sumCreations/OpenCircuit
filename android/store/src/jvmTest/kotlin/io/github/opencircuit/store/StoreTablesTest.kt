package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * What the daily-rollup, step-sample, daytime-temperature and key-value tables refuse or keep.
 *
 * Upstream `StoredDaily.day` is `@Attribute(.unique)` (ios/OpenCircuit/Store/LocalStore.swift:649
 * @ b1c2fdd): one rollup row per day. The key-value table holds every stored blob and latch; a row
 * with no value would be a blob that is neither present nor absent, so the column refuses NULL.
 * The 30-day prune deletes by time on three tables, so each has an index on that column.
 */
class StoreTablesTest {

    private val day = Instant.parse("2026-10-02T00:00:00Z")

    @Test
    fun aSecondDailyRowForTheSameDayIsRefusedAndTheFirstIsKept() = runBlocking {
        withInMemoryStore { db ->
            db.dailyDao().insertDaily(StoredDailyEntity(day = day, steps = 120, updatedAt = day))

            assertFails { db.dailyDao().insertDaily(StoredDailyEntity(day = day, steps = 999, updatedAt = day)) }

            assertEquals(listOf(120L), db.dailyDao().allDailies().map { it.steps })
        }
    }

    @Test
    fun aKeyValueRowWithoutAValueIsRefused() = runBlocking {
        withInMemoryStore { db ->
            assertFails { db.execRaw("INSERT INTO store_kv (`key`, value, updated_at) VALUES ('k', NULL, 0)") }

            assertEquals(emptyList(), db.queryRaw("SELECT `key` FROM store_kv"))
        }
    }

    @Test
    fun aKeyValueRowIsReplacedNotDuplicated() = runBlocking {
        withInMemoryStore { db ->
            db.kvDao().upsert(StoreKvEntity("k", "1", day))
            db.kvDao().upsert(StoreKvEntity("k", "2", day.plusSeconds(1)))

            assertEquals(listOf("k|2|${day.plusSeconds(1).toEpochMilli()}"), db.queryRaw("SELECT * FROM store_kv"))
        }
    }

    @Test
    fun eachPruneDeleteUsesAnIndexOnItsTimeColumn() = runBlocking {
        withInMemoryStore { db ->
            for ((table, column) in listOf("stored_sample" to "start", "stored_daytime_temp" to "time", "stored_step_sample" to "start")) {
                val plan = db.queryRaw("EXPLAIN QUERY PLAN DELETE FROM $table WHERE $column < 0").joinToString("\n")
                assertTrue("USING INDEX" in plan || "USING COVERING INDEX" in plan, "$table prune scans the table: $plan")
            }
        }
    }
}
