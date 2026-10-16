package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepHealthGate
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.containsAssertedTime
import io.github.opencircuit.ringkit.healthPublishable
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
 * variability, blood oxygen and breathing rate (one record per stored reading, [ScalarRecords]),
 * skin temperature (one record per night, [SkinTempNights]), then sleep ([sleep]): the settled
 * nights, the queued edits of edited nights, the naps (one session each, [SleepMapping]). The other
 * types follow the same steps.
 *
 * Sleep replaces by id: a night is one record `oc:sleep:<night key>`, a nap `oc:nap:<start>`, so a
 * changed night is written whole again under its id and Health Connect replaces it. An id is deleted
 * only when the night no longer has it (it moved to another key) or the nap is gone (an edit
 * absorbed it), always after the replacing write was accepted and by its clientRecordId alone; the
 * id is kept named in the store until its delete succeeds, so a failed delete never leaves Health
 * Connect emptier and is retried.
 */
class HealthWriter(
    private val port: HealthConnectPort,
    private val store: HealthSampleStore,
    private val generation: WriteGeneration,
    private val failureStore: HealthFailureStore,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    private val device: () -> RingDevice,
    private val sleep: HealthSleepStore = HealthSleepStore.NONE,
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
            writeSleep(granted)
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

        /** The settled nights, then the queued edits, then the naps: three independent steps. */
        private suspend fun writeSleep(granted: Set<HealthPermission>) {
            val nights = sleep.nights()
            writeSettledNights(nights, granted)
            writeQueuedEdits(nights, granted)
            writeNaps(granted)
        }

        /**
         * Upstream `mirrorSettledNight` (HKW:2260-2343) over ids. Every stored night that is not
         * edited (an edit owns its night), carries none of the wearer's own time (that is an edit's
         * too), and settled ([SleepHealthGate]: its last segment ended the margin ago) is written
         * whole as `oc:sleep:<key>` — unless Health Connect already holds it as staged now: the
         * signature of what was last written is the night's, and the night's only id is its own.
         * Upstream mirrors only the newest night a sync published; every stored night is a night a
         * sync staged (PORTING D-269), so each one is written here.
         */
        private suspend fun writeSettledNights(nights: List<StoredSleepNight>, granted: Set<HealthPermission>) {
            val type = RecordType.SLEEP_SESSION
            class Settled(val night: StoredSleepNight, val published: List<SleepSegment>, val id: String, val signature: String)
            val toWrite = nights.mapNotNull { n ->
                if (n.edited) return@mapNotNull null
                val published = n.timeline.healthPublishable
                if (published.isEmpty() || published.containsAssertedTime) return@mapNotNull null
                if (!SleepHealthGate.isReadyToWrite(published.maxOf { it.end }, now, finalized = false)) return@mapNotNull null
                val id = RecordIds.sleepNight(n.key)
                val signature = SleepSignature.of(n.timeline)
                if (sleep.mirror(n.night)?.signature == signature && sleep.recordIds(n.night) == listOf(id)) return@mapNotNull null
                Settled(n, published, id, signature)
            }
            if (toWrite.isEmpty()) return
            if (type.permission !in granted) {
                notGranted += type
                return
            }
            val v = version()
            val items = toWrite.mapNotNull { s ->
                val meta = RecordMeta(s.id, v, RecordingMethod.AUTOMATICALLY_RECORDED, device())
                val session = SleepMapping.session(s.published, SleepMapping.pathOf(s.published), meta, zone(), null) ?: return@mapNotNull null
                WriteItem(session, emptyList()) {
                    sleep.setMirror(s.night.night, NightMirror(s.signature, session.start, session.end))
                    replaceIds(type, s.night.night, s.id)
                    sleep.markNightWritten(s.published)
                }
            }
            writeItems(type, items)
        }

        /**
         * Upstream `reconcileEditedNightSleepLocked` (HKW:1981-2091) for each queued edit, oldest
         * night first: the edited picture is written as the night's record (the wearer's save is the
         * night's end, so no settle margin); then the ids the night no longer has are deleted and the
         * naps the edited window absorbed are removed; the edit marks move from what was written; the
         * item is cleared only if it is still the one written. An edit of a night no longer stored,
         * or with nothing in it, is dropped. Without the sleep grant the queue waits (upstream drops
         * it when sleep is denied). A queue this build cannot read is a failure of the type, and the
         * rest of the flush goes on.
         */
        private suspend fun writeQueuedEdits(nights: List<StoredSleepNight>, granted: Set<HealthPermission>) {
            val type = RecordType.SLEEP_SESSION
            val edits = try {
                sleep.queuedEdits()
            } catch (e: UnreadableEditQueueException) {
                failures[type] = e::class.simpleName ?: "unreadable"
                return
            }
            for (edit in edits) {
                if (catchingUp) return
                val night = nights.firstOrNull { it.night == edit.night }
                val published = edit.segments.healthPublishable
                if (night == null || published.isEmpty()) {
                    sleep.clearQueuedEdit(edit)
                    continue
                }
                if (type.permission !in granted) {
                    notGranted += type
                    return
                }
                val id = RecordIds.sleepNight(night.key)
                val meta = RecordMeta(id, version(), RecordingMethod.AUTOMATICALLY_RECORDED, device())
                val session = SleepMapping.session(published, SleepMapping.pathOf(published), meta, zone(), null)
                if (session == null) {
                    sleep.clearQueuedEdit(edit)
                    continue
                }
                if (HealthRanges.violation(session, now) != null) {
                    // Kept queued: a night ending ahead of the clock is written once it is past.
                    reject(type, 1)
                    continue
                }
                when (send(type, listOf(session))) {
                    Sent.ACCEPTED -> {
                        written[type] = (written[type] ?: 0) + 1
                        replaceIds(type, night.night, id)
                        sweepAbsorbedNaps(type, edit)
                        sleep.markEditWritten(night.night, published, night.recordedEnd)
                        sleep.clearQueuedEdit(edit)
                    }
                    Sent.REFUSED_RECORD -> reject(type, 1)
                    Sent.STOP -> return
                }
            }
        }

        /**
         * Upstream's sweep of the automatic naps an edit absorbed (HKW:2056-2072): a nap Health
         * Connect holds loses its record first, by its id, and only then its row; a nap never
         * written loses its row; a nap whose record cannot be deleted keeps its row, which still
         * names the record, for the next edit of the night.
         */
        private suspend fun sweepAbsorbedNaps(type: RecordType, edit: QueuedNightEdit) {
            val gone = sleep.autoNaps(edit.inBedStart, edit.sleepWake).filter { nap ->
                !nap.healthWritten || delete(type, listOf(RecordIds.nap(nap.start)))
            }
            if (gone.isNotEmpty()) sleep.deleteNaps(gone.map { it.start })
        }

        /**
         * Upstream `flushNaps` (HKW:633-667) over ids: each nap not yet written is one session
         * `oc:nap:<stored start>` titled "Nap" — its staging, or asleep over the window it shows —
         * and is marked once accepted. An edit keeps the stored start, so an edited nap replaces its
         * record under the same id and nothing is deleted. A nap the wearer added is a manual entry.
         */
        private suspend fun writeNaps(granted: Set<HealthPermission>) {
            val type = RecordType.SLEEP_SESSION
            val naps = sleep.pendingNaps()
            if (naps.isEmpty()) return
            if (type.permission !in granted) {
                notGranted += type
                return
            }
            val v = version()
            val items = naps.mapNotNull { nap ->
                val segments = (
                    nap.segments ?: listOf(
                        SleepSegment(nap.shownStart, nap.shownEnd, SleepStage.IN_BED),
                        SleepSegment(nap.shownStart, nap.shownEnd, SleepStage.ASLEEP_CORE),
                    )
                    ).healthPublishable
                val meta = if (nap.manuallyAdded) {
                    RecordMeta(RecordIds.nap(nap.start), v, RecordingMethod.MANUAL_ENTRY, null)
                } else {
                    RecordMeta(RecordIds.nap(nap.start), v, RecordingMethod.AUTOMATICALLY_RECORDED, device())
                }
                val session = SleepMapping.session(segments, SleepMapping.pathOf(segments), meta, zone(), NAP_TITLE) ?: return@mapNotNull null
                WriteItem(session, emptyList()) { sleep.markNapWritten(nap.start) }
            }
            writeItems(type, items)
        }

        /**
         * After [id] was accepted for [night]: every id the night named before stays named next to
         * [id] until its delete succeeds; then the night names [id] and what could not be deleted.
         * An unreadable stored list is read as none (it can only name an older id of this night).
         */
        private suspend fun replaceIds(type: RecordType, night: Instant, id: String) {
            val before = sleep.recordIds(night).orEmpty()
            val old = before.filter { it != id }
            if (old.isEmpty()) {
                if (before != listOf(id)) sleep.setRecordIds(night, listOf(id))
                return
            }
            sleep.setRecordIds(night, listOf(id) + old)
            if (delete(type, old)) sleep.setRecordIds(night, listOf(id))
        }

        /**
         * One delete call by clientRecordId, within the budget. A failed delete is not a write
         * failure: what it would remove stays named and is deleted on a later flush. Health
         * Connect asking to be called later ends the flush as catching up.
         */
        private suspend fun delete(type: RecordType, ids: List<String>): Boolean {
            if (catchingUp) return false
            if (calls >= MAX_CALLS_PER_FLUSH) {
                catchingUp = true
                return false
            }
            calls++
            return try {
                port.deleteByClientIds(type, ids)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: HealthRetryLaterException) {
                catchingUp = true
                false
            } catch (e: Exception) {
                false
            }
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
            for (item in items) item.afterAccepted?.invoke()
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

        /** A nap's session title (research Q11). */
        const val NAP_TITLE = "Nap"
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
