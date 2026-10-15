package io.github.opencircuit.store

import android.content.Context
import androidx.room3.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.CoroutineContext

/** The database file's name in the app's private databases directory. */
const val STORE_DATABASE_NAME: String = "opencircuit-store.db"

/**
 * Opens (creating if absent) the store at `context.getDatabasePath(name)`. Throws if the file
 * cannot be opened as this store; the file is left untouched, and what the user sees then is the
 * caller's decision.
 */
suspend fun StoreFactory.open(context: Context, name: String = STORE_DATABASE_NAME): StoreDatabase {
    val path = context.getDatabasePath(name).absolutePath
    return openWith(Room.databaseBuilder<StoreDatabase>(context.applicationContext, path))
}

/**
 * Opens a fresh database that lives in memory until it is closed, through the same [openWith] as
 * the on-device file (bundled SQLite driver, no destructive fallback). Needs no `Context`, so the
 * app's JVM tests, which link this Android variant, run their code against the real store.
 * [queryContext] is where the queries run: a virtual-time test passes its own dispatcher, so no
 * query is still running on a real thread while the test moves its clock on.
 */
suspend fun StoreFactory.openInMemory(queryContext: CoroutineContext = Dispatchers.IO): StoreDatabase =
    openWith(Room.inMemoryDatabaseBuilder<StoreDatabase>(), queryContext)
