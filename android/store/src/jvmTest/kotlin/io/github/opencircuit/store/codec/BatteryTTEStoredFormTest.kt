package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.BatteryTTE
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A battery history survives its stored form.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/BatteryTTETests.swift
 * (@ b1c2fdd) `testSampleCodableRoundTrip` (`:154`); the file's other tests are in `:ringkit`'s
 * `BatteryTTETest`. `t0` is 1970-01-01, as upstream's.
 */
class BatteryTTEStoredFormTest {

    private val t0 = Instant.EPOCH

    private fun sample(pct: Int, hours: Long): BatteryTTE.Sample = BatteryTTE.Sample(pct, t0.plusSeconds(hours * 3_600))

    @Test
    fun sampleCodableRoundTrip() {
        val h = listOf(sample(80, hours = 0), sample(78, hours = 3))
        assertEquals(h, readable(BatterySamplesCodec.decode(BatterySamplesCodec.encode(h))))
    }
}
