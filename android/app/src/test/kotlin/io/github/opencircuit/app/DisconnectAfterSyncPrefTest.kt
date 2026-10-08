package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "Disconnect after syncing" switch (`sync.disconnectAfter.v1`): ON until the user turns it
 * off, kept across a relaunch (a new instance over the same file), and a damaged value reads as
 * ON — the default, which never keeps the ring connected behind the user's back.
 */
class DisconnectAfterSyncPrefTest {

    private val values = InMemoryKeyValues()
    private val prefs = PrefsAppPrefs(values)

    @Test
    fun theSwitchStartsOn() {
        assertTrue(prefs.disconnectAfterSync)
    }

    @Test
    fun turningItOffIsKeptAcrossARelaunchAndOnAgainToo() {
        assertTrue(prefs.setDisconnectAfterSync(false))

        assertFalse(PrefsAppPrefs(values).disconnectAfterSync)
        assertEquals(false, values.raw(KEY))

        assertTrue(prefs.setDisconnectAfterSync(true))
        assertTrue(PrefsAppPrefs(values).disconnectAfterSync)
    }

    @Test
    fun aDamagedValueReadsAsTheDefaultOn() {
        values.putRaw(KEY, "false")

        assertTrue(prefs.disconnectAfterSync)
    }

    @Test
    fun aWriteThatDoesNotReachTheFileIsReported() {
        values.failWrites = true

        assertFalse(prefs.setDisconnectAfterSync(false))
        assertTrue(prefs.disconnectAfterSync)
    }

    private companion object {
        const val KEY = "sync.disconnectAfter.v1"
    }
}
