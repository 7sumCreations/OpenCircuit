package io.github.opencircuit.ringkit

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's public door to Swift's number text: what the Today tiles print with `%.1f` and round
 * with `rounded()`. The tie vectors were printed by Swift 6.3.2 (`swiftc`, arm64 macOS) on
 * 2026-10-21 — the same vectors the internal formatter is held to.
 */
class SwiftFormatTest {

    private val saved = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(saved)

    @Test
    fun oneDecimalMatchesSwiftOnEveryMeasuredTie() {
        val swiftPrinted = listOf(
            0.05 to "0.1", 0.15 to "0.1", 0.25 to "0.2", 0.35 to "0.3", 0.45 to "0.5",
            0.55 to "0.6", 0.65 to "0.7", 0.75 to "0.8", 0.85 to "0.8", 0.95 to "0.9",
            1.25 to "1.2", 2.25 to "2.2", 36.45 to "36.5", 36.55 to "36.5", 36.65 to "36.6",
            -0.25 to "-0.2", -0.05 to "-0.1", 2.5 to "2.5", 98.25 to "98.2", 12.35 to "12.3",
        )
        for ((v, text) in swiftPrinted) assertEquals(text, SwiftFormat.fixed(v, 1), "%.1f of $v")
    }

    @Test
    fun theTextIsAsciiWhateverTheDefaultLocale() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("36.5", SwiftFormat.fixed(36.55, 1))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1.0", SwiftFormat.fixed(1.0, 1), "a decimal POINT, never the locale's comma")
    }

    @Test
    fun zeroDecimalsAndTheSignedForm() {
        assertEquals("2", SwiftFormat.fixed(2.5, 0), "%.0f rounds the binary value ties to even")
        assertEquals("+0.5", SwiftFormat.fixed(0.5, 1, forceSign = true))
        assertEquals("-0.0", SwiftFormat.fixed(-0.0, 1), "Swift keeps the sign of a negative zero")
    }

    @Test
    fun roundedIsSwiftsTiesAwayFromZero() {
        assertEquals(3.0, SwiftFormat.rounded(2.5))
        assertEquals(-3.0, SwiftFormat.rounded(-2.5))
        assertEquals(2.0, SwiftFormat.rounded(2.4999999999999996))
        assertEquals(0.0, SwiftFormat.rounded(0.49999999999999994), "the classic floor(x + 0.5) trap")
        assertEquals(1.0, SwiftFormat.rounded(0.5))
    }
}
