package io.github.opencircuit.app

import io.github.opencircuit.app.data.KeyValues

/**
 * The JVM stand-in for the app's SharedPreferences: a map that can also hold a value of the wrong
 * type ([putRaw]), the way a corrupt or hand-edited preferences file can. Reads follow the device
 * adapter's rule: a value of another type reads as absent. [failWrites] makes every write report
 * failure, as a full disk would.
 */
internal class InMemoryKeyValues : KeyValues {
    private val values = mutableMapOf<String, Any>()

    var failWrites = false

    /** Stores any value as-is, bypassing the typed writes. */
    fun putRaw(key: String, value: Any) {
        values[key] = value
    }

    fun raw(key: String): Any? = values[key]

    override fun string(key: String): String? = values[key] as? String

    override fun boolean(key: String): Boolean? = values[key] as? Boolean

    override fun putString(key: String, value: String): Boolean = write { values[key] = value }

    override fun putBoolean(key: String, value: Boolean): Boolean = write { values[key] = value }

    override fun remove(key: String): Boolean = write { values.remove(key) }

    private fun write(change: () -> Unit): Boolean {
        if (failWrites) return false
        change()
        return true
    }
}
