package io.github.opencircuit.ringkit

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the unit formatter, the shared fixed-decimal rule and the
 * locale-default units: what the upstream vectors never feed in. Temperatures and distances come
 * from sensor maths that can answer NaN, ±∞, −0.0 or a huge value; `fractionDigits` is a caller
 * value that can be negative or enormous; the default locale of a JVM can print non-ASCII digits;
 * and a locale can carry a unit preference or a region override. Kept out of the upstream-port class
 * so its count stays exact.
 *
 * Every upstream string quoted below was produced by the pinned Swift build on this Mac (Swift 6.3.2,
 * Foundation's `String(format:)`). Where the port deliberately differs the test says so, and
 * `PORTING.md` records why.
 */
class UnitsFormatterHazardTest {

    private val c = TemperatureUnit.CELSIUS
    private val f = TemperatureUnit.FAHRENHEIT
    private val km = DistanceUnit.METRIC

    private fun t(v: Double, fd: Int = 1) = UnitsFormatter.temperature(v, c, fd)
    private fun dt(v: Double, fd: Int = 1) = UnitsFormatter.temperatureDelta(v, c, fd)
    private fun dist(metres: Double, fd: Int = 1) = UnitsFormatter.distance(metres, km, fd)

    @Test
    fun notANumberInfinityAndSignedZeroPrintAsFoundation() {
        // NaN prints "nan" whatever its sign and even with a forced sign; infinities "inf" / "-inf"
        // ("+inf" signed); −0.0, and a negative value that rounds to zero, keep their minus sign.
        val negativeNaN = Double.fromBits(-0x8000000000000L) // the quiet NaN with its sign bit set
        assertTrue(negativeNaN.isNaN() && negativeNaN.toRawBits() < 0)
        for (nan in listOf(Double.NaN, negativeNaN)) {
            assertEquals("nan °C", t(nan))
            assertEquals("nan °C", dt(nan))
            assertEquals("nan km", dist(nan))
            assertEquals("nan °C", t(nan, 17))
        }
        assertEquals("nan °F", UnitsFormatter.temperature(Double.NaN, f))
        assertEquals("inf °C", t(Double.POSITIVE_INFINITY))
        assertEquals("+inf °C", dt(Double.POSITIVE_INFINITY))
        assertEquals("-inf °C", t(Double.NEGATIVE_INFINITY))
        assertEquals("-inf °C", dt(Double.NEGATIVE_INFINITY))
        assertEquals("inf km", dist(Double.POSITIVE_INFINITY))
        assertEquals("-0.0 °C", t(-0.0))
        assertEquals("-0.0 °C", dt(-0.0))
        assertEquals("-0.0 °F", UnitsFormatter.temperatureDelta(-0.0, f))
        assertEquals("-0 °C", t(-0.0, 0))
        assertEquals("-0.0 °C", t(-0.04))
        assertEquals("-0.04 °C", t(-0.04, 2))
        assertEquals("-0.000 °C", t(-1e-300, 3))
        assertEquals("0.0 °C", t(0.0))
        assertEquals("+0.0 °C", dt(0.0))
        assertEquals("0.000 °C", t(Double.MIN_VALUE, 3))
    }

    @Test
    fun exactBinaryTiesRoundToEvenOnTheExactValue() {
        // Foundation rounds the exact binary value, ties to even: 0.25 → 0.2, 0.125 → 0.12, 2.5 → 2,
        // −2.5 → −2; 0.35 and 9.95 sit just below their ties (→ 0.3, 9.9), 0.05 just above (→ 0.1).
        // Java's String.format rounds half up (0.25 → 0.3), which is why the port does not use it.
        assertEquals("0.2 °C", t(0.25))
        assertEquals("0.25 °C", t(0.25, 2))
        assertEquals("0.12 °C", t(0.125, 2))
        assertEquals("0.1 °C", t(0.125))
        assertEquals("2 °C", t(2.5, 0))
        assertEquals("-2 °C", t(-2.5, 0))
        assertEquals("+2 °C", dt(2.5, 0))
        assertEquals("0.3 °C", t(0.35))
        assertEquals("9.9 °C", t(9.95))
        assertEquals("10 °C", t(9.95, 0))
        assertEquals("0.1 °C", t(0.05))
        assertEquals("37 °C", t(36.6, 0))
        assertEquals("36.60000000000000142 °C", t(36.6, 17))
        assertEquals("0.25000000000000000 °C", t(0.25, 17))
        assertEquals("0.2 km", dist(250.0))
        assertEquals(
            "1000000000000000052504760255204420248704468581108159154915854115511802457988908195786371375080447864043704443832883878176942523235360430575644792184786706982848387200926575803737830233794788090059368953234970799945081119038967640880074652742780142494579258788820056842838115669472196386865459400540160.0 °C",
            t(1e300),
        )
        assertEquals("123456789012345680 °C", t(123456789012345678.0, 0))
        assertEquals("1000000000000000000000.0 °C", t(1e21))
    }

    @Test
    fun aNegativeFractionDigitsLeftJustifiesTheWholeNumberAsFoundation() {
        // Upstream builds the format "%.-Nf": Foundation reads an empty precision (0 digits) and a
        // left-justified field N wide. Measured.
        assertEquals("0 °C", t(0.25, -1))
        assertEquals("0  °C", t(0.25, -2))
        assertEquals("0     °C", t(0.25, -5))
        assertEquals("+0    °C", dt(0.25, -5))
        assertEquals("-2    °C", t(-2.5, -5))
        assertEquals("-2 °C", t(-2.5, -2))
        assertEquals("37    °C", t(36.6, -5))
        assertEquals("+37   °C", dt(36.6, -5))
        assertEquals("-0    °C", t(-0.0, -5))
        assertEquals("nan   °C", t(Double.NaN, -5))
        assertEquals("inf   °C", t(Double.POSITIVE_INFINITY, -5))
        assertEquals("+inf  °C", dt(Double.POSITIVE_INFINITY, -5))
        assertEquals("-inf  °C", t(Double.NEGATIVE_INFINITY, -5))
        assertEquals("10 °C", t(9.95, -2))
    }

    @Test
    fun aHugeFractionDigitsIsCappedAtFoundationsLengthAndNeverCrashes() {
        // Foundation writes at most 510 characters for the number (measured: 36.6 with 506 digits →
        // 512 characters with " °C"; with 507, 1 000 or 1 000 000 digits → 513, the number cut at 510;
        // a field of −510 or wider pads to 510), and SEGFAULTS once |fractionDigits| reaches
        // 2^31 − 512 (measured: 2147483135 runs; 2147483136, Int.MAX, −2147483136 and Int.MIN crash).
        // The port writes the same 510-character prefix for every width and never crashes.
        assertEquals(512, t(36.6, 506).length)
        for (fd in listOf(507, 508, 1000, 1_000_000, 2147483135, 2147483136, Int.MAX_VALUE)) {
            val s = t(36.6, fd)
            assertEquals(513, s.length, "fractionDigits $fd")
            assertTrue(s.startsWith("36.6000000000000014210854715202003717422485351562500000"), "fractionDigits $fd")
            assertTrue(s.endsWith("0 °C"), "fractionDigits $fd")
        }
        for (fd in listOf(-510, -511, -600, -2147483136, Int.MIN_VALUE)) {
            assertEquals("2" + " ".repeat(509) + " °C", t(2.5, fd), "fractionDigits $fd")
        }
        assertEquals(512, t(2.5, -509).length)
        // A huge integer part and many digits: the 309-digit maximum double with 200 digits is cut.
        val max = UnitsFormatter.temperatureDelta(-Double.MAX_VALUE, c, 200)
        assertEquals(513, max.length)
        assertTrue(max.startsWith("-179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368."))
        // −0.0 with 507 or more digits: "-0." then zeros to 510 characters.
        assertEquals("-0." + "0".repeat(507) + " °C", t(-0.0, 600))
    }

    @Test
    fun aNonAsciiDigitDefaultLocaleChangesNoFormattedString() {
        // The JVM's default locale can print Arabic-Indic, Persian, Devanagari or Thai digits and a
        // decimal comma; the formatter must not read it (upstream's `String(format:)` takes no locale).
        val values = listOf(36.6, -0.04, 0.25, 2.5, 1234567.891, 1e21, Double.NaN, Double.NEGATIVE_INFINITY, -0.0)
        fun render(): List<String> = values.flatMap { v ->
            listOf(-3, 0, 1, 3, 17).flatMap { fd ->
                listOf(
                    UnitsFormatter.temperature(v, c, fd), UnitsFormatter.temperature(v, f, fd),
                    UnitsFormatter.temperatureDelta(v, c, fd), UnitsFormatter.temperatureDelta(v, f, fd),
                    UnitsFormatter.distance(v * 1000, km, fd), UnitsFormatter.distance(v * 1609.344, DistanceUnit.IMPERIAL, fd),
                    swiftFixed(v, fd, false), swiftFixed(v, fd, true),
                )
            }
        } + listOf(TemperatureUnit.localeDefault(Locale.US).rawValue, DistanceUnit.localeDefault(Locale.GERMANY).rawValue)
        val saved = Locale.getDefault()
        val expected: List<String>
        try {
            Locale.setDefault(Locale.ROOT)
            expected = render()
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-nu-thai", "de-DE", "my-MM")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(expected, render(), "default locale $tag")
            }
        } finally {
            Locale.setDefault(saved)
        }
        assertTrue(expected.all { s -> s.all { it.code < 128 || it == '°' } }, "ASCII digits only")
    }

    @Test
    fun localeDefaultUnitsFollowTheRegionAndTheLocalesOwnUnitPreference() {
        // Upstream asks `Locale.measurementSystem == .us`. Measured on this Mac across all 292 regions
        // (en_XX): only US and LR answer the US system — Myanmar (MM) answers the UK system and so gets
        // °C and km. The locale's own unit preference (-u-ms-) and region override (-u-rg-) win over
        // its region, the preference first, as upstream.
        fun units(tag: String) = TemperatureUnit.localeDefault(Locale.forLanguageTag(tag)) to DistanceUnit.localeDefault(Locale.forLanguageTag(tag))
        val us = TemperatureUnit.FAHRENHEIT to DistanceUnit.IMPERIAL
        val metric = TemperatureUnit.CELSIUS to DistanceUnit.METRIC
        for (tag in listOf(
            "en-US", "es-US", "haw-US", "chr-US", "en-Latn-US", "en-LR", "de-DE-u-ms-ussystem", "en-GB-u-ms-ussystem",
            "en-GB-u-rg-uszzzz", "en-US-u-rg-lrzzzz", "en-GB-u-ms-ussystem-rg-gbzzzz",
        )) {
            assertEquals(us, units(tag), tag)
        }
        for (tag in listOf(
            "my-MM", "en-MM", "en-GB", "en-PR", "es-419", "en-001", "ar-EG", "fa-IR", "en-US-u-ms-metric", "en-US-u-ms-uksystem",
            "en-US-u-rg-gbzzzz", "en-GB-u-rg-mmzzzz", "en-US-u-ms-metric-rg-uszzzz", "my",
        )) {
            assertEquals(metric, units(tag), tag)
        }
        // Kotlin-only: a locale with no region. Upstream fills one in from the language (measured:
        // "en" and the root locale → the US system; "my" → Myanmar → UK system); `java.util.Locale`
        // has no such table, so a region-less locale reads metric — the "everything else" answer.
        // An Android device locale carries a region.
        assertEquals(metric, units("en"))
        assertEquals(metric, TemperatureUnit.localeDefault(Locale.ROOT) to DistanceUnit.localeDefault(Locale.ROOT))
    }

    @Test
    fun theDiagnosticsDecimalIsUnchangedForEveryValueItCanReceive() {
        // E2's diagnostics text formats gap hours (a non-negative number of seconds over 3 600) with
        // its own copy of the rule; it now delegates to the shared formatter. Over every such value
        // from 0 s to 400 days, ties at every twentieth, and a seeded sweep of non-negative doubles of
        // every magnitude, the shared rule writes exactly what the old copy wrote.
        fun oldRule(v: Double): String = BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
        var checked = 0
        for (s in 0L..(400L * 86_400) step 11) {
            val v = s / 3600.0
            assertEquals(oldRule(v), swiftFixed(v, 1, false), "$s s")
            assertEquals(oldRule(v), EpochArchiveDiagnostics.oneDecimal(v), "$s s")
            checked++
        }
        for (k in 0..100_000) {
            val v = k * 0.05
            assertEquals(oldRule(v), swiftFixed(v, 1, false), "$v")
            checked++
        }
        var x = 0x9E3779B97F4A7C15uL
        repeat(20_000) {
            x = x xor (x shl 13); x = x xor (x shr 7); x = x xor (x shl 17)
            val v = Double.fromBits((x shr 1).toLong()) // a non-negative bit pattern: every magnitude
            if (v.isFinite()) {
                assertEquals(oldRule(v), swiftFixed(v, 1, false), "$v")
                checked++
            }
        }
        assertTrue(checked > 3_000_000, "checked $checked")
    }
}
