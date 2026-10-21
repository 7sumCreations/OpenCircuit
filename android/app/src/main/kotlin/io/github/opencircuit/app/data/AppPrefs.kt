package io.github.opencircuit.app.data

/**
 * The app's small saved choices. Each is read synchronously (the launch decision and the first
 * frame must not wait), and a damaged value reads as its safe default: onboarding shows again, the
 * Nearby-devices dialog is asked for again, Health Connect writing is OFF.
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

    /**
     * Today's section order as saved (`today.sectionOrder.v1`, upstream's `dashboard.sectionOrder`
     * form: ids joined by commas); null when none is saved or the value is not a string — the
     * order's decoder then gives the default.
     */
    val todaySectionOrder: String?

    /** Saves Today's section order; false when the file could not be written. */
    fun setTodaySectionOrder(order: String): Boolean

    /** Today's tile order as saved (`today.tileOrder.v1`); null when none is saved or the value is not a string. */
    val todayTileOrder: String?

    /** Saves Today's tile order; false when the file could not be written. */
    fun setTodayTileOrder(order: String): Boolean

    /**
     * Settings' "Send to Health Connect" switch (`health.write.enabled.v1`). OFF until the user turns
     * it on — on a fresh install and on an install upgraded from a version without the switch alike —
     * and a damaged value reads OFF: nothing leaves the app by accident.
     */
    val healthWriteEnabled: Boolean

    /** Saves the switch; false when the file could not be written. */
    fun setHealthWriteEnabled(on: Boolean): Boolean

    /** Today's one-time "Health Connect export is now off" notice was dismissed (`health.upgradeNotice.dismissed.v1`); a damaged value reads not dismissed. */
    val healthUpgradeNoticeDismissed: Boolean

    /** Marks the notice dismissed; false when the file could not be written. */
    fun setHealthUpgradeNoticeDismissed(): Boolean
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

    override val todaySectionOrder: String? get() = values.string(TODAY_SECTION_ORDER)

    override fun setTodaySectionOrder(order: String): Boolean = values.putString(TODAY_SECTION_ORDER, order)

    override val todayTileOrder: String? get() = values.string(TODAY_TILE_ORDER)

    override fun setTodayTileOrder(order: String): Boolean = values.putString(TODAY_TILE_ORDER, order)

    // Missing or damaged reads as OFF: data goes to Health Connect only after the user turned it on.
    override val healthWriteEnabled: Boolean get() = values.boolean(HEALTH_WRITE_ENABLED) == true

    override fun setHealthWriteEnabled(on: Boolean): Boolean = values.putBoolean(HEALTH_WRITE_ENABLED, on)

    override val healthUpgradeNoticeDismissed: Boolean get() = values.boolean(HEALTH_UPGRADE_NOTICE_DISMISSED) == true

    override fun setHealthUpgradeNoticeDismissed(): Boolean = values.putBoolean(HEALTH_UPGRADE_NOTICE_DISMISSED, true)

    private companion object {
        const val ONBOARDING_COMPLETED = "onboarding.completed.v1"
        const val PERMISSION_ASKED = "bt.permission.asked.v1"
        const val DISCONNECT_AFTER_SYNC = "sync.disconnectAfter.v1"
        const val TODAY_SECTION_ORDER = "today.sectionOrder.v1"
        const val TODAY_TILE_ORDER = "today.tileOrder.v1"
        const val HEALTH_WRITE_ENABLED = "health.write.enabled.v1"
        const val HEALTH_UPGRADE_NOTICE_DISMISSED = "health.upgradeNotice.dismissed.v1"
    }
}
