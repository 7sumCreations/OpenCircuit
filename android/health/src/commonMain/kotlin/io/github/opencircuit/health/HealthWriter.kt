package io.github.opencircuit.health

import io.github.opencircuit.ringkit.DateInterval
import io.github.opencircuit.ringkit.HRSample
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.RestingHR
import io.github.opencircuit.ringkit.SleepHealthGate
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.StepWindow
import io.github.opencircuit.ringkit.UserProfile
import io.github.opencircuit.ringkit.containsAssertedTime
import io.github.opencircuit.ringkit.healthPublishable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
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

    /**
     * Whether a sample of [kind] above zero, newer than the kind's mark and not after [upTo], is
     * stored: asked instead of reading them all for a type the user has not allowed.
     */
    suspend fun hasPending(kind: MetricKind, upTo: Instant): Boolean = pendingSamples(kind).any { !it.start.isAfter(upTo) }

    /**
     * A watermark of what is stored now (the store's highest row id), read before a flush reads what
     * is pending; null for a store that keeps none.
     */
    suspend fun rowWatermark(): Long? = null

    /**
     * As [markWritten], by a flush that read what is pending after [seenWatermark]: a mark never
     * passes a sample stored after it (a sync or a live reading landing while the flush sends), so
     * that sample stays pending.
     */
    suspend fun markWritten(samples: List<QuantitySample>, seenWatermark: Long?) = markWritten(samples)
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
 * nights, the queued edits of edited nights, the naps (one session each, [SleepMapping]); with an
 * activity store, steps then distance, resting heart rate, basal metabolic rate and active energy;
 * last, the logged periods ([writePeriods], [PeriodRecords]). The other types follow the same steps.
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
    private val activity: HealthActivityStore = HealthActivityStore.NONE,
    private val profile: () -> UserProfile = { EnergyProfile.DEFAULT },
    private val periods: HealthPeriodStore = HealthPeriodStore.NONE,
) {
    private val lock = Mutex()

    /** Writes everything pending; a flush that starts while another runs waits for it, then runs. */
    suspend fun flush(): FlushResult = lock.withLock { Pass(clock()).run() }

    /**
     * Deletes the records a deleted logged period named (`LocalStore.deletePeriodEntry` returns
     * them; upstream `deleteMenstrualFlowSamples`, HKW:848-853), by clientRecordId, each under its
     * own type, between flushes. False when a call failed: the caller keeps the ids to try again.
     */
    suspend fun deletePeriodRecords(ids: List<String>): Boolean = lock.withLock {
        val (flows, others) = ids.partition(PeriodRecords::isFlowId)
        listOf(RecordType.MENSTRUATION_FLOW to flows, RecordType.MENSTRUATION_PERIOD to others)
            .filter { it.second.isNotEmpty() }
            .map { (type, named) ->
                try {
                    port.deleteByClientIds(type, named)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            .all { it }
    }

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

        /** What the store held before this flush read anything: a sample stored later is never marked by it. */
        private var watermark: Long? = null

        suspend fun run(): FlushResult {
            if (port.sdkStatus() != HealthSdkStatus.AVAILABLE) return FlushResult(finishedAt = clock(), available = false, failureMap = failureStore.load())
            watermark = store.rowWatermark()
            val granted = port.grantedWrites()
            writeHeartRate(granted)
            for ((kind, type) in ScalarRecords.TYPES) writeScalar(kind, type, granted)
            writeSkinTemperature(granted)
            writeSleep(granted)
            // Steps, the daily records and energy keep their marks in the activity store: none without one.
            if (activity !== HealthActivityStore.NONE) {
                writeStepsThenDistance(granted)
                writeRestingHeartRate(granted)
                writeBasalMetabolicRate(granted)
                writeActiveEnergy(granted)
            }
            writePeriods(granted)
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

        /** For a type not allowed: notes it when something waits, without reading what waits. */
        private suspend fun notAllowed(type: RecordType, waiting: suspend () -> Boolean) {
            if (waiting()) notGranted += type
        }

        private suspend fun writeHeartRate(granted: Set<HealthPermission>) {
            val type = RecordType.HEART_RATE
            if (type.permission !in granted) return notAllowed(type) { store.hasPending(MetricKind.HEART_RATE, now) }
            val hourStarts = HrHours.hoursToRebuild(store.pendingSamples(MetricKind.HEART_RATE), now)
            if (hourStarts.isEmpty()) return
            // One read covers every hour: everything after the first hour's start is in a pending hour or before the mark in it.
            val stored = store.samples(MetricKind.HEART_RATE, hourStarts.first(), hourStarts.last().plusSeconds(SECONDS_PER_HOUR))
                .groupBy { HrHours.hourStart(it.start) }
            val v = version()
            val hours = hourStarts.mapNotNull { h -> HrHours.hour(h, stored[h].orEmpty(), now, zone(), device(), v) }
            writeItems(type, hours.map { WriteItem(it.record, it.samples) })
        }

        /** Heart-rate variability, blood oxygen or breathing rate: one record per pending stored sample. */
        private suspend fun writeScalar(kind: MetricKind, type: RecordType, granted: Set<HealthPermission>) {
            if (type.permission !in granted) return notAllowed(type) { store.hasPending(kind, now) }
            val pending = store.pendingRows(kind).filter { !it.sample.start.isAfter(now) }
            if (pending.isEmpty()) return
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
            val allowed = type.permission in granted
            // Not allowed: only whether anything waits, never every held reading.
            val pending = if (allowed) store.pendingSamples(MetricKind.TEMPERATURE).filter { !it.start.isAfter(now) } else emptyList()
            if (if (allowed) pending.isEmpty() else !store.hasPending(MetricKind.TEMPERATURE, now)) return
            val supported = try {
                port.featureAvailable(HealthFeature.SKIN_TEMPERATURE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HealthRetryLaterException) {
                catchingUp = true
                return
            } catch (e: Exception) {
                // A failed check fails this type only; the kind of failure, never a value.
                failures[type] = e::class.simpleName ?: "failure"
                return
            }
            if (!supported) {
                unsupported += type
                return
            }
            if (!allowed) {
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

        /** The stored nights with their timelines, read once per flush (sleep and resting heart rate both use them). */
        private var storedNights: List<StoredSleepNight>? = null

        private suspend fun storedNights(): List<StoredSleepNight> = storedNights ?: sleep.nights().also { storedNights = it }

        /** The settled nights, then the queued edits, then the naps: three independent steps. */
        private suspend fun writeSleep(granted: Set<HealthPermission>) {
            val nights = storedNights()
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
         * or with nothing in it, is dropped; so is one breaking a platform rule for good (counted as
         * rejected), while one starting ahead of the clock waits. Without the sleep grant the queue waits (upstream drops
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
                if (session.start.isAfter(now)) continue // kept queued: written once the clock passes its start
                if (HealthRanges.violation(session, now) != null) {
                    // A rule broken for good: the edit can never be written, so it is dropped, counted once.
                    reject(type, 1)
                    sleep.clearQueuedEdit(edit)
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
                    Sent.REFUSED_RECORD -> {
                        // Refused by the platform for a rule our table missed: as above, dropped once.
                        reject(type, 1)
                        sleep.clearQueuedEdit(edit)
                    }
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
                WriteItem(session, emptyList()) { sleep.markNapWritten(nap) }
            }
            writeItems(type, items)
        }

        /**
         * Upstream's steps then distance (HKW:538-598): each pending step row (its start not ahead of
         * the clock) is one steps record over its own window ([ActivityRecords.stepItems]); the rows
         * are marked when Health Connect accepted their record. A record breaking a platform rule (a
         * window with no length) is dropped, counted and its rows marked: it can never be written.
         * Only then the distance of each accepted steps record, under the same version: distance has
         * no mark of its own, so it is not written when its steps were not, and steps accepted while
         * distance is not allowed are not caught up later (upstream's tradeoff).
         */
        private suspend fun writeStepsThenDistance(granted: Set<HealthPermission>) {
            val type = RecordType.STEPS
            if (type.permission !in granted) return notAllowed(type) { activity.hasPendingSteps(now) }
            val pending = activity.pendingSteps().filter { !it.start.isAfter(now) }
            if (pending.isEmpty()) return
            // Every stored row at the pending starts: rows that share a start are one record.
            val atStarts = activity.steps(pending.first().start, pending.last().start.plusMillis(1))
            val v = version()
            val (broken, valid) = ActivityRecords.stepItems(pending, atStarts, zone(), device(), v)
                .partition { HealthRanges.violation(it.record, now) != null }
            if (broken.isNotEmpty()) {
                reject(type, broken.size)
                activity.markStepsWritten(broken.flatMap { it.rowIds })
            }
            val accepted = mutableListOf<Steps>()
            writeItems(type, valid.map { item -> WriteItem(item.record, emptyList()) { activity.markStepsWritten(item.rowIds); accepted += item.record } })
            if (accepted.isEmpty()) return
            val distance = RecordType.DISTANCE
            if (distance.permission !in granted) {
                notGranted += distance
                return
            }
            writeItems(distance, accepted.map { WriteItem(ActivityRecords.distanceOf(it, v), emptyList()) })
        }

        /**
         * Upstream `flushRestingHR` (HKW:1406-1437): one record per final day after the day mark
         * ([DailyRecords.daysToWrite], 7 days back at most), valued by `RestingHR.dailyValues` over
         * the stored heart rate and the stored nights' stages (the mean asleep heart rate where a
         * night was staged, else the lowest sustained window). A day without readings gets none.
         * The mark moves to each day Health Connect accepted.
         */
        private suspend fun writeRestingHeartRate(granted: Set<HealthPermission>) {
            val type = RecordType.RESTING_HEART_RATE
            val zone = zone()
            val days = DailyRecords.daysToWrite(activity.dayMark(type), now, zone, firstRunFromToday = false)
            if (days.isEmpty()) return
            val from = days.first().atStartOfDay(zone).toInstant()
            val heartRate = store.samples(MetricKind.HEART_RATE, from, now).map { HRSample(it.value.toInt(), it.start, it.end) }
            if (heartRate.isEmpty()) return
            val stages = storedNights().flatMap { it.timeline }.filter { it.end.isAfter(from) }
            val starts = days.associateBy { it.atStartOfDay(zone).toInstant() }
            val values = RestingHR.dailyValues(heartRate, stages, zone).mapNotNull { v -> starts[v.day]?.let { it to v.bpm } }
            if (values.isEmpty()) return
            if (type.permission !in granted) {
                notGranted += type
                return
            }
            val v = version()
            val ring = device()
            writeItems(type, values.map { (day, bpm) -> WriteItem(DailyRecords.restingHeartRate(day, zone, bpm, ring, v), emptyList()) { activity.setDayMark(type, day) } })
        }

        /**
         * One basal-metabolic-rate record per final day after the day mark (PORTING.md D-296; upstream
         * writes basal energy per hour, HKW:1453-1483): a first run writes today only, as upstream's
         * meter starts at the current hour; each day valued [DailyRecords.basalKcalPerDay] with the
         * resting heart rate of [DailyRecords.BASAL_LOOKBACK_DAYS] days of stored heart rate.
         */
        private suspend fun writeBasalMetabolicRate(granted: Set<HealthPermission>) {
            val type = RecordType.BASAL_METABOLIC_RATE
            val zone = zone()
            val days = DailyRecords.daysToWrite(activity.dayMark(type), now, zone, firstRunFromToday = true)
            if (days.isEmpty()) return
            if (type.permission !in granted) {
                notGranted += type
                return
            }
            val from = days.first().minusDays(DailyRecords.BASAL_LOOKBACK_DAYS).atStartOfDay(zone).toInstant()
            val heartRate = store.samples(MetricKind.HEART_RATE, from, now).map { HRSample(it.value.toInt(), it.start, it.end) }
            val daily = RestingHR.dailyValues(heartRate, emptyList(), zone)
            val v = version()
            val ring = device()
            val profile = profile()
            writeItems(
                type,
                days.map { day ->
                    val kcal = DailyRecords.basalKcalPerDay(day.atStartOfDay(zone).toInstant(), daily, profile)
                    WriteItem(DailyRecords.basalMetabolicRate(day, zone, kcal, ring, v), emptyList()) { activity.setDayMark(type, day) }
                },
            )
        }

        /**
         * Active energy as absolute per-bucket records ([ActiveEnergyDays], PORTING.md D-295): each of
         * the last [ActiveEnergyDays.LOOKBACK_DAYS] local days is estimated again from what the store
         * holds — its heart rate, its steps and their windows, the night that ended in it (its heart
         * rate is not exercise) — and every bucket whose kilocalories changed is written under its id;
         * what each accepted bucket carries is kept per day, so the next flush writes only changes and
         * can lower a bucket to 0. What was kept for older days is forgotten.
         */
        private suspend fun writeActiveEnergy(granted: Set<HealthPermission>) {
            val type = RecordType.ACTIVE_CALORIES_BURNED
            val zone = zone()
            val today = now.atZone(zone).toLocalDate()
            val first = today.minusDays(ActiveEnergyDays.LOOKBACK_DAYS - 1)
            activity.forgetEnergyBefore(first)
            val nights = store.nights(first.minusDays(2).atStartOfDay(zone).toInstant(), today.plusDays(2).atStartOfDay(zone).toInstant())
            val perDay = LinkedHashMap<LocalDate, Pair<Map<Instant, Double>, List<ActiveEnergyDays.Change>>>()
            for (offset in 0 until ActiveEnergyDays.LOOKBACK_DAYS) {
                val day = first.plusDays(offset)
                val dayStart = day.atStartOfDay(zone).toInstant()
                val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
                val inputs = ActiveEnergyDays.DayInputs(
                    heartRate = store.samples(MetricKind.HEART_RATE, dayStart, dayEnd).map { HRSample(it.value.toInt(), it.start, it.end) },
                    steps = activity.daySteps(day),
                    stepWindows = activity.steps(dayStart.minusSeconds(SECONDS_PER_DAY), dayEnd)
                        .map { StepWindow(it.start, it.end, it.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) },
                    sleepWindow = nights.filter { it.inBedEnd.isAfter(it.inBedStart) && !it.inBedEnd.isBefore(dayStart) && it.inBedEnd.isBefore(dayEnd) }
                        .maxByOrNull { it.inBedEnd }?.let { DateInterval(it.inBedStart, it.inBedEnd) },
                )
                val written = activity.energyWritten(day)
                val changes = ActiveEnergyDays.changes(day, zone, ActiveEnergyDays.buckets(day, zone, inputs, profile()), written, now)
                if (changes.isNotEmpty()) perDay[day] = written to changes
            }
            if (perDay.isEmpty()) return
            if (type.permission !in granted) {
                notGranted += type
                return
            }
            val v = version()
            val ring = device()
            val items = perDay.flatMap { (day, writtenAndChanges) ->
                val kept = writtenAndChanges.first.toMutableMap()
                writtenAndChanges.second.map { change ->
                    WriteItem(change.record(zone, ring, v), emptyList()) {
                        kept[change.start] = change.keptValue
                        activity.setEnergyWritten(day, kept.toMap())
                    }
                }
            }
            writeItems(type, items)
        }

        /**
         * Upstream `flushMenstrualFlow` (HKW:727-791) over ids, for every period the store asks for
         * that is not up to date ([PeriodRecords.isUpToDate]) and covers a day: its records
         * ([PeriodRecords.build]) are named in the store next to what it named before, BEFORE the
         * calls, so a kill after a call leaves only named records; then its flow days, then its
         * period record(s); then, once both were accepted, the ids it no longer has (days an edit
         * removed) are deleted by id; it is finalized when nothing is left to delete, else it stays
         * pending with those ids named and the delete is tried again. A call that fails ends the
         * step (upstream's break), every id still named (upstream rolls its list back to the old
         * UUIDs, which a lost reply would strand; ids here repeat, so the list cannot grow).
         */
        private suspend fun writePeriods(granted: Set<HealthPermission>) {
            val zone = zone()
            val due = periods.periodsNeedingMirror(now, zone)
                .filter { !PeriodRecords.isUpToDate(it, now, zone) && PeriodRecords.daysToCover(it, now, zone) > 0 }
            if (due.isEmpty()) return
            if (HealthPermission.MENSTRUATION !in granted) {
                notGranted += RecordType.MENSTRUATION_FLOW
                notGranted += RecordType.MENSTRUATION_PERIOD
                return
            }
            val v = version()
            for (entry in due) {
                if (catchingUp) return
                val built = PeriodRecords.build(entry, now, zone, v)
                val fresh = built.ids
                periods.recordWrittenIds(entry, (entry.writtenIds + fresh).distinct(), finalized = false)
                if (!writeItems(RecordType.MENSTRUATION_FLOW, built.flows.map { WriteItem(it, emptyList()) })) return
                if (!writeItems(RecordType.MENSTRUATION_PERIOD, built.periods.map { WriteItem(it, emptyList()) })) return
                val (goneFlows, goneOthers) = entry.writtenIds.filter { it !in fresh }.distinct().partition(PeriodRecords::isFlowId)
                val undeleted = buildList {
                    if (goneFlows.isNotEmpty() && !delete(RecordType.MENSTRUATION_FLOW, goneFlows)) addAll(goneFlows)
                    if (goneOthers.isNotEmpty() && !delete(RecordType.MENSTRUATION_PERIOD, goneOthers)) addAll(goneOthers)
                }
                periods.recordWrittenIds(entry, fresh + undeleted, finalized = undeleted.isEmpty())
            }
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
         * [Chunker]'s size, marking each call's samples once Health Connect accepted it. False when
         * the type's writing stopped before the end (not granted, failed, or the flush catching up);
         * true when every item was accepted or dropped for good.
         */
        private suspend fun writeItems(type: RecordType, items: List<WriteItem>): Boolean {
            if (catchingUp) return false
            val kept = items.filter { HealthRanges.violation(it.record, now) == null }
            reject(type, items.size - kept.size)
            for (chunk in Chunker.chunks(kept) { RecordSize.estimateBytes(it.record) }) {
                when (send(type, chunk.map { it.record })) {
                    Sent.ACCEPTED -> accepted(type, chunk)
                    Sent.STOP -> return false
                    // The platform refused a record our table let through: try each record once on
                    // its own, so only the bad one is dropped (each try is a call of the budget).
                    Sent.REFUSED_RECORD -> if (chunk.size == 1) reject(type, 1) else if (!isolate(type, chunk)) return false
                }
            }
            return true
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
            store.markWritten(items.flatMap { it.samples }, watermark)
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

        /** A nap's session title. */
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
