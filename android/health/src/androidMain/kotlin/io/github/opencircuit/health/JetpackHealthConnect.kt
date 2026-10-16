package io.github.opencircuit.health

import android.content.Context
import android.health.connect.HealthConnectException
import android.os.UserManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * [HealthConnectPort] over Jetpack Health Connect (connect-client 1.1.0), the only code in the
 * project that talks to Health Connect. It maps our records to Jetpack's and the platform's
 * failures to the port's: a missing grant → [HealthNotGrantedException]; a record the platform
 * refuses (when it is built or when it is inserted) → [HealthInvalidRecordException]; a refusal
 * for now (the write quota, a backup or restore in progress) → [HealthRetryLaterException]; any
 * other failure → [HealthCallFailedException]. The writer's decisions (what to send, what to mark)
 * are all in common code, tested on the JVM; nothing here decides anything but the mapping.
 *
 * The app holds WRITE permissions only. Reading back its own records needs no READ permission but
 * must be filtered to its own package (measured on Android 16: an unfiltered read is refused as a
 * read of other apps' data), and comes in pages of at most [PAGE_SIZE].
 *
 * Heart rate, heart-rate variability (RMSSD), blood oxygen and breathing rate are written today;
 * the other types arrive with their mappers.
 */
class JetpackHealthConnect(context: Context) : HealthConnectPort {
    private val appContext: Context = context.applicationContext
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    override suspend fun sdkStatus(): HealthSdkStatus =
        if (HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE) HealthSdkStatus.AVAILABLE else HealthSdkStatus.UNAVAILABLE

    override suspend fun grantedWrites(): Set<HealthPermission> = call(type = null) {
        val granted = client.permissionController.getGrantedPermissions()
        HealthPermission.entries.filter { it.permission in granted }.toSet()
    }

    override suspend fun featureAvailable(feature: HealthFeature): Boolean = call(type = null) {
        when (feature) {
            HealthFeature.SKIN_TEMPERATURE ->
                client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }
    }

    override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> = call(type) {
        // Built inside the call: a record the platform refuses when it is built fails the call the same way.
        val jetpack = records.map { toJetpack(type, it) }
        client.insertRecords(jetpack).recordIdsList
    }

    override suspend fun deleteByClientIds(type: RecordType, clientRecordIds: List<String>): Unit = call(type) {
        client.deleteRecords(recordClass(type), recordIdsList = emptyList(), clientRecordIdsList = clientRecordIds)
    }

    override suspend fun readOwn(type: RecordType, from: Instant, until: Instant): List<HealthRecord> = call(type) {
        readAllOwn(recordClass(type), from, until).map { fromJetpack(type, it) }
    }

    /** Every page of this app's own records of [kind] in [from, until). */
    private suspend fun <T : Record> readAllOwn(kind: KClass<T>, from: Instant, until: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = kind,
                    timeRangeFilter = TimeRangeFilter.between(from, until),
                    dataOriginFilter = setOf(DataOrigin(appContext.packageName)),
                    ascendingOrder = true,
                    pageSize = PAGE_SIZE,
                    pageToken = token,
                ),
            )
            out += page.records
            token = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return out
    }

    /** Runs one platform call, turning its failure into the port's. */
    private suspend fun <T> call(type: RecordType?, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HealthInvalidRecordException) {
            throw e
        } catch (e: HealthCallFailedException) {
            throw e
        } catch (e: SecurityException) {
            if (type != null) throw HealthNotGrantedException(type.permission, "${type.permission.permission} is not granted")
            throw HealthCallFailedException("Health Connect refused the call", e)
        } catch (e: IllegalArgumentException) {
            throw HealthInvalidRecordException(e.message ?: "a record breaks a platform rule")
        } catch (e: IllegalStateException) {
            // Jetpack wraps every platform error it has no type for; the code tells the retryable ones apart.
            val error = when ((e.cause as? HealthConnectException)?.errorCode) {
                HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED -> PlatformError.RATE_LIMIT_EXCEEDED
                HealthConnectException.ERROR_DATA_SYNC_IN_PROGRESS -> PlatformError.DATA_SYNC_IN_PROGRESS
                else -> PlatformError.OTHER
            }
            // The platform refuses a user who is not in front with no code of its own: ask Android who is.
            val inFront = appContext.getSystemService(UserManager::class.java)?.isUserForeground ?: true
            val reason = HealthErrors.retryReason(error, inFront) ?: throw HealthCallFailedException("Health Connect could not complete the call", e)
            throw HealthRetryLaterException(reason, "Health Connect asked to be called later: $reason")
        } catch (e: Exception) {
            throw HealthCallFailedException("Health Connect could not complete the call", e)
        }
    }

    companion object {
        /** The most records one read returns (the platform's page cap, measured on Android 16). */
        const val PAGE_SIZE = 1000

        /**
         * The contract that shows Health Connect's own permission screen for the platform
         * permission strings it is given. It must be launched through the activity result
         * registry: on Android 14 and later its intent's action is handled only there (a plain
         * startActivityForResult finds no activity).
         */
        fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()

        private fun recordClass(type: RecordType): KClass<out Record> = when (type) {
            RecordType.HEART_RATE -> HeartRateRecord::class
            RecordType.HEART_RATE_VARIABILITY_RMSSD -> HeartRateVariabilityRmssdRecord::class
            RecordType.OXYGEN_SATURATION -> OxygenSaturationRecord::class
            RecordType.RESPIRATORY_RATE -> RespiratoryRateRecord::class
            RecordType.SKIN_TEMPERATURE -> SkinTemperatureRecord::class
            RecordType.SLEEP_SESSION -> SleepSessionRecord::class
            RecordType.STEPS -> StepsRecord::class
            RecordType.DISTANCE -> DistanceRecord::class
            RecordType.ACTIVE_CALORIES_BURNED -> ActiveCaloriesBurnedRecord::class
            RecordType.BASAL_METABOLIC_RATE -> BasalMetabolicRateRecord::class
            RecordType.RESTING_HEART_RATE -> RestingHeartRateRecord::class
            RecordType.MENSTRUATION_FLOW -> MenstruationFlowRecord::class
            RecordType.MENSTRUATION_PERIOD -> MenstruationPeriodRecord::class
        }

        private fun device(ring: RingDevice): Device = Device(type = Device.TYPE_RING, manufacturer = RingDevice.MANUFACTURER, model = ring.model)

        private fun metadata(meta: RecordMeta): Metadata = when (meta.method) {
            RecordingMethod.AUTOMATICALLY_RECORDED ->
                Metadata.autoRecorded(device(requireNotNull(meta.device) { "a ring record names its ring" }), meta.clientRecordId, meta.version)
            RecordingMethod.MANUAL_ENTRY -> {
                val ring = meta.device
                if (ring == null) Metadata.manualEntry(meta.clientRecordId, meta.version) else Metadata.manualEntry(meta.clientRecordId, meta.version, device(ring))
            }
        }

        private fun toJetpack(type: RecordType, record: HealthRecord): Record {
            if (record.type != type) throw HealthInvalidRecordException("a ${record.type} record in a $type call")
            return when (record) {
                is HeartRateSeries -> HeartRateRecord(
                    startTime = record.start,
                    startZoneOffset = record.startZoneOffset,
                    endTime = record.end,
                    endZoneOffset = record.endZoneOffset,
                    samples = record.samples.map { HeartRateRecord.Sample(time = it.time, beatsPerMinute = it.beatsPerMinute) },
                    metadata = metadata(record.meta),
                )
                is HeartRateVariabilityRmssd -> HeartRateVariabilityRmssdRecord(record.time, record.zoneOffset, record.millis, metadata(record.meta))
                is OxygenSaturation -> OxygenSaturationRecord(record.time, record.zoneOffset, Percentage(record.percent), metadata(record.meta))
                is RespiratoryRate -> RespiratoryRateRecord(record.time, record.zoneOffset, record.breathsPerMinute, metadata(record.meta))
                is SkinTemperature -> SkinTemperatureRecord(
                    startTime = record.start,
                    startZoneOffset = record.startZoneOffset,
                    endTime = record.end,
                    endZoneOffset = record.endZoneOffset,
                    metadata = metadata(record.meta),
                    deltas = record.deltas.map { SkinTemperatureRecord.Delta(it.time, TemperatureDelta.celsius(it.deltaCelsius)) },
                    baseline = record.baselineCelsius?.let { Temperature.celsius(it) },
                    measurementLocation = when (record.location) {
                        SkinTemperatureLocation.UNKNOWN -> SkinTemperatureRecord.MEASUREMENT_LOCATION_UNKNOWN
                        SkinTemperatureLocation.FINGER -> SkinTemperatureRecord.MEASUREMENT_LOCATION_FINGER
                        SkinTemperatureLocation.TOE -> SkinTemperatureRecord.MEASUREMENT_LOCATION_TOE
                        SkinTemperatureLocation.WRIST -> SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST
                    },
                )
                // Our stage kinds carry the platform's own numbers (SleepSessionRecord.STAGE_TYPE_*).
                is SleepSession -> SleepSessionRecord(
                    startTime = record.start,
                    startZoneOffset = record.startZoneOffset,
                    endTime = record.end,
                    endZoneOffset = record.endZoneOffset,
                    metadata = metadata(record.meta),
                    title = record.title,
                    notes = record.notes,
                    stages = record.stages.map { SleepSessionRecord.Stage(startTime = it.start, endTime = it.end, stage = it.stage.platformCode) },
                )
                is Steps -> StepsRecord(
                    startTime = record.start,
                    startZoneOffset = record.startZoneOffset,
                    endTime = record.end,
                    endZoneOffset = record.endZoneOffset,
                    count = record.count,
                    metadata = metadata(record.meta),
                )
                is Distance -> DistanceRecord(
                    startTime = record.start,
                    startZoneOffset = record.startZoneOffset,
                    endTime = record.end,
                    endZoneOffset = record.endZoneOffset,
                    distance = Length.meters(record.meters),
                    metadata = metadata(record.meta),
                )
                is ActiveCaloriesBurned -> ActiveCaloriesBurnedRecord(
                    startTime = record.start,
                    startZoneOffset = record.startZoneOffset,
                    endTime = record.end,
                    endZoneOffset = record.endZoneOffset,
                    energy = Energy.kilocalories(record.kilocalories),
                    metadata = metadata(record.meta),
                )
                is BasalMetabolicRate -> BasalMetabolicRateRecord(record.time, record.zoneOffset, Power.kilocaloriesPerDay(record.kilocaloriesPerDay), metadata(record.meta))
                is RestingHeartRate -> RestingHeartRateRecord(record.time, record.zoneOffset, record.beatsPerMinute, metadata(record.meta))
                // Our flow levels carry the platform's own numbers (MenstruationFlowRecord.FLOW_*).
                is MenstruationFlow -> MenstruationFlowRecord(time = record.time, zoneOffset = record.zoneOffset, metadata = metadata(record.meta), flow = record.flow.platformCode)
                is MenstruationPeriod -> MenstruationPeriodRecord(
                    startTime = record.start,
                    startZoneOffset = record.startZoneOffset,
                    endTime = record.end,
                    endZoneOffset = record.endZoneOffset,
                    metadata = metadata(record.meta),
                )
            }
        }

        private fun fromJetpack(type: RecordType, record: Record): HealthRecord = when (record) {
            is HeartRateRecord -> HeartRateSeries(
                start = record.startTime,
                startZoneOffset = requireNotNull(record.startZoneOffset) { "every record the app writes carries its zone offsets" },
                end = record.endTime,
                endZoneOffset = requireNotNull(record.endZoneOffset) { "every record the app writes carries its zone offsets" },
                samples = record.samples.map { HeartRateSample(it.time, it.beatsPerMinute) },
                meta = metaOf(record.metadata),
            )
            is HeartRateVariabilityRmssdRecord -> HeartRateVariabilityRmssd(record.time, offsetOf(record.zoneOffset), record.heartRateVariabilityMillis, metaOf(record.metadata))
            is OxygenSaturationRecord -> OxygenSaturation(record.time, offsetOf(record.zoneOffset), record.percentage.value, metaOf(record.metadata))
            is RespiratoryRateRecord -> RespiratoryRate(record.time, offsetOf(record.zoneOffset), record.rate, metaOf(record.metadata))
            is SkinTemperatureRecord -> SkinTemperature(
                start = record.startTime,
                startZoneOffset = offsetOf(record.startZoneOffset),
                end = record.endTime,
                endZoneOffset = offsetOf(record.endZoneOffset),
                baselineCelsius = record.baseline?.inCelsius,
                deltas = record.deltas.map { SkinTemperatureDelta(it.time, it.delta.inCelsius) },
                location = when (record.measurementLocation) {
                    SkinTemperatureRecord.MEASUREMENT_LOCATION_FINGER -> SkinTemperatureLocation.FINGER
                    SkinTemperatureRecord.MEASUREMENT_LOCATION_TOE -> SkinTemperatureLocation.TOE
                    SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST -> SkinTemperatureLocation.WRIST
                    else -> SkinTemperatureLocation.UNKNOWN
                },
                meta = metaOf(record.metadata),
            )
            is SleepSessionRecord -> SleepSession(
                start = record.startTime,
                startZoneOffset = offsetOf(record.startZoneOffset),
                end = record.endTime,
                endZoneOffset = offsetOf(record.endZoneOffset),
                title = record.title,
                notes = record.notes,
                stages = record.stages.map { s ->
                    SleepStage(s.startTime, s.endTime, SleepStageType.entries.firstOrNull { it.platformCode == s.stage } ?: SleepStageType.UNKNOWN)
                },
                meta = metaOf(record.metadata),
            )
            is StepsRecord -> Steps(record.startTime, offsetOf(record.startZoneOffset), record.endTime, offsetOf(record.endZoneOffset), record.count, metaOf(record.metadata))
            is DistanceRecord -> Distance(record.startTime, offsetOf(record.startZoneOffset), record.endTime, offsetOf(record.endZoneOffset), record.distance.inMeters, metaOf(record.metadata))
            is ActiveCaloriesBurnedRecord ->
                ActiveCaloriesBurned(record.startTime, offsetOf(record.startZoneOffset), record.endTime, offsetOf(record.endZoneOffset), record.energy.inKilocalories, metaOf(record.metadata))
            is BasalMetabolicRateRecord -> BasalMetabolicRate(record.time, offsetOf(record.zoneOffset), record.basalMetabolicRate.inKilocaloriesPerDay, metaOf(record.metadata))
            is RestingHeartRateRecord -> RestingHeartRate(record.time, offsetOf(record.zoneOffset), record.beatsPerMinute, metaOf(record.metadata))
            is MenstruationFlowRecord -> MenstruationFlow(
                record.time,
                offsetOf(record.zoneOffset),
                MenstruationFlowLevel.entries.firstOrNull { it.platformCode == record.flow } ?: MenstruationFlowLevel.UNKNOWN,
                metaOf(record.metadata),
            )
            is MenstruationPeriodRecord -> MenstruationPeriod(record.startTime, offsetOf(record.startZoneOffset), record.endTime, offsetOf(record.endZoneOffset), metaOf(record.metadata))
            else -> throw HealthCallFailedException("$type records are not read back by this version")
        }

        private fun offsetOf(offset: ZoneOffset?): ZoneOffset = requireNotNull(offset) { "every record the app writes carries its zone offsets" }

        private fun metaOf(m: Metadata): RecordMeta = RecordMeta(
            clientRecordId = requireNotNull(m.clientRecordId) { "every record the app writes carries its id" },
            version = m.clientRecordVersion,
            method = if (m.recordingMethod == Metadata.RECORDING_METHOD_MANUAL_ENTRY) RecordingMethod.MANUAL_ENTRY else RecordingMethod.AUTOMATICALLY_RECORDED,
            device = m.device?.model?.takeIf { it.isNotBlank() }?.let(::RingDevice),
        )
    }
}
