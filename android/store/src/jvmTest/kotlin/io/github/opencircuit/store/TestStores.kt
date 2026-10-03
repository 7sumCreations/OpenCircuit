package io.github.opencircuit.store

import androidx.room3.useWriterConnection

/**
 * Runs [block] against a fresh in-memory database opened through the production [StoreFactory]
 * (bundled SQLite driver, no destructive fallback), and closes it afterwards.
 */
suspend fun <T> withInMemoryStore(block: suspend (StoreDatabase) -> T): T {
    val db = StoreFactory.openInMemory()
    try {
        return block(db)
    } finally {
        db.close()
    }
}

/** Runs one raw SQL statement on the writer connection (fixtures the DAOs cannot express). */
suspend fun StoreDatabase.execRaw(sql: String) {
    useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
}

/** Every row of a raw query, each row's columns read as text and joined by `|`. */
suspend fun StoreDatabase.queryRaw(sql: String): List<String> =
    useWriterConnection { connection ->
        connection.usePrepared(sql) { statement ->
            buildList {
                while (statement.step()) {
                    add((0 until statement.getColumnCount()).joinToString("|") { statement.getText(it) })
                }
            }
        }
    }
