package io.github.opencircuit.store

import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.CoroutineContext

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
     *
     * Room opens lazily, on the first query. This opens now, so a file that is not a database,
     * a schema with no migration path or a newer schema fails HERE, in front of the caller,
     * instead of inside the first write. On failure the handle is closed and the error rethrown (a
     * failure to close is suppressed on it, never thrown instead); nothing is deleted.
     */
    internal suspend fun openWith(
        builder: RoomDatabase.Builder<StoreDatabase>,
        queryContext: CoroutineContext = Dispatchers.IO,
    ): StoreDatabase {
        val db = builder
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(queryContext)
            .build()
        closeOnFailure(close = db::close) {
            db.useWriterConnection { connection -> connection.usePrepared("PRAGMA user_version") { it.step() } }
        }
        return db
    }
}

/**
 * Runs [block]; when it throws, runs [close] and rethrows what [block] threw. A failure of [close]
 * is added to it as suppressed, never thrown in its place: the caller must see why [block] failed.
 */
internal inline fun <T> closeOnFailure(close: () -> Unit, block: () -> T): T =
    try {
        block()
    } catch (failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
