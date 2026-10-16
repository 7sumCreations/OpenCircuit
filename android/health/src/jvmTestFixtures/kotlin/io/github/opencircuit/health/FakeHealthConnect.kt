package io.github.opencircuit.health

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A Health Connect that enforces the platform's write rules, for JVM tests. It is a claim about
 * the platform, so each rule has its own test (`FakeHealthConnectRulesTest`) and each is sourced
 * from AOSP's Health Connect service on Android 14 to 16 (the Jetpack testing library's fake was
 * not used: it scopes ids across types, never checks a grant, is not atomic, applies Jetpack's
 * looser JVM ranges and never rate-limits).
 *
 * What it models:
 *  1. records keyed by (record type, clientRecordId); every record of a call must be of its type;
 *  2. a stored record is replaced only by a HIGHER version — the documented contract, stricter
 *     than AOSP, which also replaces on an equal one — and the returned id is the platform's
 *     name-based id of (app, type, clientRecordId), the same whether replaced or ignored;
 *  3. a call is atomic: a record breaking a rule, or a type without its WRITE grant, makes the
 *     whole call fail and stores nothing;
 *  4. the record rules of [HealthRanges], judged at the fake's [clock];
 *  5. deletes by clientRecordId within one type, of this app's records only, needing the WRITE
 *     grant; a missing id is not an error. Reading back own records needs the grant too;
 *  6. 1000 write calls (inserts and deletes alike) per 15 minutes and 8000 per 24 hours, and at
 *     most 5 MB per call by [RecordSize]'s estimate, each refused as [RetryReason.RATE_LIMITED];
 *     [busy] refuses every write call with its reason (a data sync, another user in front);
 *  7. injected failures per type, once ([failNext]) or until cleared ([failAlways]), and
 *     [crashAfterAccept]: the next insert of a type stores its records and then fails;
 *  8. [records], [otherAppRecords] and the [calls] log, for assertions.
 *
 * What it does not model: aggregation, change logs, reading other apps' data, the permission UI.
 */
class FakeHealthConnect(
    private val clock: () -> Instant,
    private val packageName: String = "io.github.opencircuit.android",
) : HealthConnectPort {

    /** One write call and how it ended. */
    data class Call(val kind: Kind, val type: RecordType, val count: Int, val outcome: Outcome) {
        enum class Kind { INSERT, DELETE }
        enum class Outcome { OK, NOT_GRANTED, INVALID, RETRY_LATER, FAILED }
    }

    @Volatile var status: HealthSdkStatus = HealthSdkStatus.AVAILABLE
    @Volatile var features: Set<HealthFeature> = HealthFeature.entries.toSet()

    /** When set, every write call is refused for this reason. */
    @Volatile var busy: RetryReason? = null

    private val lock = Any()
    private val granted = mutableSetOf<HealthPermission>()
    private val stored = LinkedHashMap<Pair<RecordType, String>, HealthRecord>()
    private val others = mutableListOf<HealthRecord>()
    private val callTimes = ArrayDeque<Instant>()
    private val callLog = mutableListOf<Call>()
    private val failOnce = mutableMapOf<RecordType, ArrayDeque<Throwable>>()
    private val failPersistent = mutableMapOf<RecordType, Throwable>()
    private val crashAfter = mutableSetOf<RecordType>()

    /** Every write call made, oldest first. */
    val calls: List<Call> get() = synchronized(lock) { callLog.toList() }

    fun grantAll() = synchronized(lock) { granted += HealthPermission.entries }
    fun grant(vararg permissions: HealthPermission) = synchronized(lock) { granted += permissions }
    fun revoke(vararg permissions: HealthPermission) = synchronized(lock) { granted -= permissions.toSet() }

    /** Stores [record] as another app's: never deleted or read back by this app. */
    fun seedOtherApp(record: HealthRecord) = synchronized(lock) { others += record }
    fun otherAppRecords(type: RecordType): List<HealthRecord> = synchronized(lock) { others.filter { it.type == type } }

    /** This app's stored records of [type], in the order first written. */
    fun records(type: RecordType): List<HealthRecord> = synchronized(lock) { stored.filterKeys { it.first == type }.values.toList() }

    fun failNext(type: RecordType, error: Throwable) = synchronized(lock) { failOnce.getOrPut(type) { ArrayDeque() }.addLast(error) }
    fun failAlways(type: RecordType, error: Throwable) = synchronized(lock) { failPersistent[type] = error }
    fun crashAfterAccept(type: RecordType) = synchronized(lock) { crashAfter += type }
    fun clearFailures() = synchronized(lock) {
        failOnce.clear()
        failPersistent.clear()
        crashAfter.clear()
    }

    override suspend fun sdkStatus(): HealthSdkStatus = status
    override suspend fun grantedWrites(): Set<HealthPermission> = synchronized(lock) { granted.toSet() }
    override suspend fun featureAvailable(feature: HealthFeature): Boolean = feature in features

    override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> = writeCall(Call.Kind.INSERT, type, records.size) {
        records.firstOrNull { it.type != type }?.let { throw HealthInvalidRecordException("a ${it.type} record in a $type call") }
        val now = clock()
        for (r in records) {
            HealthRanges.violation(r, now)?.let { throw HealthInvalidRecordException("${r.meta.clientRecordId}: $it") }
        }
        if (RecordSize.estimateBytes(records) > MAX_CALL_BYTES) {
            throw HealthRetryLaterException(RetryReason.RATE_LIMITED, "the call is over 5 MB")
        }
        for (r in records) {
            val key = type to r.meta.clientRecordId
            val old = stored[key]
            if (old == null || r.meta.version > old.meta.version) stored[key] = r
        }
        if (crashAfter.remove(type)) throw HealthCallFailedException("the connection broke after Health Connect stored the call")
        records.map { platformId(type, it.meta.clientRecordId) }
    }

    override suspend fun deleteByClientIds(type: RecordType, clientRecordIds: List<String>): Unit = writeCall(Call.Kind.DELETE, type, clientRecordIds.size) {
        clientRecordIds.forEach { stored.remove(type to it) }
    }

    override suspend fun readOwn(type: RecordType, from: Instant, until: Instant): List<HealthRecord> = synchronized(lock) {
        requireGrant(type)
        stored.filterKeys { it.first == type }.values
            .filter { val t = startOf(it); !t.isBefore(from) && t.isBefore(until) }
            .sortedBy { startOf(it) }
    }

    /** One write call: busy, quota, grant, injected failure, then [body]; logged with its outcome. */
    private fun <T> writeCall(kind: Call.Kind, type: RecordType, count: Int, body: () -> T): T = synchronized(lock) {
        val result = try {
            busy?.let { throw HealthRetryLaterException(it, "Health Connect is busy: $it") }
            spendQuota()
            requireGrant(type)
            failOnce[type]?.removeFirstOrNull()?.let { throw it }
            failPersistent[type]?.let { throw it }
            body()
        } catch (e: Throwable) {
            callLog += Call(kind, type, count, outcomeOf(e))
            throw e
        }
        callLog += Call(kind, type, count, Call.Outcome.OK)
        result
    }

    private fun outcomeOf(e: Throwable): Call.Outcome = when (e) {
        is HealthNotGrantedException -> Call.Outcome.NOT_GRANTED
        is HealthInvalidRecordException -> Call.Outcome.INVALID
        is HealthRetryLaterException -> Call.Outcome.RETRY_LATER
        else -> Call.Outcome.FAILED
    }

    private fun spendQuota() {
        val now = clock()
        while (callTimes.isNotEmpty() && !callTimes.first().isAfter(now.minus(DAY))) callTimes.removeFirst()
        val inWindow = callTimes.count { it.isAfter(now.minus(WINDOW)) }
        if (inWindow >= CALLS_PER_WINDOW || callTimes.size >= CALLS_PER_DAY) {
            throw HealthRetryLaterException(RetryReason.RATE_LIMITED, "the write quota is spent")
        }
        callTimes.addLast(now)
    }

    private fun requireGrant(type: RecordType) {
        if (type.permission !in granted) throw HealthNotGrantedException(type.permission, "${type.permission.permission} is not granted")
    }

    private fun platformId(type: RecordType, clientRecordId: String): String =
        UUID.nameUUIDFromBytes("$packageName|$type|$clientRecordId".toByteArray()).toString()

    private fun startOf(r: HealthRecord): Instant = when (r) {
        is InstantHealthRecord -> r.time
        is IntervalHealthRecord -> r.start
    }

    private companion object {
        const val CALLS_PER_WINDOW = 1000
        const val CALLS_PER_DAY = 8000
        const val MAX_CALL_BYTES = 5L * 1024 * 1024
        val WINDOW: Duration = Duration.ofMinutes(15)
        val DAY: Duration = Duration.ofHours(24)
    }
}
