package io.github.opencircuit.health

/**
 * The twelve Health Connect permissions the app asks for: WRITE only. Nothing here reads Health
 * Connect — an app holding a type's WRITE permission may read back the records it wrote itself,
 * which is all the app ever needs — so no READ permission and no background-read permission is
 * declared or requested.
 *
 * [permission] is the platform's permission string, as declared in the app's manifest.
 */
enum class HealthPermission(val permission: String) {
    HEART_RATE("android.permission.health.WRITE_HEART_RATE"),
    RESTING_HEART_RATE("android.permission.health.WRITE_RESTING_HEART_RATE"),
    HEART_RATE_VARIABILITY("android.permission.health.WRITE_HEART_RATE_VARIABILITY"),
    OXYGEN_SATURATION("android.permission.health.WRITE_OXYGEN_SATURATION"),
    RESPIRATORY_RATE("android.permission.health.WRITE_RESPIRATORY_RATE"),
    SKIN_TEMPERATURE("android.permission.health.WRITE_SKIN_TEMPERATURE"),
    STEPS("android.permission.health.WRITE_STEPS"),
    DISTANCE("android.permission.health.WRITE_DISTANCE"),
    ACTIVE_CALORIES_BURNED("android.permission.health.WRITE_ACTIVE_CALORIES_BURNED"),
    BASAL_METABOLIC_RATE("android.permission.health.WRITE_BASAL_METABOLIC_RATE"),
    SLEEP("android.permission.health.WRITE_SLEEP"),
    MENSTRUATION("android.permission.health.WRITE_MENSTRUATION"),
    ;

    companion object {
        /** The permission strings of every entry, in declaration order. */
        val ALL_STRINGS: List<String> = entries.map { it.permission }
    }
}

/**
 * The Health Connect record types the app writes. A record's id is unique per app AND per record
 * type: the same `clientRecordId` in two types is two records. Menstruation flow and menstruation
 * period are two record types under one permission.
 */
enum class RecordType(val permission: HealthPermission) {
    HEART_RATE(HealthPermission.HEART_RATE),
    RESTING_HEART_RATE(HealthPermission.RESTING_HEART_RATE),
    HEART_RATE_VARIABILITY_RMSSD(HealthPermission.HEART_RATE_VARIABILITY),
    OXYGEN_SATURATION(HealthPermission.OXYGEN_SATURATION),
    RESPIRATORY_RATE(HealthPermission.RESPIRATORY_RATE),
    SKIN_TEMPERATURE(HealthPermission.SKIN_TEMPERATURE),
    STEPS(HealthPermission.STEPS),
    DISTANCE(HealthPermission.DISTANCE),
    ACTIVE_CALORIES_BURNED(HealthPermission.ACTIVE_CALORIES_BURNED),
    BASAL_METABOLIC_RATE(HealthPermission.BASAL_METABOLIC_RATE),
    SLEEP_SESSION(HealthPermission.SLEEP),
    MENSTRUATION_FLOW(HealthPermission.MENSTRUATION),
    MENSTRUATION_PERIOD(HealthPermission.MENSTRUATION),
}
