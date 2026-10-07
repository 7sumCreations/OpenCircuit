package io.github.opencircuit.app.data

/**
 * The app's few saved values, typed. A seam over `SharedPreferences` ([SharedPreferencesKeyValues]
 * on the phone, an in-memory map in the JVM tests).
 *
 * A read never throws: a value that is missing, or saved with another type (a damaged or
 * hand-edited file), reads as null. A write reports whether it reached the file.
 */
interface KeyValues {
    /** The string under [key], or null when there is none or it is not a string. */
    fun string(key: String): String?

    /** The boolean under [key], or null when there is none or it is not a boolean. */
    fun boolean(key: String): Boolean?

    /** Saves [value] under [key]; false when the file could not be written. */
    fun putString(key: String, value: String): Boolean

    /** Saves [value] under [key]; false when the file could not be written. */
    fun putBoolean(key: String, value: Boolean): Boolean

    /** Removes [key]; false when the file could not be written. */
    fun remove(key: String): Boolean
}
