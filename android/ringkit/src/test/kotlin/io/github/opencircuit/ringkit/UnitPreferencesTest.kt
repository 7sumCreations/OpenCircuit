package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * User-configurable display units for temperature and distance (#83): conversions, symbols, the
 * formatter's three number shapes and the stored raw names.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/UnitPreferencesTests.swift
 * (@ b1c2fdd), all 21 tests. Upstream's `TemperatureUnit(rawValue:)` is `fromRawValue` here.
 */
class UnitPreferencesTest {

    // TemperatureUnit conversion

    @Test
    fun zeroCelsiusToFahrenheit() { // :8-11
        val f = TemperatureUnit.FAHRENHEIT.convert(fromCelsius = 0.0)
        assertEquals(32.0, f, 0.001)
    }

    @Test
    fun hundredCelsiusToFahrenheit() { // :13-16
        val f = TemperatureUnit.FAHRENHEIT.convert(fromCelsius = 100.0)
        assertEquals(212.0, f, 0.001)
    }

    @Test
    fun bodyTempCelsiusToFahrenheit() { // :18-21
        val f = TemperatureUnit.FAHRENHEIT.convert(fromCelsius = 37.0)
        assertEquals(98.6, f, 0.01)
    }

    @Test
    fun celsiusIdentity() { // :23-25
        assertEquals(36.5, TemperatureUnit.CELSIUS.convert(fromCelsius = 36.5), 0.0001)
    }

    @Test
    fun celsiusSymbol() { // :27-29
        assertEquals("°C", TemperatureUnit.CELSIUS.symbol)
    }

    @Test
    fun fahrenheitSymbol() { // :31-33
        assertEquals("°F", TemperatureUnit.FAHRENHEIT.symbol)
    }

    // TemperatureUnit delta conversion (offset from baseline)

    /**
     * A DELTA scales by 9/5 with NO +32 offset: +0.5 °C is +0.9 °F, not +32.9 °F. This is the bug —
     * the baseline offset was converted (or hardcoded) as an absolute temperature.
     */
    @Test
    fun deltaCelsiusToFahrenheitScalesWithoutOffset() { // :39-42
        assertEquals(0.9, TemperatureUnit.FAHRENHEIT.convertDelta(fromCelsius = 0.5), 0.0001)
        assertEquals(-1.8, TemperatureUnit.FAHRENHEIT.convertDelta(fromCelsius = -1.0), 0.0001)
    }

    @Test
    fun zeroDeltaIsZeroInBothUnits() { // :44-47
        assertEquals(0.0, TemperatureUnit.FAHRENHEIT.convertDelta(fromCelsius = 0.0), 0.0001)
        assertEquals(0.0, TemperatureUnit.CELSIUS.convertDelta(fromCelsius = 0.0), 0.0001)
    }

    @Test
    fun deltaCelsiusIdentity() { // :49-51
        assertEquals(0.7, TemperatureUnit.CELSIUS.convertDelta(fromCelsius = 0.7), 0.0001)
    }

    @Test
    fun formatterTemperatureDeltaIsSignedAndScaled() { // :53-57
        assertEquals("+0.9 °F", UnitsFormatter.temperatureDelta(0.5, unit = TemperatureUnit.FAHRENHEIT))
        assertEquals("+0.5 °C", UnitsFormatter.temperatureDelta(0.5, unit = TemperatureUnit.CELSIUS))
        assertEquals("-1.8 °F", UnitsFormatter.temperatureDelta(-1.0, unit = TemperatureUnit.FAHRENHEIT))
    }

    // DistanceUnit conversion

    @Test
    fun metrestoKilometres() { // :61-63
        assertEquals(5.0, DistanceUnit.METRIC.convert(fromMeters = 5_000.0), 0.0001)
    }

    @Test
    fun metresToMiles() { // :65-68
        // 1 mile = 1609.344 m
        assertEquals(1.0, DistanceUnit.IMPERIAL.convert(fromMeters = 1_609.344), 0.0001)
    }

    @Test
    fun metricSymbol() { // :70-72
        assertEquals("km", DistanceUnit.METRIC.symbol)
    }

    @Test
    fun imperialSymbol() { // :74-76
        assertEquals("mi", DistanceUnit.IMPERIAL.symbol)
    }

    // UnitsFormatter.temperature

    @Test
    fun formatterCelsiusOutput() { // :80-83
        val s = UnitsFormatter.temperature(36.5, unit = TemperatureUnit.CELSIUS)
        assertEquals("36.5 °C", s)
    }

    @Test
    fun formatterFahrenheitOutput() { // :85-89
        val s = UnitsFormatter.temperature(37.0, unit = TemperatureUnit.FAHRENHEIT)
        // 37 °C = 98.6 °F
        assertEquals("98.6 °F", s)
    }

    @Test
    fun formatterFractionDigitsZero() { // :91-95
        // Use 36.6 to avoid banker's-rounding ambiguity at .5
        val s = UnitsFormatter.temperature(36.6, unit = TemperatureUnit.CELSIUS, fractionDigits = 0)
        assertEquals("37 °C", s)
    }

    // UnitsFormatter.distance

    @Test
    fun formatterKilometres() { // :99-102
        val s = UnitsFormatter.distance(3_200.0, unit = DistanceUnit.METRIC)
        assertEquals("3.2 km", s)
    }

    @Test
    fun formatterMiles() { // :104-108
        val metres = 1_609.344 * 2 // 2 miles
        val s = UnitsFormatter.distance(metres, unit = DistanceUnit.IMPERIAL)
        assertEquals("2.0 mi", s)
    }

    // RawValue round-trip (AppStorage compatibility)

    @Test
    fun temperatureUnitRawValueRoundTrip() { // :112-116
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.fromRawValue("celsius"))
        assertEquals(TemperatureUnit.FAHRENHEIT, TemperatureUnit.fromRawValue("fahrenheit"))
        assertNull(TemperatureUnit.fromRawValue("kelvin"))
    }

    @Test
    fun distanceUnitRawValueRoundTrip() { // :118-121
        assertEquals(DistanceUnit.METRIC, DistanceUnit.fromRawValue("metric"))
        assertEquals(DistanceUnit.IMPERIAL, DistanceUnit.fromRawValue("imperial"))
    }
}
