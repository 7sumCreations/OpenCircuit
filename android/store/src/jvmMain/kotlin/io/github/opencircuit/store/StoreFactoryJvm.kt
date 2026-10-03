package io.github.opencircuit.store

import androidx.room3.Room
import java.nio.file.Path

/**
 * Opens (creating if absent) the database file at [path]. Throws if the file cannot be opened as
 * this store; the file is left untouched.
 */
suspend fun StoreFactory.openFile(path: Path): StoreDatabase =
    openWith(Room.databaseBuilder<StoreDatabase>(name = path.toString()))

/** Opens a fresh database that lives in memory until it is closed. */
suspend fun StoreFactory.openInMemory(): StoreDatabase =
    openWith(Room.inMemoryDatabaseBuilder<StoreDatabase>())
