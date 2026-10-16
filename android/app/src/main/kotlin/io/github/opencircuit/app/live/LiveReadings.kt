package io.github.opencircuit.app.live

import io.github.opencircuit.app.sync.learningNights
import io.github.opencircuit.ringkit.ActivityPeriod
import io.github.opencircuit.ringkit.DeviceStatus
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.NightWindow
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.StepAccumulator
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StepBaseline
import io.github.opencircuit.store.StoreDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** Where the readings measured live are kept. */
interface LiveReadingStore {
    /** The step fold's baseline, or null when none is kept (or it cannot be read). */
    suspend fun stepBaseline(): StepBaseline?

    /** Saves the step fold's baseline. */
    suspend fun saveStepBaseline(baseline: StepBaseline)

    /** Adds [delta] steps read at [at], counted over `[windowStart, at]`, to that local day. */
    suspend fun addSteps(delta: Long, at: Instant, windowStart: Instant)

    /** Keeps heart rates, SpO₂ fractions and nightly skin temperatures measured live, outside the sync cursor. */
    suspend fun insertLive(samples: List<QuantitySample>)

    /** Keeps a worn skin temperature read by day, apart from the nightly ones. */
    suspend fun recordDaytimeTemperature(celsius: Double, at: Instant)

    /** The stored nights the night window is learned from (latest first). */
    suspend fun learningNights(): List<NightWindow.StoredNight>

    companion object {
        /** Keeps nothing: a session built without a store. */
        val NONE: LiveReadingStore = object : LiveReadingStore {
            override suspend fun stepBaseline(): StepBaseline? = null
            override suspend fun saveStepBaseline(baseline: StepBaseline) = Unit
            override suspend fun addSteps(delta: Long, at: Instant, windowStart: Instant) = Unit
            override suspend fun insertLive(samples: List<QuantitySample>) = Unit
            override suspend fun recordDaytimeTemperature(celsius: Double, at: Instant) = Unit
            override suspend fun learningNights(): List<NightWindow.StoredNight> = emptyList()
        }
    }
}

/** [LiveReadingStore] over the app's database. */
class StoreLiveReadings(
    private val database: suspend () -> StoreDatabase,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) : LiveReadingStore {
    override suspend fun stepBaseline(): StepBaseline? = BlobStore(database()).loadStepBaseline()

    override suspend fun saveStepBaseline(baseline: StepBaseline) = BlobStore(database()).saveStepBaseline(baseline, now())

    override suspend fun addSteps(delta: Long, at: Instant, windowStart: Instant) =
        LocalStore(database()).addDailySteps(delta, day = at, windowStart = windowStart, now = now(), zone = zone())

    override suspend fun insertLive(samples: List<QuantitySample>) {
        LocalStore(database()).insertLiveSamples(samples, now())
    }

    override suspend fun recordDaytimeTemperature(celsius: Double, at: Instant) = LocalStore(database()).recordDaytimeTemperature(celsius, at)

    override suspend fun learningNights(): List<NightWindow.StoredNight> = learningNights(database())
}

/**
 * Keeps what the ring measures live, so the Health Connect write has it (upstream
 * `ios/OpenCircuit/BLE/RingSession.swift` @ b1c2fdd):
 *
 * - **Steps** — each `0x10` / `0x87` descriptor's quarter-hour bucket (`[4:6]`) is folded by
 *   [StepAccumulator] against the stored baseline (RS:4905-4986): the increment while the bucket
 *   climbs, the new bucket whole when it rolled or the local day turned; the delta is stored with
 *   its window, then the baseline moves to this reading, so a relaunch never credits a bucket twice.
 * - **Skin temperature** — a worn reading (at least [ActivityPeriod.WORN_MIN_TEMPERATURE_C]) inside
 *   the night window is a nightly reading; a worn one by day goes to the daytime table only; a cold
 *   one (charger, off the finger) is kept nowhere (RS:4986-5032).
 * - **Heart rate / SpO₂** — when a measure stops, the settled heart rate or the last SpO₂ (as a
 *   fraction), stamped when it was measured (RS:3430-3477).
 *
 * Heart rate, SpO₂ and skin temperature are kept outside the sync cursor (PORTING.md D-293), so a
 * later drain keeps the ring's older backlog. The frame handlers return at once: the readings are
 * kept in arrival order by one coroutine on [scope]; a store write that fails is logged by its kind
 * and the next reading goes on (the baseline then stays at the last reading kept).
 */
class LiveReadings(
    private val store: LiveReadingStore,
    scope: CoroutineScope,
    private val wallClock: () -> Instant,
    private val zone: () -> ZoneId,
    private val log: (String) -> Unit,
) {
    private sealed interface Reading

    private class Descriptor(val steps: Int?, val celsius: Double?, val at: Instant) : Reading

    private class Measured(val sample: QuantitySample) : Reading

    private val readings = Channel<Reading>(Channel.UNLIMITED)

    // Read and written only by the one coroutine that keeps the readings.
    private var baseline: StepBaseline? = null
    private var baselineRead = false

    init {
        if (store !== LiveReadingStore.NONE) {
            scope.launch {
                for (reading in readings) keep(reading)
            }
        }
    }

    /** A `0x10` / `0x87` descriptor from the ring, routed here by the session's dispatcher. */
    fun onDescriptor(frame: ByteArray) {
        if (store === LiveReadingStore.NONE) return
        val steps = DeviceStatus.steps(frame)
        val celsius = DeviceStatus.skinTemperature(frame)?.celsius
        if (steps == null && celsius == null) return
        readings.trySend(Descriptor(steps, celsius, wallClock()))
    }

    /** A measure stopped with [value] (bpm, or SpO₂ %) of [mode], measured at [at]. */
    fun onMeasured(mode: LiveMode, value: Int, at: Instant) {
        if (store === LiveReadingStore.NONE) return
        val sample = when (mode) {
            LiveMode.HEART_RATE -> QuantitySample(MetricKind.HEART_RATE, at, value = value.toDouble())
            LiveMode.SPO2 -> QuantitySample(MetricKind.SPO2, at, value = value / 100.0)
        }
        readings.trySend(Measured(sample))
    }

    private suspend fun keep(reading: Reading) {
        try {
            when (reading) {
                is Measured -> store.insertLive(listOf(reading.sample))
                is Descriptor -> {
                    reading.steps?.let { foldSteps(it, reading.at) }
                    reading.celsius?.let { keepTemperature(it, reading.at) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("live readings: a reading could not be kept (${e::class.java.simpleName})")
        }
    }

    private suspend fun foldSteps(raw: Int, at: Instant) {
        val zone = zone()
        val previous = if (baselineRead) baseline else store.stepBaseline().also { baselineRead = true }
        val day = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val dayChanged = previous != null && previous.day != day
        val update = StepAccumulator.update(previous?.raw, raw, dayChanged)
        if (update.deltaToAdd > 0) {
            val windowStart = StepAccumulator.windowStart(at, if (dayChanged) null else previous?.sampleAt, day, zone)
            store.addSteps(update.deltaToAdd.toLong(), at, windowStart)
        }
        val next = StepBaseline(raw, day, at)
        store.saveStepBaseline(next)
        baseline = next
    }

    private suspend fun keepTemperature(celsius: Double, at: Instant) {
        if (celsius < ActivityPeriod.WORN_MIN_TEMPERATURE_C) return
        val night = NightWindow.resolve(store.learningNights(), at, zone())?.window
        if (night != null && at in night) {
            store.insertLive(listOf(QuantitySample(MetricKind.TEMPERATURE, at, value = celsius)))
        } else {
            store.recordDaytimeTemperature(celsius, at)
        }
    }
}
