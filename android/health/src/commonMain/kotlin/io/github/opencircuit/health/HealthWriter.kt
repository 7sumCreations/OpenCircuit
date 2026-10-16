package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the writer reads from the app's store and marks in it. The app implements it over its
 * `LocalStore` (`pendingHealthSamples`, `samples`, `markHealthWritten`); `:health` never reaches
 * into `:store`.
 */
interface HealthSampleStore {
    /** Stored samples of [kind] above zero and newer than the kind's Health Connect mark, oldest first. */
    suspend fun pendingSamples(kind: MetricKind): List<QuantitySample>

    /** Every stored sample of [kind] with `from <= start < until`, oldest first. */
    suspend fun samples(kind: MetricKind, from: Instant, until: Instant): List<QuantitySample>

    /** As [pendingSamples], each with its row id, oldest first (ties in row order). */
    suspend fun pendingRows(kind: MetricKind): List<SampleRow>

    /** Every stored sample of [kind] with `from <= start < until`, any value, with its row id, oldest first (ties in row order). */
    suspend fun rows(kind: MetricKind, from: Instant, until: Instant): List<SampleRow>

    /** The stored nights keyed `from <= night < until`, oldest first. */
    suspend fun nights(from: Instant, until: Instant): List<NightWindow>

    /** Moves each kind's mark to the newest of [samples] (forward only). Only what Health Connect accepted. */
    suspend fun markWritten(samples: List<QuantitySample>)
}

/** What one flush did, per record type. Counts only: no value, no id. */
data class FlushResult(
    /** When the flush ended. */
    val finishedAt: Instant,
    /** False when Health Connect cannot be used on this phone for this user; nothing was tried. */
    val available: Boolean = true,
    /** Records Health Connect accepted, per type. */
    val written: Map<RecordType, Int> = emptyMap(),
    /** Records dropped because they break a platform rule ([HealthRanges]), per type; never sent. */
    val rejected: Map<RecordType, Int> = emptyMap(),
    /** Types with something to write whose WRITE permission the user has not granted: skipped, not failed. */
    val notGranted: Set<RecordType> = emptySet(),
    /** Types with something to write that this phone's Health Connect does not offer (skin temperature): skipped, not failed, left pending. */
    val unsupported: Set<RecordType> = emptySet(),
    /** Types whose write failed, with the kind of failure (never a value); retried on the next flush. */
    val failures: Map<RecordType, String> = emptyMap(),
    /** Health Connect asked to be called later (quota, a backup, another user in front): the rest waits for the next flush. */
    val catchingUp: Boolean = false,
    /** The persisted failure map after this flush: each type whose last write failed, with when ([FailureMap]). */
    val failureMap: Map<RecordType, Instant> = emptyMap(),
) {
    /** Every record accepted in this flush. */
    val recordsWritten: Int get() = written.values.sum()
}

/**
 * Writes what the store holds and Health Connect does not have yet, one flush at a time.
 *
 * A flush: Health Connect's availability → the granted WRITE permissions → per record type, the
 * pending samples → records (pure mappers) → the platform's rules ([HealthRanges]: a breaking
 * record is dropped and counted) → calls of one type each, at most [Chunker.MAX_RECORDS] records
 * and [Chunker.MAX_BYTES] estimated → after EACH accepted call, the store's mark moves past what
 * that call held, and only then. A call that throws marks nothing: Health Connect may have stored
 * it anyway, and the next flush sends the same ids with a higher version, which replaces them.
 *
 * Every record of one flush carries one version, the next [WriteGeneration], taken just before
 * the flush's first call (a flush with nothing to send spends none).
 *
 * A flush makes at most [MAX_CALLS_PER_FLUSH] write calls; reaching it ends the flush quietly as
 * catching up, and the next flush goes on from the marks.
 *
 * Failures: a type without its grant is skipped; a refusal that asks to be called later
 * ([HealthRetryLaterException]: the quota, a data sync, another user in front) ends the whole
 * flush as catching up; a call refused for a bad record ([HealthInvalidRecordException], a rule our
 * table missed) is sent again record by record, once, so only the bad record is dropped and
 * counted; any other failure ends that type's writing for this flush and is reported in
 * [FlushResult.failures]. Cancellation stops the flush where it is; what was accepted and marked
 * stays marked.
 *
 * Written today, in this order: heart rate (one record per UTC hour, [HrHours]), then heart-rate
 * variability, blood oxygen and breathing rate (one record per stored reading, [ScalarRecords]).
 * The other types follow the same steps.
 */
class HealthWriter(
    private val port: HealthConnectPort,
    private val store: HealthSampleStore,
    private val generation: WriteGeneration,
    private val failureStore: HealthFailureStore,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    private val device: () -> RingDevice,
) {
    private val lock = Mutex()

    /** Writes everything pending; a flush that starts while another runs waits for it, then runs. */
    suspend fun flush(): FlushResult = lock.withLock { Pass(clock()).run() }

    /** One flush's state. */
    private inner class Pass(private val now: Instant) {
        private var version: Long? = null
        private val written = LinkedHashMap<RecordType, Int>()
        private val rejected = LinkedHashMap<RecordType, Int>()
        private val notGranted = LinkedHashSet<RecordType>()
        private val unsupported = LinkedHashSet<RecordType>()
        private val failures = LinkedHashMap<RecordType, String>()
        private var catchingUp = false
        private var calls = 0

        suspend fun run(): FlushResult {
            if (port.sdkStatus() != HealthSdkStatus.AVAILABLE) return FlushResult(finishedAt = clock(), available = false, failureMap = failureStore.load())
            val granted = port.grantedWrites()
            writeHeartRate(granted)
            for ((kind, type) in ScalarRecords.TYPES) writeScalar(kind, type, granted)
            writeSkinTemperature(granted)
            val finishedAt = clock()
            return FlushResult(
                finishedAt = finishedAt,
                written = written.toMap(),
                rejected = rejected.toMap(),
                notGranted = notGranted.toSet(),
                unsupported = unsupported.toSet(),
                failures = failures.toMap(),
                catchingUp = catchingUp,
                failureMap = recordOutcome(finishedAt),
            )
        }

        /** Stamps this flush's failed types and clears the ones that wrote, in the shared failure map. */
        private suspend fun recordOutcome(at: Instant): Map<RecordType, Instant> {
            val before = failureStore.load()
            val after = FailureMap.merge(before, written.keys, failures.keys, at)
            if (after != before) failureStore.save(after)
            return after
        }

        private suspend fun version(): Long = version ?: generation.next().also { version = it }

        private suspend fun writeHeartRate(granted: Set<HealthPermission>) {
            val type = RecordType.HEART_RATE
            val hourStarts = HrHours.hoursToRebuild(store.pendingSamples(MetricKind.HEART_RATE), now)
            if (hourStarts.isEmpty()) return
            if (type.permission !in granted) {
                notGranted += type
                return
            }
            // One read covers every hour: everything after the first hour's start is in a pending hour or before the mark in it.
            val stored = store.samples(MetricKind.HEART_RATE, hourStarts.first(), hourStarts.last().plusSeconds(SECONDS_PER_HOUR))
                .groupBy { HrHours.hourStart(it.start) }
            val v = version()
            val hours = hourStarts.mapNotNull { h -> HrHours.hour(h, stored[h].orEmpty(), now, zone(), device(), v) }
            writeItems(type, hours.map { WriteItem(it.record, it.samples) })
        }

        /** Heart-rate variability, blood oxygen or breathing rate: one record per pending stored sample. */
        private suspend fun writeScalar(kind: MetricKind, type: RecordType, granted: Set<HealthPermission>) {
            val pending = store.pendingRows(kind).filter { !it.sample.start.isAfter(now) }
            if (pending.isEmpty()) return
            if (type.permission !in granted) {
                notGranted += type
                return
            }
            // Every stored row at the pending starts, so each id's ordinal counts them all.
            val atStarts = store.rows(kind, pending.first().sample.start, pending.last().sample.start.plusMillis(1))
            writeItems(type, ScalarRecords.items(kind, pending, atStarts, now, zone(), device(), version()))
        }

        /**
         * Skin temperature, one record per night holding a pending reading ([SkinTempNights]). On a
         * phone whose Health Connect has no skin-temperature feature nothing is tried and nothing
         * marked: the readings wait in case the platform gains it.
         */
        private suspend fun writeSkinTemperature(granted: Set<HealthPermission>) {
            val type = RecordType.SKIN_TEMPERATURE
            val pending = store.pendingSamples(MetricKind.TEMPERATURE).filter { !it.start.isAfter(now) }
            if (pending.isEmpty()) return
            if (!port.featureAvailable(HealthFeature.SKIN_TEMPERATURE)) {
                unsupported += type
                return
            }
            if (type.permission !in granted) {
                notGranted += type
                return
            }
            // Every night up to the last pending reading: the baseline reads the prior nights' means.
            val nights = store.nights(Instant.EPOCH, pending.last().start.plusSeconds(SECONDS_PER_DAY))
            val toRebuild = SkinTempNights.nightsToRebuild(pending, nights, now)
            if (toRebuild.isEmpty()) return
            val v = version()
            val items = toRebuild.mapNotNull { night ->
                val stored = store.samples(MetricKind.TEMPERATURE, night.inBedStart, night.inBedEnd.plusMillis(1))
                SkinTempNights.item(night, stored, nights, now, zone(), device(), v)
            }
            writeItems(type, items)
        }

        /**
         * Drops (and counts) the items breaking a platform rule, then sends the rest in calls of
         * [Chunker]'s size, marking each call's samples once Health Connect accepted it.
         */
        private suspend fun writeItems(type: RecordType, items: List<WriteItem>) {
            if (catchingUp) return
            val kept = items.filter { HealthRanges.violation(it.record, now) == null }
            reject(type, items.size - kept.size)
            for (chunk in Chunker.chunks(kept) { RecordSize.estimateBytes(it.record) }) {
                when (send(type, chunk.map { it.record })) {
                    Sent.ACCEPTED -> accepted(type, chunk)
                    Sent.STOP -> return
                    // The platform refused a record our table let through: try each record once on
                    // its own, so only the bad one is dropped (each try is a call of the budget).
                    Sent.REFUSED_RECORD -> if (chunk.size == 1) reject(type, 1) else if (!isolate(type, chunk)) return
                }
            }
        }

        /** Sends each item of a refused call on its own; false when this type is done for the flush. */
        private suspend fun isolate(type: RecordType, chunk: List<WriteItem>): Boolean {
            for (item in chunk) {
                when (send(type, listOf(item.record))) {
                    Sent.ACCEPTED -> accepted(type, listOf(item))
                    Sent.REFUSED_RECORD -> reject(type, 1)
                    Sent.STOP -> return false
                }
            }
            return true
        }

        private suspend fun accepted(type: RecordType, items: List<WriteItem>) {
            store.markWritten(items.flatMap { it.samples })
            written[type] = (written[type] ?: 0) + items.size
        }

        private fun reject(type: RecordType, count: Int) {
            if (count > 0) rejected[type] = (rejected[type] ?: 0) + count
        }

        /**
         * One call, within the flush's budget of [MAX_CALLS_PER_FLUSH]. [Sent.STOP] ends this type's
         * writing for the flush (not granted, or failed), and with [catchingUp] set the whole flush
         * (the budget spent, or Health Connect asked to be called later).
         */
        private suspend fun send(type: RecordType, records: List<HealthRecord>): Sent {
            if (catchingUp) return Sent.STOP
            if (calls >= MAX_CALLS_PER_FLUSH) {
                catchingUp = true
                return Sent.STOP
            }
            calls++
            try {
                port.insert(type, records)
                return Sent.ACCEPTED
            } catch (e: CancellationException) {
                throw e
            } catch (e: HealthNotGrantedException) {
                notGranted += type
            } catch (e: HealthRetryLaterException) {
                catchingUp = true
            } catch (e: HealthInvalidRecordException) {
                return Sent.REFUSED_RECORD
            } catch (e: Exception) {
                // The failure's kind only: a message may carry an id or a value.
                failures[type] = e::class.simpleName ?: "failure"
            }
            return Sent.STOP
        }
    }

    /** How one call ended, for the writer. */
    private enum class Sent { ACCEPTED, REFUSED_RECORD, STOP }

    companion object {
        /**
         * The most write calls one flush makes. Health Connect allows 1000 per 15 minutes and 8000
         * per day for every write the app makes; a flush leaves the rest of the window to the next
         * sync and to edits, and stops quietly as catching up when it reaches this.
         */
        const val MAX_CALLS_PER_FLUSH = 300

        private const val SECONDS_PER_HOUR = 3_600L
        private const val SECONDS_PER_DAY = 86_400L
    }
}

/**
 * Cuts records into Health Connect calls: in the given order, at most [MAX_RECORDS] records and
 * [MAX_BYTES] estimated per call ("chunk requests to at most 1000 records"; the platform refuses a
 * call over 5 MB, the estimate errs high). A single item above the byte bound gets a call of its
 * own.
 */
object Chunker {
    const val MAX_RECORDS = 1000
    const val MAX_BYTES = 4L * 1024 * 1024

    fun <T> chunks(items: List<T>, maxRecords: Int = MAX_RECORDS, maxBytes: Long = MAX_BYTES, bytesOf: (T) -> Long): List<List<T>> {
        require(maxRecords >= 1 && maxBytes >= 1) { "a call holds at least one record" }
        val out = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var bytes = 0L
        for (item in items) {
            val size = bytesOf(item)
            if (current.isNotEmpty() && (current.size == maxRecords || bytes + size > maxBytes)) {
                out += current
                current = mutableListOf()
                bytes = 0
            }
            current += item
            bytes += size
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}
