package io.github.opencircuit.store

import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The one way to open the store. Each target adds its entry points (a file or in-memory database
 * on the JVM, a `Context` on Android); all of them share [openWith].
 *
 * Port of upstream's `makeContainerOrThrow` (ios/OpenCircuit/App.swift:482 @ b1c2fdd), which
 * never wipes. Upstream's recovery paths that delete the store and restore a partial backup are
 * not ported: a failed open is the caller's to handle, and the file is left as it was.
 */
object StoreFactory {

    /**
     * Every open uses the bundled SQLite driver, so the phone runs the same SQLite build as the
     * JVM tests, and never a destructive migration fallback.
     */
    internal fun openWith(builder: RoomDatabase.Builder<StoreDatabase>): StoreDatabase =
        builder
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
}
