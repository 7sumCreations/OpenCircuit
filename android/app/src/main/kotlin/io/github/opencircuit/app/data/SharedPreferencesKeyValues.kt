package io.github.opencircuit.app.data

import android.content.SharedPreferences

/**
 * [KeyValues] over the app's private `SharedPreferences` file.
 *
 * Reads go through [SharedPreferences.getAll], so a value saved with another type reads as null
 * (`getString` would throw `ClassCastException` on it). Writes use `commit()`: the values are
 * tiny, and the caller learns whether the write reached the disk.
 */
class SharedPreferencesKeyValues(private val prefs: SharedPreferences) : KeyValues {
    override fun string(key: String): String? = prefs.all[key] as? String

    override fun boolean(key: String): Boolean? = prefs.all[key] as? Boolean

    override fun putString(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()

    override fun putBoolean(key: String, value: Boolean): Boolean = prefs.edit().putBoolean(key, value).commit()

    override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()
}
