package io.github.opencircuit.app.data

/**
 * The app's two flags. Both are read synchronously (the launch decision must not wait), and a
 * damaged value reads as not set: onboarding shows again, the Nearby-devices dialog is asked for
 * again. Neither failure is harmful, so both read closed to "not yet".
 */
interface AppPrefs {
    /** The user finished or skipped onboarding (`onboarding.completed.v1`; upstream's key, OB:18). */
    val onboardingCompleted: Boolean

    /** Marks onboarding done; false when the file could not be written. */
    fun setOnboardingCompleted(): Boolean

    /**
     * The app has asked for Nearby devices before (`bt.permission.asked.v1`). Android gives no way
     * to tell "never asked" from "refused for good"; this flag does.
     */
    val permissionAsked: Boolean

    /** Marks the permission as asked; false when the file could not be written. */
    fun setPermissionAsked(): Boolean

    /**
     * The Ring data card's "Disconnect after syncing" switch (`sync.disconnectAfter.v1`): ON until
     * the user turns it off. A damaged value reads as ON, the default.
     */
    val disconnectAfterSync: Boolean

    /** Saves the switch; false when the file could not be written. */
    fun setDisconnectAfterSync(on: Boolean): Boolean
}

/** [AppPrefs] over [KeyValues]. */
class PrefsAppPrefs(private val values: KeyValues) : AppPrefs {
    override val onboardingCompleted: Boolean get() = values.boolean(ONBOARDING_COMPLETED) == true

    override fun setOnboardingCompleted(): Boolean = values.putBoolean(ONBOARDING_COMPLETED, true)

    override val permissionAsked: Boolean get() = values.boolean(PERMISSION_ASKED) == true

    override fun setPermissionAsked(): Boolean = values.putBoolean(PERMISSION_ASKED, true)

    // Missing or damaged reads as ON: the default never keeps the ring connected behind the user's back.
    override val disconnectAfterSync: Boolean get() = values.boolean(DISCONNECT_AFTER_SYNC) ?: true

    override fun setDisconnectAfterSync(on: Boolean): Boolean = values.putBoolean(DISCONNECT_AFTER_SYNC, on)

    private companion object {
        const val ONBOARDING_COMPLETED = "onboarding.completed.v1"
        const val PERMISSION_ASKED = "bt.permission.asked.v1"
        const val DISCONNECT_AFTER_SYNC = "sync.disconnectAfter.v1"
    }
}
