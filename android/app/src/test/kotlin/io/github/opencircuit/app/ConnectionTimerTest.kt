package io.github.opencircuit.app

import io.github.opencircuit.app.session.ConnectionTimer
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.PairingFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/** The connect → authenticated time on Connection details measures the LAST `connect()` only. */
class ConnectionTimerTest {

    @Test
    fun tryAgainAfterAFailedPairingTimesOnlyTheNewAttempt() {
        var now = 0L
        val timer = ConnectionTimer { now }
        timer.onState(LinkState.Idle)
        timer.onConnect()
        now = 1_000
        timer.onState(LinkState.Connecting)
        now = 5_000
        timer.onState(LinkState.PairingFailed(PairingFailure.BOND_TIMED_OUT))

        now = 60_000 // the user reads the card, then taps Try again
        timer.onConnect()
        now = 62_500
        timer.onState(LinkState.Authenticated)

        assertEquals(2_500L, timer.timings.value.connectToAuthenticatedMillis)
    }
}
