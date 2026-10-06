package io.github.opencircuit.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.opencircuit.app.ring.RingScreen
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.ui.OpenCircuitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's screens. No navigation library: two destinations, one `when`. */
enum class Destination { Onboarding, Ring }

/** The single activity. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpenCircuitApp).container
        setContent {
            OpenCircuitTheme {
                // Onboarding is not built yet, so the app opens on the Ring screen.
                var destination by rememberSaveable { mutableStateOf(Destination.Ring) }
                when (destination) {
                    Destination.Onboarding -> OnboardingPlaceholder(onDone = { destination = Destination.Ring })
                    Destination.Ring -> {
                        val ringViewModel = viewModel {
                            RingViewModel(
                                controller = container.ringSession,
                                title = container.ringTitle,
                                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                            )
                        }
                        val state by ringViewModel.uiState.collectAsStateWithLifecycle()
                        RingScreen(state = state, onAction = ringViewModel::onAction, pulse = !reducedMotion())
                    }
                }
            }
        }
    }
}

/**
 * True when the system asks for less motion: "Remove animations" sets the animator duration scale
 * to 0 (the same switch Android's own animations follow).
 */
private fun ComponentActivity.reducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Stands in for the onboarding pages until they are built. */
@Composable
private fun OnboardingPlaceholder(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Welcome to OpenCircuit")
        Button(onClick = onDone) { Text("Get started") }
    }
}
