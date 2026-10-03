package io.github.opencircuit.store

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
