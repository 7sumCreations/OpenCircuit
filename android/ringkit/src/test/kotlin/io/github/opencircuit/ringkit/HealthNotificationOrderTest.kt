package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A NO-REGRESSION LOCK on the four shipped notification families (HR/SpO₂, temperature/fever,
 * reminders, charging).
 *
 * `NotificationGate.filter` returns survivors in `HealthNotification` DECLARATION ORDER, and the
 * de-dupe ledgers are keyed by `rawValue`. So the enum has two silent-breakage modes: INSERTING a case
 * rather than appending reorders delivery for every pair after it, and RENAMING a raw value orphans
 * that notification's persisted ledger entries. This file pins the first twelve raw values, in order,
 * as they shipped. It deliberately does NOT pin the case count: appending is the safe operation.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HealthNotificationOrderTests.swift
 * (@ b1c2fdd), 4 of 4. The raw values below are typed from upstream's test, never read from the port.
 */
class HealthNotificationOrderTest {

    /** The twelve shipped notifications, in declaration order, with their persisted raw values. */
    private val shipped = listOf(
        // heart rate & blood oxygen
        "highHR",
        "lowSpO2",
        "elevatedHRInactive",
        // skin temperature + fever
        "skinTempRise",
        "skinTempDrop",
        "skinTempFluctuationRise",
        "skinTempFluctuationDrop",
        "fever",
        // app-side reminders
        "reminder.sedentary",
        "reminder.wear",
        "reminder.bedtime",
        // battery
        "battery.chargingComplete",
    )

    @Test
    fun shippedTwelveKeepTheirOrderAndRawValues() { // :42
        assertEquals(
            shipped,
            HealthNotification.entries.take(12).map { it.rawValue },
            "A case was inserted or renamed. Append at the END and give it an explicit rawValue — see the file comment.",
        )
    }

    @Test
    fun headacheSignsIsAppendedAfterAllTwelve() { // :48
        val index = HealthNotification.entries.indexOf(HealthNotification.HEADACHE_SIGNS)
        assertTrue(index >= 12, "the overnight-signals notification must sit AFTER every shipped case")
        assertEquals("headache.signs", HealthNotification.HEADACHE_SIGNS.rawValue)
    }

    /** The gate emits survivors in declaration order regardless of the order the caller assembled them in. */
    @Test
    fun gateFilterReturnsDeclarationOrderNotCallerOrder() { // :56
        val now = Instant.ofEpochSecond(1_753_700_000)
        val scrambled = listOf(
            HealthNotification.CHARGING_COMPLETE, HealthNotification.FEVER, HealthNotification.HIGH_HR, HealthNotification.WEAR_REMINDER,
        )
        // Quiet hours are disabled, so the zone is never read; UTC is passed only because the gate requires one.
        val out = NotificationGate().filter(scrambled, now = now, lastFired = emptyMap(), quietHours = QuietHours(enabled = false), zone = ZoneOffset.UTC)
        assertEquals(
            listOf(HealthNotification.HIGH_HR, HealthNotification.FEVER, HealthNotification.WEAR_REMINDER, HealthNotification.CHARGING_COMPLETE),
            out,
        )
    }

    /** Appending the overnight-signals case must not have disturbed the relative order of any shipped pair. */
    @Test
    fun appendingDidNotReorderAnyShippedPair() { // :65
        val positions = HealthNotification.entries.withIndex().associate { (i, n) -> n.rawValue to i }
        for ((i, earlier) in shipped.withIndex()) {
            for (later in shipped.drop(i + 1)) {
                assertTrue(
                    assertNotNull(positions[earlier]) < assertNotNull(positions[later]),
                    "$earlier must still be delivered before $later",
                )
            }
        }
    }
}
