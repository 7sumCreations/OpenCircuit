package io.github.opencircuit.app.health

import io.github.opencircuit.app.data.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings' "Send to Health Connect" switch: whether the app may talk to Health Connect at all.
 * It starts from the saved choice ([AppPrefs.healthWriteEnabled]: nothing saved is OFF) and is the
 * one thing [HealthFlush] and [HealthAccess] ask before any Health Connect call — while it is OFF
 * nothing is written, nothing is read, and no permission screen is shown.
 *
 * Turning it ON takes effect only once saved (an ON lost at the next launch would surprise the
 * user later); turning it OFF takes effect at once, saved or not, so stopping is never held back.
 * [onChanged] hears each change (the app reads Health Connect's access again when it turns ON).
 */
class HealthSwitch(
    private val prefs: AppPrefs,
    private val log: (String) -> Unit,
    private val onChanged: (Boolean) -> Unit = {},
) {
    private val on = MutableStateFlow(prefs.healthWriteEnabled)

    /** The switch as the Settings screen draws it. */
    val enabled: StateFlow<Boolean> = on.asStateFlow()

    /** Whether Health Connect may be called now; read synchronously at every gate. */
    fun isOn(): Boolean = on.value

    /** Turns writing [value]; false when the choice could not be saved (an ON then stays OFF). */
    fun set(value: Boolean): Boolean {
        val saved = prefs.setHealthWriteEnabled(value)
        if (!saved) log("Could not save the Health Connect switch")
        if (value && !saved) return false
        if (on.value != value) {
            on.value = value
            onChanged(value)
        }
        return saved
    }
}
