package io.github.opencircuit.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Which `IllegalStateException`s from Health Connect mean "call again later" (a quiet "catching up",
 * never the failure map) and which are real failures. Jetpack turns every platform error it has no
 * Kotlin type for into an `IllegalStateException` wrapping the platform's exception, whose error
 * code tells the write quota (rate limit) and a backup, restore or migration in progress (data sync)
 * apart. A call from a user who is not the phone's foreground user is refused by the platform with
 * an `IllegalStateException` of no particular code (AOSP `enforceIsForegroundUser`); the adapter
 * asks Android whether the app's user is in the foreground instead of reading the message.
 */
class HealthErrorsTest {

    /** A refused grant keeps the platform's own exception: its message is what names the real cause. */
    @Test
    fun aMissingGrantKeepsThePlatformsExceptionAsItsCause() {
        val platform = SecurityException("Caller doesn't have android.permission.health.WRITE_HEART_RATE")
        val mapped = HealthErrors.notGranted(HealthPermission.HEART_RATE, platform)
        assertEquals(HealthPermission.HEART_RATE, mapped.permission)
        assertSame(platform, mapped.cause)
        assertEquals("Caller doesn't have android.permission.health.WRITE_HEART_RATE", mapped.cause?.message)
        assertEquals("android.permission.health.WRITE_HEART_RATE is not granted", mapped.message)
    }

    @Test
    fun aMissingGrantWithNoPlatformExceptionHasNoCause() {
        assertNull(HealthNotGrantedException(HealthPermission.HEART_RATE, "not granted").cause)
    }

    @Test
    fun theWriteQuotaIsRetryableWhoeverIsInFront() {
        assertEquals(RetryReason.RATE_LIMITED, HealthErrors.retryReason(PlatformError.RATE_LIMIT_EXCEEDED, userInForeground = true))
        assertEquals(RetryReason.RATE_LIMITED, HealthErrors.retryReason(PlatformError.RATE_LIMIT_EXCEEDED, userInForeground = false))
    }

    @Test
    fun aDataSyncInProgressIsRetryable() {
        assertEquals(RetryReason.DATA_SYNC_IN_PROGRESS, HealthErrors.retryReason(PlatformError.DATA_SYNC_IN_PROGRESS, userInForeground = true))
    }

    @Test
    fun anyOtherStateErrorWhileAnotherUserIsInFrontIsRetryable() {
        assertEquals(RetryReason.NOT_FOREGROUND_USER, HealthErrors.retryReason(PlatformError.OTHER, userInForeground = false))
    }

    @Test
    fun anyOtherStateErrorForTheForegroundUserIsAFailure() {
        assertNull(HealthErrors.retryReason(PlatformError.OTHER, userInForeground = true))
    }
}
