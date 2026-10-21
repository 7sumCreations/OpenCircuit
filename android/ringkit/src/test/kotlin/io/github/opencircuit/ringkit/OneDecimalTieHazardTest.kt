package io.github.opencircuit.ringkit

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upstream's Today tiles print one-decimal values with `String(format: "%.1f", v)`. Java's
 * `String.format` rounds half up from the decimal text, Swift rounds the exact binary value with ties
 * to even, so the two disagree on values at an x.x5 boundary. These vectors were printed by Swift
 * 6.3.2 (`swiftc`, arm64 macOS) on 2026-10-21; the one-decimal text the tiles show must match them.
 */
class OneDecimalTieHazardTest {

    private val swiftPrinted: List<Pair<Double, String>> = listOf(
        0.05 to "0.1", 0.15 to "0.1", 0.25 to "0.2", 0.35 to "0.3", 0.45 to "0.5",
        0.55 to "0.6", 0.65 to "0.7", 0.75 to "0.8", 0.85 to "0.8", 0.95 to "0.9",
        1.25 to "1.2", 2.25 to "2.2", 36.45 to "36.5", 36.55 to "36.5", 36.65 to "36.6",
        -0.25 to "-0.2", -0.05 to "-0.1", 2.5 to "2.5", 98.25 to "98.2", 12.35 to "12.3",
    )

    @Test
    fun theSwiftFaithfulFormatterMatchesEveryMeasuredTieVector() {
        for ((v, text) in swiftPrinted) assertEquals(text, swiftFixed(v, 1), "%.1f of $v")
    }

    /** The vectors can tell the two rules apart: a half-up formatter fails some of them. */
    @Test
    fun javasHalfUpFormatDisagreesWithSwiftOnTies() {
        val disagreements = swiftPrinted.filter { (v, text) -> String.format(Locale.ROOT, "%.1f", v) != text }
        assertTrue(disagreements.isNotEmpty(), "no vector separates half-up from Swift's rule")
        assertEquals("0.3", String.format(Locale.ROOT, "%.1f", 0.25), "Java rounds the exact tie 0.25 up")
    }
}
