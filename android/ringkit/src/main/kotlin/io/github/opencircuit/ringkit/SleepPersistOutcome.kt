package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepPersistOutcome.swift (@ b1c2fdd): what
// actually happened when a drain tried to STORE a night's summary.
//
// "We staged a night" and "the wearer has a night" are different claims, and upstream's app once
// reported only the first: a tester's export carried no stored night while the card showed a full
// night and the sync evidence said "sleep committed" drain after drain. The write path had four ways to
// store nothing and only one of them said so. The meaning of each outcome lives here, asserted by tests,
// so "committed" can be defined against [wroteRow] in one place.
//
// [rawValue] is upstream's persisted string (sync evidence, older exports): wire format, never renamed.
// Decoding a stored value back is the store's job.

/** The outcome of one attempt to store a staged night. */
enum class SleepPersistOutcome(val rawValue: String) {
    /** A new row was inserted for this night. */
    INSERTED("inserted"),

    /** An existing row was refreshed from this staging. */
    UPDATED("updated"),

    /** Merge protection kept the stored night because it is at least as complete. NOT a failure. */
    KEPT_FULLER_STORED_NIGHT("keptFullerStoredNight"),

    /** The wearer manually edited this night and the edit is authoritative. NOT a failure. */
    KEPT_MANUAL_EDIT("keptManualEdit"),

    /**
     * A DIFFERENT night already owns this key and the incoming block is an unfinished evening bout, so
     * the write was refused rather than allowed to evict it.
     */
    REFUSED_NIGHT_KEY_COLLISION("refusedNightKeyCollision"),

    /** Staging produced no segments — nothing was offered to the store; nothing protects the night. */
    NO_STAGED_SEGMENTS("noStagedSegments"),

    /**
     * The one-shot night-key migration has not succeeded yet, so the write was deferred rather than
     * filed under a scheme the rest of the table has not adopted. Recoverable: the epochs are still in
     * the archive and the next drain re-stages them.
     */
    DEFERRED_NIGHT_KEY_MIGRATION("deferredNightKeyMigration"),

    /** The store threw. */
    FAILED("failed"),
    ;

    /** Whether a stored row now reflects THIS staging — what "sleep committed" means. */
    val wroteRow: Boolean get() = this == INSERTED || this == UPDATED

    /** Whether the night is represented by a stored row at all; the two deliberate keeps count. */
    val nightIsStored: Boolean get() = wroteRow || this == KEPT_FULLER_STORED_NIGHT || this == KEPT_MANUAL_EDIT

    /**
     * No stored row backs this night and nothing deliberately kept one — the state in which the card
     * could display a night the stored pipeline never computed. The only outcomes that should ever
     * reach the wearer as a warning.
     */
    val isSilentLoss: Boolean get() = !nightIsStored

    /**
     * Whether the same drain, repeated, could still land the night. A key collision is permanent for
     * this staging (every retry hits the same guard); the other losses are not.
     */
    val isRecoverableByRetry: Boolean get() = this == DEFERRED_NIGHT_KEY_MIGRATION || this == NO_STAGED_SEGMENTS || this == FAILED
}
