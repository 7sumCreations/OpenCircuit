package io.github.opencircuit.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The comparison the migration tests rely on must notice every way a row can be lost: a table
 * gone, a row gone, a duplicate gone, one cell changed in value or in storage class.
 */
class TableSnapshotTest {

    private val before = mapOf(
        "t" to listOf(
            mapOf("id" to "integer 1", "v" to "integer 7"),
            mapOf("id" to "integer 2", "v" to "text a"),
            mapOf("id" to "integer 2", "v" to "text a"),
        ),
    )

    @Test
    fun anIdenticalOrWiderCopyLosesNothing() {
        assertEquals(emptyList(), TableSnapshot.lostRows(before, before))
        // A column and a row added by a later version are not losses.
        val wider = mapOf("t" to before.getValue("t").map { it + ("added" to "null") } + mapOf("id" to "integer 3", "v" to "null"))
        assertEquals(emptyList(), TableSnapshot.lostRows(before, wider))
    }

    @Test
    fun aMissingTableRowDuplicateOrChangedCellIsALoss() {
        assertEquals(listOf("table t is gone"), TableSnapshot.lostRows(before, emptyMap()))
        val rows = before.getValue("t")
        assertEquals(1, TableSnapshot.lostRows(before, mapOf("t" to rows.drop(1))).size, "a row dropped")
        assertEquals(1, TableSnapshot.lostRows(before, mapOf("t" to rows.dropLast(1))).size, "one of two duplicates dropped")
        val changedValue = listOf(mapOf("id" to "integer 1", "v" to "integer 8")) + rows.drop(1)
        assertEquals(1, TableSnapshot.lostRows(before, mapOf("t" to changedValue)).size, "a value changed")
        val changedClass = listOf(mapOf("id" to "integer 1", "v" to "real 7.0")) + rows.drop(1)
        assertEquals(1, TableSnapshot.lostRows(before, mapOf("t" to changedClass)).size, "a storage class changed")
    }

    @Test
    fun aSnapshotReadsEveryStorageClassExactlyAndSkipsBookkeeping() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            connection.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, i INTEGER, r REAL, s TEXT, b BLOB, n TEXT)")
            connection.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            connection.execSQL("INSERT INTO room_master_table VALUES (42, 'x')")
            connection.execSQL("INSERT INTO t (i, r, s, b, n) VALUES (-62135769600000, 0.1, 'é''s', x'00ff10', NULL)")
            assertEquals(
                mapOf(
                    "t" to listOf(
                        mapOf(
                            "id" to "integer 1", "i" to "integer -62135769600000", "r" to "real 0.1",
                            "s" to "text é's", "b" to "blob 00ff10", "n" to "null",
                        ),
                    ),
                ),
                TableSnapshot.of(connection),
            )
        } finally {
            connection.close()
        }
    }
}
