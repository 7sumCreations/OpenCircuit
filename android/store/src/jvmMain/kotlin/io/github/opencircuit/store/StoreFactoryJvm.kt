package io.github.opencircuit.store

import androidx.room3.Room
import java.nio.file.Path

/** Opens (creating if absent) the database file at [path]. */
fun StoreFactory.openFile(path: Path): StoreDatabase =
    openWith(Room.databaseBuilder<StoreDatabase>(name = path.toString()))

/** Opens a fresh database that lives in memory until it is closed. */
fun StoreFactory.openInMemory(): StoreDatabase =
    openWith(Room.inMemoryDatabaseBuilder<StoreDatabase>())
