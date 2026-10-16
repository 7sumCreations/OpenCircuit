package io.github.opencircuit.health

/** What the user allowed of what the app asks for. */
sealed interface ShareState {
    /** Nothing: the card offers to connect. */
    data object Unauthorized : ShareState

    /** Some: the rest is written, and the card names what is not allowed. */
    data class Partial(val denied: Set<HealthPermission>) : ShareState

    /** Everything the app asks for on this phone. */
    data object Authorized : ShareState
}

/**
 * The share state, its names and what the app asks for (upstream `resolveShareState`,
 * `friendlyName(s)`, ios/OpenCircuit/Health/HealthKitWriter.swift :257-308).
 */
object HealthShare {
    /**
     * The permissions the app asks for on this phone: all twelve, less skin temperature where
     * Health Connect does not offer it (asking for a type the phone cannot store would only show
     * the user a switch that does nothing).
     */
    fun requested(skinTemperatureSupported: Boolean): Set<HealthPermission> =
        if (skinTemperatureSupported) HealthPermission.entries.toSet() else HealthPermission.entries.toSet() - HealthPermission.SKIN_TEMPERATURE

    /**
     * Nothing of [requested] granted → [ShareState.Unauthorized]; all of it → [ShareState.Authorized];
     * else [ShareState.Partial] with the rest. Upstream gates the whole state on heart rate (its
     * flush wrote nothing without it); Health Connect writes every type the user allowed, so a
     * grant without heart rate is partial.
     */
    fun resolve(granted: Set<HealthPermission>, requested: Set<HealthPermission>): ShareState {
        if ((granted intersect requested).isEmpty()) return ShareState.Unauthorized
        val denied = requested - granted
        return if (denied.isEmpty()) ShareState.Authorized else ShareState.Partial(denied)
    }

    /** The name Health Connect's own screens give the permission's data. */
    fun name(permission: HealthPermission): String = when (permission) {
        HealthPermission.HEART_RATE -> "Heart rate"
        HealthPermission.RESTING_HEART_RATE -> "Resting heart rate"
        HealthPermission.HEART_RATE_VARIABILITY -> "Heart rate variability"
        HealthPermission.OXYGEN_SATURATION -> "Oxygen saturation"
        HealthPermission.RESPIRATORY_RATE -> "Respiratory rate"
        HealthPermission.SKIN_TEMPERATURE -> "Skin temperature"
        HealthPermission.STEPS -> "Steps"
        HealthPermission.DISTANCE -> "Distance"
        HealthPermission.ACTIVE_CALORIES_BURNED -> "Active calories burned"
        HealthPermission.BASAL_METABOLIC_RATE -> "Basal metabolic rate"
        HealthPermission.SLEEP -> "Sleep"
        HealthPermission.MENSTRUATION -> "Menstruation"
    }

    /** Each permission's name once, sorted (by code point, the same on every phone). */
    fun names(permissions: Collection<HealthPermission>): List<String> = permissions.map(::name).distinct().sorted()

    /** What the card names: the types not allowed and the types whose writes fail, once each, sorted. */
    fun attentionNames(state: ShareState, failed: Collection<RecordType>): List<String> {
        val denied = (state as? ShareState.Partial)?.denied.orEmpty()
        return names(denied + failed.map { it.permission })
    }
}
