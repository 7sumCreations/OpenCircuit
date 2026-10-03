package io.github.opencircuit.store

import io.github.opencircuit.ringkit.RingAlarm

/**
 * The ring alarm as stored: none saved yet, a readable alarm, or a stored alarm this build cannot
 * read. An unreadable alarm is never replaced by a default: its text stays stored, byte for byte,
 * until a new alarm is saved (upstream resets it to 07:00, disabled — RingAlarmController.swift:58-60).
 */
sealed interface StoredAlarm {
    /** Nothing saved yet; the caller shows its default. */
    data object Absent : StoredAlarm

    data class Readable(val alarm: RingAlarm) : StoredAlarm

    /** The stored text, kept as it is, and what could not be read. */
    data class Unreadable(val raw: String, val reason: String) : StoredAlarm
}
