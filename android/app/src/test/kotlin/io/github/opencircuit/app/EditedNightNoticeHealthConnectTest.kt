package io.github.opencircuit.app

import io.github.opencircuit.app.health.EditedNightNotice
import io.github.opencircuit.ringkit.SleepEditedNightNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edited-night notice as the app shows it: `:ringkit`'s line, kept byte-identical to upstream,
 * with its health sentence said of Health Connect and of what this app writes there (the wearer's
 * spans named in the night's notes; Health Connect has no per-sample "entered by you" mark).
 */
class EditedNightNoticeHealthConnectTest {
    private fun min(m: Double) = m * 60

    @Test
    fun aPartlyMeasuredNightNamesHealthConnectAndWhatItHolds() {
        assertEquals(
            "We kept the times you set. The ring recorded 2h 42m of the sleep above; for the other 4h 1m we have your account, " +
                "not a measurement. Both reach Health Connect; the times you entered are named in the night's notes there.",
            EditedNightNotice.line(measuredAsleep = min(162.0), assertedAsleep = min(241.0), writesSleepToHealthConnect = true),
        )
    }

    @Test
    fun anUnmeasuredNightNamesHealthConnectToo() {
        assertEquals(
            "We kept the times you set. The ring wasn’t recording for any of this night, so all 4h 1m of the sleep above is your account, " +
                "not a measurement. It all reaches Health Connect, with the times you entered named in the night's notes there.",
            EditedNightNotice.line(measuredAsleep = 0.0, assertedAsleep = min(241.0), writesSleepToHealthConnect = true),
        )
    }

    @Test
    fun noLineEverNamesAppleHealth() {
        for (measured in listOf(0.0, 0.5, 40.0, min(1.0), min(380.0))) {
            for (asserted in listOf(min(1.0), min(41.0), min(246.0))) {
                for (writes in listOf(true, false)) {
                    val line = EditedNightNotice.line(measured, asserted, writes)
                    assertFalse(line.orEmpty().contains("Apple"), "$measured / $asserted / $writes: $line")
                    if (writes) assertTrue(line.orEmpty().contains("Health Connect"), "$measured / $asserted: $line")
                }
            }
        }
    }

    @Test
    fun withoutASleepWriteTheHealthSentenceIsDroppedAsUpstreamDoes() {
        val line = EditedNightNotice.line(min(162.0), min(241.0), writesSleepToHealthConnect = false)
        assertEquals(SleepEditedNightNotice.line(min(162.0), min(241.0), mirrorsSleepToHealth = false), line)
        assertFalse(line.orEmpty().contains("Health"))
    }

    @Test
    fun aNightThatMustStaySilentStaysSilent() {
        assertNull(EditedNightNotice.line(-1.0, min(41.0), true), "not computed")
        assertNull(EditedNightNotice.line(min(380.0), 30.0, true), "under a minute asserted")
    }

    @Test
    fun theRingkitLineItselfIsUntouched() {
        // The ported copy stays upstream's, byte for byte; only the app's rendering changes it.
        assertTrue(SleepEditedNightNotice.line(min(162.0), min(241.0), mirrorsSleepToHealth = true)!!.endsWith("Both reach Apple Health; your part is marked there as entered by you."))
    }
}
