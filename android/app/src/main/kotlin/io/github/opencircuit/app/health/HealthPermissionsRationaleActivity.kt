package io.github.opencircuit.app.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.opencircuit.app.ui.OpenCircuitTheme

/**
 * The privacy text Health Connect shows when the user asks why this app wants health permissions
 * (Health Connect's "privacy policy" link). Android 14 and later grant health permissions only to
 * an app that answers that request, through the guarded alias in the manifest.
 *
 * Static and offline: the app has no website to link to and no network permission.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenCircuitTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "How OpenCircuit uses Health Connect",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        PARAGRAPHS.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }

    private companion object {
        val PARAGRAPHS = listOf(
            "OpenCircuit reads your ring over Bluetooth and keeps everything on this phone. It asks " +
                "Health Connect only for permission to WRITE: heart rate, resting heart rate, heart rate " +
                "variability, blood oxygen, breathing rate, skin temperature, steps, distance, active " +
                "calories, basal metabolic rate, sleep and menstruation.",
            "It never asks to read your Health Connect data. It only reads back the records it wrote " +
                "itself, which Health Connect allows without a read permission.",
            "Nothing leaves your phone: the app has no internet permission, no account and no " +
                "analytics. Health Connect keeps what the app wrote, on this phone, under your control.",
            "You can allow some kinds and not others, and change that at any time in Health Connect. " +
                "Uninstalling the app does not delete what it wrote; you can delete that in Health Connect.",
            "OpenCircuit is not affiliated with RingConn and is not a medical device.",
        )
    }
}
