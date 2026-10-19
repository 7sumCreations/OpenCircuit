package io.github.opencircuit.health

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the user allowed, as the card tells it: nothing (not connected), some (partial, naming
 * what is not allowed) or everything the app asks for. Ported from upstream
 * ios/OpenCircuitTests/HealthKitShareTypesTests.swift (@ b1c2fdd) :210
 * `testShareStatePartialListsDeniedTypes`, :223 `testShareStateAuthorizedWhenAllGranted`, :230
 * `testShareStateUnauthorizedWhenHeartRateNotGranted` (adapted: Health Connect writes every
 * allowed type, so heart rate is no gate — a grant without heart rate is partial, and only
 * nothing granted is unauthorized) and :239 `testFriendlyNamesMapAndDedupe` (adapted: Health
 * Connect's own names; flow and period are one permission, as upstream's two blood-pressure types
 * were one name), over ios/OpenCircuit/Health/HealthKitWriter.swift :257-308. Kotlin-only: skin
 * temperature on a phone without the feature is neither asked for nor "not allowed".
 */
class ShareStateTest {

    private val all = HealthPermission.entries.toSet()

    // ---- ported ----

    @Test
    fun shareStatePartialListsDeniedTypes() {
        val granted = all - setOf(HealthPermission.OXYGEN_SATURATION, HealthPermission.SKIN_TEMPERATURE)

        val state = HealthShare.resolve(granted, requested = all)

        assertEquals(ShareState.Partial(setOf(HealthPermission.OXYGEN_SATURATION, HealthPermission.SKIN_TEMPERATURE)), state)
    }

    @Test
    fun shareStateAuthorizedWhenAllGranted() {
        assertEquals(ShareState.Authorized, HealthShare.resolve(all, requested = all))
    }

    @Test
    fun shareStateUnauthorizedWhenNothingGranted() {
        assertEquals(ShareState.Unauthorized, HealthShare.resolve(emptySet(), requested = all))
    }

    @Test
    fun aGrantWithoutHeartRateIsPartialNotUnauthorized() {
        // Upstream gates on heart rate; Health Connect writes every type the user allowed.
        assertEquals(ShareState.Partial(setOf(HealthPermission.HEART_RATE)), HealthShare.resolve(all - HealthPermission.HEART_RATE, requested = all))
    }

    @Test
    fun friendlyNamesMapAndDedupe() {
        val names = HealthShare.names(
            listOf(
                RecordType.OXYGEN_SATURATION,
                RecordType.SKIN_TEMPERATURE,
                RecordType.MENSTRUATION_FLOW,
                RecordType.MENSTRUATION_PERIOD, // one permission with flow → one "Menstruation"
                RecordType.SLEEP_SESSION,
            ).map { it.permission },
        )
        assertEquals(listOf("Menstruation", "Oxygen saturation", "Skin temperature", "Sleep"), names)
    }

    // ---- Kotlin-only ----

    @Test
    fun everyPermissionHasItsOwnName() {
        val names = HealthPermission.entries.map { HealthShare.name(it) }
        assertEquals(12, names.toSet().size, "twelve permissions, twelve names")
        assertEquals(
            listOf(
                "Heart rate", "Resting heart rate", "Heart rate variability", "Oxygen saturation", "Respiratory rate", "Skin temperature",
                "Steps", "Distance", "Active calories burned", "Basal metabolic rate", "Sleep", "Menstruation",
            ),
            names,
        )
    }

    @Test
    fun withoutTheSkinTemperatureFeatureItIsNeitherAskedForNorNotAllowed() {
        val requested = HealthShare.requested(skinTemperatureSupported = false)

        assertEquals(all - HealthPermission.SKIN_TEMPERATURE, requested)
        assertEquals(ShareState.Authorized, HealthShare.resolve(all - HealthPermission.SKIN_TEMPERATURE, requested))
        assertEquals(all, HealthShare.requested(skinTemperatureSupported = true))
    }

    @Test
    fun aGrantOutsideWhatIsAskedForDoesNotCount() {
        // Skin temperature granted earlier on a phone that has since lost the feature: still nothing else.
        val requested = HealthShare.requested(skinTemperatureSupported = false)
        assertEquals(ShareState.Unauthorized, HealthShare.resolve(setOf(HealthPermission.SKIN_TEMPERATURE), requested))
    }

    @Test
    fun theAttentionNamesAreTheDeniedAndTheFailedOnceEachSorted() {
        val names = HealthShare.attentionNames(
            ShareState.Partial(setOf(HealthPermission.SLEEP, HealthPermission.RESPIRATORY_RATE)),
            failed = setOf(RecordType.SLEEP_SESSION, RecordType.HEART_RATE),
        )
        assertEquals(listOf("Heart rate", "Respiratory rate", "Sleep"), names)
        assertEquals(emptyList(), HealthShare.attentionNames(ShareState.Authorized, failed = emptySet()))
    }
}
