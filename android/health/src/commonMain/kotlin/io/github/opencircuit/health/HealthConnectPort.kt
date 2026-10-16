package io.github.opencircuit.health

import java.time.Instant

/** Whether Health Connect can be used at all on this phone, for this user. */
enum class HealthSdkStatus {
    /** Present and usable. */
    AVAILABLE,

    /**
     * Not usable: missing, disabled, or the app runs in a work profile, Private Space or another
     * profile, where Health Connect is not offered.
     */
    UNAVAILABLE,
}

/** Health Connect features that a phone may or may not have. */
enum class HealthFeature {
    /** Skin temperature records (Android 14 needs a platform extension; Android 15 and later have it). */
    SKIN_TEMPERATURE,
}

/**
 * The one door to Health Connect. Every call that writes or deletes is one call against the
 * platform's write quota and is atomic: it stores all of its records or none.
 *
 * A call fails with one of the [HealthCallException]s below, never with anything else the caller
 * has to know about.
 */
interface HealthConnectPort {
    suspend fun sdkStatus(): HealthSdkStatus

    /** The WRITE permissions the user has granted, out of the twelve the app asks for. */
    suspend fun grantedWrites(): Set<HealthPermission>

    suspend fun featureAvailable(feature: HealthFeature): Boolean

    /**
     * Inserts [records], all of type [type], in one call. A record whose id and type are already
     * stored replaces the stored one when its version is higher, and is ignored otherwise.
     * Returns Health Connect's own id of each record, in order. That id depends only on the app,
     * the type and the clientRecordId, so it cannot tell a replaced record from an ignored one.
     */
    suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String>

    /** Deletes this app's records of [type] with these ids, in one call. A missing id is not an error. */
    suspend fun deleteByClientIds(type: RecordType, clientRecordIds: List<String>)

    /**
     * This app's own records of [type] that start (or, for an instant record, fall) in
     * [`from`, `until`). For diagnostics and tests: the app holds no READ permission and needs none
     * to read what it wrote.
     */
    suspend fun readOwn(type: RecordType, from: Instant, until: Instant): List<HealthRecord>
}

/** Why a Health Connect call failed. */
sealed interface HealthCallException

/** The call needed a WRITE permission the user has not granted. Nothing of the call was stored. */
class HealthNotGrantedException(val permission: HealthPermission, message: String) :
    SecurityException(message), HealthCallException

/** A record broke one of the platform's record rules. Nothing of the call was stored. */
class HealthInvalidRecordException(message: String) :
    IllegalArgumentException(message), HealthCallException

/** Why a call may succeed later without anything changing on our side. */
enum class RetryReason {
    /** The platform's write quota is spent for now. */
    RATE_LIMITED,

    /** Health Connect is busy with a backup, a restore or a data migration. */
    DATA_SYNC_IN_PROGRESS,

    /** Another user is in the foreground; Health Connect only serves the foreground user. */
    NOT_FOREGROUND_USER,
}

/**
 * What the platform's error code says about an `IllegalStateException` from Health Connect (Jetpack
 * wraps every platform error it has no Kotlin type for in one). The adapter maps the SDK's own
 * constants to these.
 */
enum class PlatformError {
    /** `HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED`: the write quota is spent for now. */
    RATE_LIMIT_EXCEEDED,

    /** `HealthConnectException.ERROR_DATA_SYNC_IN_PROGRESS`: a backup, restore or migration is running. */
    DATA_SYNC_IN_PROGRESS,

    /** Any other code, or none. */
    OTHER,
}

/** Which Health Connect state errors are worth trying again later, and why. */
object HealthErrors {
    /**
     * The reason to try again later, or null for a real failure. The quota and a data sync say so
     * in their code. A call from a user who is not the phone's foreground user (another user
     * switched in) is refused by the platform with no code of its own, so any other state error
     * while the app's user is not in front is taken as that refusal; in front, it is a failure.
     */
    fun retryReason(error: PlatformError, userInForeground: Boolean): RetryReason? = when (error) {
        PlatformError.RATE_LIMIT_EXCEEDED -> RetryReason.RATE_LIMITED
        PlatformError.DATA_SYNC_IN_PROGRESS -> RetryReason.DATA_SYNC_IN_PROGRESS
        PlatformError.OTHER -> if (userInForeground) null else RetryReason.NOT_FOREGROUND_USER
    }
}

/** The call was refused for now, for [reason]; nothing of it was stored. Try again later. */
class HealthRetryLaterException(val reason: RetryReason, message: String) :
    IllegalStateException(message), HealthCallException

/** Any other failure of the call (Health Connect's own I/O or a broken connection to it). */
class HealthCallFailedException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause), HealthCallException
