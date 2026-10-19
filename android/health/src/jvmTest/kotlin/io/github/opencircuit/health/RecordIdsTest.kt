package io.github.opencircuit.health

import io.github.opencircuit.health.RecordIds.StoredSampleKey
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every id is typed out in full here. The functions take stored facts only and read no clock, so
 * the same stored fact names the same record on every write.
 */
class RecordIdsTest {

    private val t = Instant.parse("2026-10-16T08:00:00Z") // 1792137600000 ms
    private val day = LocalDate.of(2026, 10, 16)

    @Test
    fun eachRecordTypeHasItsIdShape() {
        assertEquals("oc:hr:1792137600000", RecordIds.heartRateHour(t))
        assertEquals("oc:rhr:2026-10-16", RecordIds.restingHeartRate(day))
        assertEquals("oc:skin:2026-10-15", RecordIds.skinTemperature("2026-10-15"))
        assertEquals("oc:steps:1792137600123", RecordIds.steps(t.plusMillis(123)))
        assertEquals("oc:dist:1792137600123", RecordIds.distance(t.plusMillis(123)))
        assertEquals("oc:ae:1792137600000", RecordIds.activeCalories(t))
        assertEquals("oc:bmr:2026-10-16", RecordIds.basalMetabolicRate(day))
        assertEquals("oc:sleep:2026-10-15", RecordIds.sleepNight("2026-10-15"))
        assertEquals("oc:nap:1792137600500", RecordIds.nap(t.plusMillis(500)))
        assertEquals("oc:flow:2026-10-16", RecordIds.menstruationFlow(day))
        assertEquals("oc:period:2026-10-16", RecordIds.menstruationPeriod(day))
    }

    @Test
    fun sampleIdsKeyByStartInMillisAndOrdinal() {
        val ids = RecordIds.sampleIds(RecordType.HEART_RATE_VARIABILITY_RMSSD, listOf(StoredSampleKey(7, t.plusMillis(250))))
        assertEquals(mapOf(7L to "oc:hrv:1792137600250:0"), ids)
        assertEquals(
            mapOf(1L to "oc:spo2:1792137600000:0"),
            RecordIds.sampleIds(RecordType.OXYGEN_SATURATION, listOf(StoredSampleKey(1, t))),
        )
        assertEquals(
            mapOf(1L to "oc:rr:1792137600000:0"),
            RecordIds.sampleIds(RecordType.RESPIRATORY_RATE, listOf(StoredSampleKey(1, t))),
        )
    }

    @Test
    fun twoStoredSamplesOfOneKindAtOneStartGetTwoIdsInRowIdOrder() {
        // Given in reverse row order: the ordinal follows the row id, not the list order.
        val ids = RecordIds.sampleIds(
            RecordType.HEART_RATE_VARIABILITY_RMSSD,
            listOf(StoredSampleKey(42, t), StoredSampleKey(9, t.plusSeconds(60)), StoredSampleKey(17, t)),
        )
        assertEquals(
            mapOf(
                17L to "oc:hrv:1792137600000:0",
                42L to "oc:hrv:1792137600000:1",
                9L to "oc:hrv:1792137660000:0",
            ),
            ids,
        )
    }

    @Test
    fun aLaterRowAtTheSameStartKeepsTheEarlierRowsIds() {
        val before = RecordIds.sampleIds(RecordType.OXYGEN_SATURATION, listOf(StoredSampleKey(17, t), StoredSampleKey(42, t)))
        val after = RecordIds.sampleIds(
            RecordType.OXYGEN_SATURATION,
            listOf(StoredSampleKey(17, t), StoredSampleKey(42, t), StoredSampleKey(99, t)),
        )
        assertEquals(before[17], after[17])
        assertEquals(before[42], after[42])
        assertEquals("oc:spo2:1792137600000:2", after[99])
    }

    @Test
    fun sampleIdsOfNothingIsEmpty() {
        assertEquals(emptyMap(), RecordIds.sampleIds(RecordType.RESPIRATORY_RATE, emptyList()))
    }

    @Test
    fun sampleIdsRefuseOtherTypesAndRepeatedRowIds() {
        assertFailsWith<IllegalArgumentException> { RecordIds.sampleIds(RecordType.HEART_RATE, listOf(StoredSampleKey(1, t))) }
        assertFailsWith<IllegalArgumentException> { RecordIds.sampleIds(RecordType.STEPS, listOf(StoredSampleKey(1, t))) }
        assertFailsWith<IllegalArgumentException> {
            RecordIds.sampleIds(RecordType.OXYGEN_SATURATION, listOf(StoredSampleKey(1, t), StoredSampleKey(1, t.plusSeconds(1))))
        }
    }

    @Test
    fun aHeartRateHourMustStartOnAUtcHour() {
        assertFailsWith<IllegalArgumentException> { RecordIds.heartRateHour(t.plusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { RecordIds.heartRateHour(t.plusMillis(1)) }
        // 08:00 at a +05:30 offset is not on a UTC hour.
        assertFailsWith<IllegalArgumentException> { RecordIds.heartRateHour(Instant.parse("2026-10-16T08:00:00+05:30")) }
    }

    @Test
    fun aBlankNightKeyIsRefused() {
        assertFailsWith<IllegalArgumentException> { RecordIds.sleepNight("") }
        assertFailsWith<IllegalArgumentException> { RecordIds.skinTemperature(" ") }
    }

    @Test
    fun instantsBefore1970AndDaysAfter9999StayUnambiguous() {
        assertEquals("oc:steps:-1500", RecordIds.steps(Instant.ofEpochMilli(-1500)))
        assertEquals("oc:hr:-3600000", RecordIds.heartRateHour(Instant.ofEpochSecond(-3600)))
        assertEquals("oc:bmr:+10000-01-01", RecordIds.basalMetabolicRate(LocalDate.of(10000, 1, 1)))
    }

    @Test
    fun idsUseAsciiDigitsWhateverThePhonesLanguage() {
        val saved = Locale.getDefault()
        try {
            // Arabic and Hindi locales format numbers with their own digits by default.
            for (tag in listOf("ar-EG", "hi-IN-u-nu-deva", "fa-IR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("oc:ae:1792137600000", RecordIds.activeCalories(t), tag)
                assertEquals("oc:rhr:2026-10-16", RecordIds.restingHeartRate(day), tag)
                assertEquals(
                    mapOf(3L to "oc:hrv:1792137600000:0"),
                    RecordIds.sampleIds(RecordType.HEART_RATE_VARIABILITY_RMSSD, listOf(StoredSampleKey(3, t))),
                    tag,
                )
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
