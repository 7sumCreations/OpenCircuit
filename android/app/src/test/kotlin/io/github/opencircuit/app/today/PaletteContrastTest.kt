package io.github.opencircuit.app.today

import io.github.opencircuit.app.ui.Palette
import io.github.opencircuit.app.ui.Tones
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's designed palette in both modes, held to WCAG 2.2 contrast: text 4.5:1 on every surface
 * it sits on (large hero numbers 3:1), graphics — the sparkline, the band edge, the tier arc, the
 * live chart's momentum line, the accent dot — 3:1. The colours are read from the palette the
 * theme is built from, never retyped here.
 */
class PaletteContrastTest {

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int): Double =
        0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(failures: MutableList<String>, mode: String, what: String, fg: Int, bg: Int, min: Double) {
        val c = contrast(fg, bg)
        if (c < min) failures += "$mode $what: ${"%.2f".format(java.util.Locale.ROOT, c)} < $min"
    }

    private fun checks(mode: String, t: Tones): List<String> {
        val f = mutableListOf<String>()
        for ((name, surface) in listOf("page" to t.page, "card" to t.card, "tonal" to t.tonal)) {
            check(f, mode, "ink on $name", t.ink, surface, 4.5)
            check(f, mode, "secondary ink on $name", t.ink2, surface, 4.5)
            check(f, mode, "sparkline on $name", t.ink3, surface, 3.0)
            check(f, mode, "primary on $name", t.primary, surface, 4.5)
        }
        check(f, mode, "text on primary", t.onPrimary, t.primary, 4.5)
        check(f, mode, "text on the hero", t.onHero, t.hero, 4.5)
        check(f, mode, "live number (large) on the hero", t.primary, t.hero, 3.0)
        check(f, mode, "heart on the hero", t.heart, t.hero, 3.0)
        check(f, mode, "amber words on amber", t.onAmber, t.amber, 4.5)
        check(f, mode, "red words on red", t.onRed, t.red, 4.5)
        for ((name, tier) in listOf("excellent" to t.tierExcellent, "good" to t.tierGood, "needs improvement" to t.tierNeeds)) {
            check(f, mode, "$name tier on card", tier, t.card, 3.0)
        }
        for ((name, line) in listOf("rising" to t.rising, "falling" to t.falling)) {
            check(f, mode, "live chart $name on card", line, t.card, 3.0)
            check(f, mode, "live chart $name on page", line, t.page, 3.0)
        }
        return f
    }

    @Test
    fun everyPairingMeetsItsContrastInLightAndDark() {
        assertEquals(emptyList(), checks("light", Palette.LIGHT) + checks("dark", Palette.DARK))
    }

    /** The check can fail: the mockup's own light sparkline grey on the tonal surface would be a pass, a pale one is not. */
    @Test
    fun aTooPaleColourFailsTheCheck() {
        val pale = Palette.LIGHT.copy(ink3 = 0xFFB8C2C0.toInt())
        assertTrue(checks("light", pale).any { it.startsWith("light sparkline on card") })
    }

    @Test
    fun theTierColoursAreTheMockupsAndDistinctPerMode() {
        for (t in listOf(Palette.LIGHT, Palette.DARK)) assertEquals(3, setOf(t.tierExcellent, t.tierGood, t.tierNeeds).size)
        assertEquals(0xFF2F6DB5.toInt(), Palette.LIGHT.tierExcellent)
        assertEquals(0xFF1F5F6B.toInt(), Palette.LIGHT.primary)
        assertEquals(0xFF86CDD6.toInt(), Palette.DARK.primary)
    }
}
