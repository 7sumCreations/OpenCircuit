package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.HealthNotification
import io.github.opencircuit.ringkit.SyncAlert
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stored form of the last-fired ledgers: when each sync alert and each health notification
 * last fired, and the night each skin-temperature notification last fired for.
 *
 * Kotlin-only. Upstream keeps them as property-list dictionaries (`[String: Double]` seconds and
 * `[String: Int]` night keys; ios/OpenCircuit/Observability/ObservabilityStore.swift:196-207,
 * ios/OpenCircuit/Health/HealthNotificationCenter.swift:145-185 @ b1c2fdd) and reads them by
 * casting the whole dictionary: a value of the wrong type fails the cast and the ledger reads
 * empty, while an unknown name or a value of 0 or less is skipped. Here the times are epoch
 * milliseconds, so a fractional value is the wrong type.
 */
class LastFiredLedgerCodecTest {

    private val t1 = Instant.ofEpochMilli(1_790_000_000_123)
    private val t2 = Instant.ofEpochMilli(1_790_000_500_000)

    private fun alerts(text: String) = LastFiredLedgerCodec.decode(text, SyncAlert.entries) { it.rawValue }

    private fun nights(text: String) = LastFiredLedgerCodec.decodeNights(text, HealthNotification.entries) { it.rawValue }

    @Test
    fun theStoredFormIsRawNameToEpochMillisecondsInNameOrder() {
        assertEquals(
            """{"lowBattery":1790000500000,"notSynced":1790000000123}""",
            LastFiredLedgerCodec.encode(mapOf(SyncAlert.NOT_SYNCED to t1, SyncAlert.LOW_BATTERY to t2)) { it.rawValue },
        )
    }

    @Test
    fun bothLedgersRoundTrip() {
        val syncLedger = mapOf(SyncAlert.NOT_SYNCED to t1, SyncAlert.HEALTH_AUTH_LOST to t2)
        assertEquals(syncLedger, readable(alerts(LastFiredLedgerCodec.encode(syncLedger) { it.rawValue })))

        val healthLedger = mapOf(HealthNotification.HIGH_HR to t1, HealthNotification.FEVER to t2)
        val text = LastFiredLedgerCodec.encode(healthLedger) { it.rawValue }
        assertEquals(healthLedger, readable(LastFiredLedgerCodec.decode(text, HealthNotification.entries) { it.rawValue }))

        val nightLedger = mapOf(HealthNotification.FEVER to 20261002L, HealthNotification.SKIN_TEMP_RISE to 20261001L)
        assertEquals(nightLedger, readable(nights(LastFiredLedgerCodec.encodeNights(nightLedger) { it.rawValue })))

        assertEquals(emptyMap(), readable(alerts("{}")))
    }

    @Test
    fun anUnknownNameOrAValueOfZeroOrLessIsSkippedAndTheRestKept() {
        assertEquals(
            mapOf(SyncAlert.LOW_BATTERY to t2),
            readable(alerts("""{"lowBattery":1790000500000,"notSynced":0,"healthAuthLost":-5,"retired":1790000000000,"LowBattery":7}""")),
        )
        assertEquals(
            mapOf(HealthNotification.FEVER to 20261002L),
            readable(nights("""{"fever":20261002,"highHR":0,"skinTempRise":-1,"someday":20261003}""")),
        )
    }

    @Test
    fun aValueOfTheWrongTypeMakesTheWholeLedgerUnreadable() {
        for (raw in listOf(
            """{"lowBattery":1790000500000,"notSynced":1.5}""",
            """{"notSynced":"1790000000000"}""",
            """{"notSynced":true}""",
            """{"notSynced":null}""",
            """{"notSynced":[1]}""",
            """{"retired":"x"}""",
            """[]""",
            """{"notSynced":9223372036854775808}""",
        )) {
            assertUnreadable(alerts(raw), raw)
        }
        // A fractional night key fails upstream's `[String: Int]` cast too.
        val fractional = """{"fever":20261002.5}"""
        assertUnreadable(nights(fractional), fractional)
    }
}
