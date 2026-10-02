package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/UnitPreferences.swift (@ b1c2fdd), whole.
//
// Port notes:
//  • `localeDefault` takes the locale (upstream reads `Locale.current`). Upstream asks whether the
//    locale's measurement system is the US one. Measured on this Mac with the pinned build's
//    Foundation, across all 292 regions: only US and LR use it (Myanmar uses the UK system), the
//    locale's own unit preference (`-u-ms-`) wins over a region override (`-u-rg-`), which wins over
//    the region. The JDK has no measurement-system data, so the rule is that table and those two
//    keywords. A locale with no region reads metric (Foundation would fill a region in from the
//    language — `java.util.Locale` has no such table; a device locale carries a region).
//  • The three `String(format:)` number shapes use `swiftFixed` (SwiftNumerics.kt): the exact binary
//    value rounded ties to even, ASCII digits whatever the default locale, Foundation's text for NaN,
//    ±∞, −0.0, a negative `fractionDigits` and a huge one (where upstream crashes, this does not).
//  • `fromRawValue` stands in for upstream's `init?(rawValue:)`; the raw names are what upstream stores.

import java.util.Locale

/** A display unit for temperature. Values are STORED in Celsius; only the display converts. [rawValue] is upstream's stored name. */
enum class TemperatureUnit(val rawValue: String) {
    CELSIUS("celsius"),
    FAHRENHEIT("fahrenheit"),
    ;

    /** Convert a Celsius value to this unit. */
    fun convert(fromCelsius: Double): Double = when (this) {
        CELSIUS -> fromCelsius
        FAHRENHEIT -> fromCelsius * 9 / 5 + 32
    }

    /**
     * Convert a temperature DIFFERENCE (a delta / offset, e.g. "+0.5 °C above baseline") to this unit:
     * the ratio only — the +32 of an ABSOLUTE conversion must NOT be applied, or a +0.5 °C delta would
     * read as +32.9 °F. Always use this (never [convert]) for an offset from a baseline.
     */
    fun convertDelta(fromCelsius: Double): Double = when (this) {
        CELSIUS -> fromCelsius
        FAHRENHEIT -> fromCelsius * 9 / 5
    }

    /** The unit abbreviation shown next to a formatted value. */
    val symbol: String
        get() = when (this) {
            CELSIUS -> "°C"
            FAHRENHEIT -> "°F"
        }

    companion object {
        /** The unit stored as [rawValue], or null for an unknown name. */
        fun fromRawValue(rawValue: String): TemperatureUnit? = entries.firstOrNull { it.rawValue == rawValue }

        /** The out-of-box default for [locale]: °F where the US measurement system applies, else °C. */
        fun localeDefault(locale: Locale): TemperatureUnit = if (usesUsMeasurementSystem(locale)) FAHRENHEIT else CELSIUS
    }
}

/** A display unit for distance. Values are STORED in metres. [rawValue] is upstream's stored name. */
enum class DistanceUnit(val rawValue: String) {
    METRIC("metric"), // kilometres
    IMPERIAL("imperial"), // miles
    ;

    /** Convert metres to this unit. */
    fun convert(fromMeters: Double): Double = when (this) {
        METRIC -> fromMeters / 1_000
        IMPERIAL -> fromMeters / 1_609.344
    }

    val symbol: String
        get() = when (this) {
            METRIC -> "km"
            IMPERIAL -> "mi"
        }

    companion object {
        /** The unit stored as [rawValue], or null for an unknown name. */
        fun fromRawValue(rawValue: String): DistanceUnit? = entries.firstOrNull { it.rawValue == rawValue }

        /** The out-of-box default for [locale]: miles where the US measurement system applies, else kilometres. */
        fun localeDefault(locale: Locale): DistanceUnit = if (usesUsMeasurementSystem(locale)) IMPERIAL else METRIC
    }
}

/** Stateless helpers that turn raw SI values into display strings. */
object UnitsFormatter {

    /**
     * Canonical on-screen unit for respiratory rate (breaths per minute): one string for every RR
     * surface. DISPLAY copy only — the health-store unit stays the metric's own.
     */
    const val RESPIRATORY_RATE_UNIT = "brpm"

    /** Format a Celsius value in [unit], e.g. "36.5 °C" or "97.7 °F" (upstream's `"%.Nf <symbol>"`). */
    fun temperature(celsius: Double, unit: TemperatureUnit, fractionDigits: Int = 1): String =
        swiftFixed(unit.convert(fromCelsius = celsius), fractionDigits) + " " + unit.symbol

    /**
     * Format a temperature DELTA (offset from baseline) in [unit], signed, e.g. "+0.5 °C" or "+0.9 °F"
     * (upstream's `"%+.Nf <symbol>"`). Uses [TemperatureUnit.convertDelta], so a small offset stays small.
     */
    fun temperatureDelta(celsiusDelta: Double, unit: TemperatureUnit, fractionDigits: Int = 1): String =
        swiftFixed(unit.convertDelta(fromCelsius = celsiusDelta), fractionDigits, forceSign = true) + " " + unit.symbol

    /** Format a metres value in [unit], e.g. "3.2 km" or "2.0 mi" (upstream's `"%.Nf <symbol>"`). */
    fun distance(meters: Double, unit: DistanceUnit, fractionDigits: Int = 1): String =
        swiftFixed(unit.convert(fromMeters = meters), fractionDigits) + " " + unit.symbol
}

/** The regions whose measurement system is the US one (measured: US and LR of 292). */
private val US_MEASUREMENT_REGIONS = setOf("US", "LR")

/**
 * Whether [locale] uses the US measurement system: its own unit preference (`-u-ms-ussystem` yes,
 * `metric` / `uksystem` no) first, then its region override (`-u-rg-xxzzzz`), then its region.
 */
private fun usesUsMeasurementSystem(locale: Locale): Boolean {
    when (locale.getUnicodeLocaleType("ms")) {
        "ussystem" -> return true
        "metric", "uksystem" -> return false
    }
    val override = locale.getUnicodeLocaleType("rg")
    val region = if (override != null && override.length == 6 && override.endsWith("zzzz") && override.take(2).all { it in 'a'..'z' }) {
        override.take(2).uppercase(Locale.ROOT)
    } else {
        locale.country
    }
    return region in US_MEASUREMENT_REGIONS
}
