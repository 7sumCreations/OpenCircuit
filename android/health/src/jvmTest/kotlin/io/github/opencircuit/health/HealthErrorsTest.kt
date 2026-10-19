package io.github.opencircuit.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
