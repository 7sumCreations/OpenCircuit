package io.github.opencircuit.ringkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guard for how the Android code registers its Bluetooth broadcast receivers. The code that
 * registers them is Android-only and is never run on the JVM, so this reads the sources instead.
 *
 * A receiver registered with `RECEIVER_NOT_EXPORTED` only gets broadcasts sent by uid system (or
 * the app itself). On current Android the bond broadcast (`ACTION_BOND_STATE_CHANGED`) is sent by
 * the Bluetooth stack, which runs as its own uid, so such a receiver silently never hears a bond
 * change: on a real phone a first pairing then finished only through the link's own slow re-read
 * of the bond. Both Bluetooth actions are protected broadcasts (no app can send them), so
 * `RECEIVER_EXPORTED` opens nothing. Every source that registers a receiver for a Bluetooth
 * action must therefore not use `RECEIVER_NOT_EXPORTED`.
 */
class BluetoothReceiverExportTest {

    private val bluetoothActions = listOf(
        "BluetoothAdapter.ACTION_STATE_CHANGED",
        "BluetoothDevice.ACTION_BOND_STATE_CHANGED",
    )

    @Test
    fun bluetoothBroadcastReceiversAreRegisteredExported() {
        val rootPath = assertNotNull(
            System.getProperty("opencircuit.androidRoot"),
            "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts",
        )
        val sourceRoots = listOf("ble/src/androidMain", "app/src/main").map { File(rootPath, it) }
        sourceRoots.forEach { assertTrue(it.isDirectory, "source root not found: $it") }

        val registering = sourceRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file ->
                val text = file.readText()
                "registerReceiver(" in text && bluetoothActions.any { it in text }
            }
        assertTrue(
            registering.any { it.name == "AndroidLinkBroadcasts.kt" },
            "the scan never reached the link's broadcast receivers — the source layout moved",
        )

        val notExported = registering.filter { "Context.RECEIVER_NOT_EXPORTED" in it.readText() }
        assertTrue(
            notExported.isEmpty(),
            "Bluetooth broadcast receivers registered RECEIVER_NOT_EXPORTED (they miss broadcasts " +
                "the Bluetooth stack sends): " + notExported.joinToString { it.name },
        )
    }
}
