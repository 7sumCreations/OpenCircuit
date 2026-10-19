package io.github.opencircuit.app.health

import io.github.opencircuit.ringkit.SleepEditedNightNotice

/**
 * The edited-night notice as the app shows it. `:ringkit`'s [SleepEditedNightNotice] is upstream's
 * copy byte for byte, and names Apple Health; here its health sentence is said of Health Connect
 * and of what this app writes there. Health Connect has no per-sample "entered by you" mark (one
 * record per night, no custom metadata), so the sentence says where her part is named: the
 * night's notes ("Times you entered: …"). The rest of the line, and when it stays silent or drops
 * the health sentence, is `:ringkit`'s.
 */
object EditedNightNotice {
    private const val UPSTREAM_ALL = " It all reaches Apple Health, marked there as entered by you."
    private const val UPSTREAM_BOTH = " Both reach Apple Health; your part is marked there as entered by you."
    private const val ALL = " It all reaches Health Connect, with the times you entered named in the night's notes there."
    private const val BOTH = " Both reach Health Connect; the times you entered are named in the night's notes there."

    /**
     * The line for a night with [measuredAsleep] and [assertedAsleep] seconds (negative: not
     * computed), or null when it stays silent. [writesSleepToHealthConnect]: whether the app is
     * writing sleep to Health Connect now (the sleep permission granted); when not, the health
     * sentence is dropped, as upstream drops it.
     */
    fun line(measuredAsleep: Double, assertedAsleep: Double, writesSleepToHealthConnect: Boolean): String? {
        val line = SleepEditedNightNotice.line(measuredAsleep, assertedAsleep, writesSleepToHealthConnect) ?: return null
        return when {
            line.endsWith(UPSTREAM_ALL) -> line.removeSuffix(UPSTREAM_ALL) + ALL
            line.endsWith(UPSTREAM_BOTH) -> line.removeSuffix(UPSTREAM_BOTH) + BOTH
            else -> line
        }
    }
}
