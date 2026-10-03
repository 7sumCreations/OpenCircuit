package io.github.opencircuit.store

import android.content.Context
import androidx.room3.Room

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
