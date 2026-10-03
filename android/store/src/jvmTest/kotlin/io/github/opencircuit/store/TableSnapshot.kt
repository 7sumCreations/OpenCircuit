package io.github.opencircuit.store

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * Every row of every data table of a database, each cell as its SQLite storage class and its exact
 * value (`integer 7`, `real 0.25`, `text abc`, `blob 0a1b`, `null`). The migration tests take one
 * before closing a database and one after reopening it, and [lostRows] compares them by content.
 */
internal object TableSnapshot {

    /** One row: column name to its cell. */
    typealias Row = Map<String, String>

    /** Room's and SQLite's own bookkeeping tables, which hold no user data. */
    private val bookkeeping = setOf("room_master_table", "sqlite_sequence", "android_metadata")

    private const val TABLES = "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"

    /** A snapshot taken over a raw connection (the migration helper's database). */
    fun of(connection: SQLiteConnection): Map<String, List<Row>> {
        val tables = connection.prepare(TABLES).use(::rows).map(::tableName).filter { it !in bookkeeping }
        return tables.associateWith { table -> connection.prepare("SELECT * FROM `$table`").use(::rows) }
    }

    /** A snapshot taken over the store's own writer connection. */
    suspend fun of(db: StoreDatabase): Map<String, List<Row>> =
        db.useWriterConnection { connection ->
            val tables = connection.usePrepared(TABLES, ::rows).map(::tableName).filter { it !in bookkeeping }
            tables.associateWith { table -> connection.usePrepared("SELECT * FROM `$table`", ::rows) }
        }

    private fun tableName(row: Row): String = row.getValue("name").removePrefix("text ")

    private fun rows(statement: SQLiteStatement): List<Row> = buildList {
        while (statement.step()) {
            add((0 until statement.getColumnCount()).associate { statement.getColumnName(it) to cell(statement, it) })
        }
    }

    private fun cell(statement: SQLiteStatement, index: Int): String = when (val type = statement.getColumnType(index)) {
        SQLITE_DATA_INTEGER -> "integer ${statement.getLong(index)}"
        SQLITE_DATA_FLOAT -> "real ${statement.getDouble(index)}"
        SQLITE_DATA_TEXT -> "text ${statement.getText(index)}"
        SQLITE_DATA_BLOB -> "blob " + statement.getBlob(index).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        SQLITE_DATA_NULL -> "null"
        else -> error("unknown SQLite storage class $type")
    }

    /**
     * What [after] lost of [before]: a table that is gone, or a row of [before] with no row in
     * [after] holding the same cell in every one of its columns. Rows are matched one to one, so a
     * dropped duplicate is a loss too. Columns or rows that [after] adds are not losses (a later
     * migration may add both).
     */
    fun lostRows(before: Map<String, List<Row>>, after: Map<String, List<Row>>): List<String> =
        before.flatMap { (table, rows) ->
            val remaining = after[table]?.toMutableList() ?: return@flatMap listOf("table $table is gone")
            rows.mapNotNull { row ->
                val match = remaining.indexOfFirst { candidate -> row.all { (column, cell) -> candidate[column] == cell } }
                if (match < 0) {
                    "$table: row lost or changed: $row"
                } else {
                    remaining.removeAt(match)
                    null
                }
            }
        }
}
