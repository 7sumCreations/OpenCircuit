package io.github.opencircuit.ble

/** The outcome of [RingLink.send]. `send` never throws: every outcome is one of these values. */
sealed interface SendResult {
    /** The ring acknowledged the write (every write is a write with response). */
    data object Sent : SendResult

    /** The link refused the command before writing it. */
    data class Refused(
        /** Which rule refused it. */
        val reason: RefusalReason,
    ) : SendResult

    /** The command was accepted for writing but the write did not succeed. */
    data class Failed(
        /** How the write failed. */
        val reason: SendFailure,
    ) : SendResult
}

/** Why [RingLink.send] refused a command without writing it. */
enum class RefusalReason {
    /** The link is not [LinkState.Authenticated]. */
    NOT_AUTHENTICATED,

    /** `01 00 00` and `01 01 …` belong to the link's own auth exchange; callers may not write them. */
    AUTH_COMMAND_RESERVED,

    /** A data command while the ring is not bonded (the ring ignores data from an unbonded phone). */
    NOT_BONDED,

    /** A history sync open while [LinkInfo.historySafe] is false (pages would be cut short and lost). */
    HISTORY_UNSAFE,
}

/** How an accepted write failed. */
enum class SendFailure {
    /** The write got no answer in time; the connection is closed. */
    TIMED_OUT,

    /** Android reported an error for the write; the connection is closed. */
    GATT_ERROR,

    /** The connection went away before the write completed. */
    LINK_LOST,
}
