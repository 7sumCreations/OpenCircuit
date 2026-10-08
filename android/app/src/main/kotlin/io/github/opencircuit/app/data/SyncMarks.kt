package io.github.opencircuit.app.data

import java.time.Instant

/**
 * When the ring's last COMPLETE sync finished, kept across launches: the link-up auto-sync is
 * throttled on it (300 s) and, until there is one, the auto-sync ignores the night's quiet.
 */
interface SyncMarks {
    /** The last complete sync's finish, or null when there was none (or the saved value is unreadable). */
    val lastCompleteSync: Instant?

    /** Saves [at] as the last complete sync's finish; false when the file could not be written. */
    fun setLastCompleteSync(at: Instant): Boolean
}

/**
 * [SyncMarks] for one ring ([ringId], its normalized address) over [KeyValues], under
 * `sync.lastComplete.v1/<ringId>` as decimal epoch milliseconds (upstream keeps its last successful
 * sync in its observability store, `ios/OpenCircuit/ContentView.swift:924-941` @ b1c2fdd). A value
 * that is missing, not plain ASCII digits, or past 64 bits reads as none: the next link-up then
 * syncs, which is harmless.
 */
class PrefsSyncMarks(private val values: KeyValues, ringId: String) : SyncMarks {
    private val key = "$LAST_COMPLETE/$ringId"

    override val lastCompleteSync: Instant?
        get() {
            val text = values.string(key) ?: return null
            // ASCII digits only: Kotlin's toLongOrNull also takes other scripts' digits.
            if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
            return text.toLongOrNull()?.let(Instant::ofEpochMilli)
        }

    override fun setLastCompleteSync(at: Instant): Boolean = values.putString(key, at.toEpochMilli().toString())

    private companion object {
        const val LAST_COMPLETE = "sync.lastComplete.v1"
    }
}
