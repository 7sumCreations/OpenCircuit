package io.github.opencircuit.store

import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.BulkSleep
import java.time.Instant
import java.util.Collections

/**
 * One ring's stored epoch archive: the raw `0x4c` records kept for re-staging a night, and the
 * facts about its drains (upstream `EpochArchiveStore`, ios/OpenCircuit/Store/EpochArchiveStore.swift
 * @ b1c2fdd, which keeps each in its own `UserDefaults` key). [records] is copied in, so a later
 * change to the caller's list changes nothing here; two archives compare by content.
 */
class StoredEpochArchive(records: List<BulkRecord>, val marks: EpochArchiveMarks) {
    val records: List<BulkRecord> = Collections.unmodifiableList(ArrayList(records))

    fun copy(records: List<BulkRecord> = this.records, marks: EpochArchiveMarks = this.marks): StoredEpochArchive =
        StoredEpochArchive(records, marks)

    override fun equals(other: Any?): Boolean = other is StoredEpochArchive && records == other.records && marks == other.marks

    override fun hashCode(): Int = 31 * records.hashCode() + marks.hashCode()

    override fun toString(): String = "StoredEpochArchive(records=$records, marks=$marks)"

    companion object {
        val EMPTY = StoredEpochArchive(emptyList(), EpochArchiveMarks.NONE)
    }
}

/**
 * The drain facts stored with an epoch archive.
 *
 * - [lastDrainAt]: when the history buffer was last drained (`sleep.lastHistoryDrainAt`).
 * - [headAt]: the newest epoch ever held for this ring (`sleep.archiveHeadAt`).
 * - [unmovedDrains]: completed drains that left [headAt] unmoved (`sleep.unmovedCompletedDrains`).
 * - [hrvPooling]: the last DECIDED HRV-pooling verdict (`sleep.hrvPoolingVerdict`); null before the
 *   first decision. `NO_EVIDENCE` is the absence of a decision and is never stored, as upstream.
 */
data class EpochArchiveMarks(
    val lastDrainAt: Instant? = null,
    val headAt: Instant? = null,
    val unmovedDrains: Int = 0,
    val hrvPooling: BulkSleep.HRVPooling? = null,
) {
    init {
        require(hrvPooling != BulkSleep.HRVPooling.NO_EVIDENCE) { "NO_EVIDENCE is not a decided verdict and is never stored" }
    }

    companion object {
        val NONE = EpochArchiveMarks()
    }
}
