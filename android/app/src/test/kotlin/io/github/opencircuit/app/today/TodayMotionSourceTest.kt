package io.github.opencircuit.app.today

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dashboard's motion rules live in Compose code that never runs on the JVM, so they are checked
 * on the source: one never-ending animation on Today (the live dot), started only when animation
 * is on; the activity turns animation off when the system asks for less motion; every spring the
 * screen uses comes from the shared motion tokens; the theme no longer takes the phone's dynamic
 * colour.
 */
class TodayMotionSourceTest {

    private val screen = File("src/main/kotlin/io/github/opencircuit/app/today/TodayScreen.kt").readText()
    private val activity = File("src/main/kotlin/io/github/opencircuit/app/MainActivity.kt").readText()
    private val theme = File("src/main/kotlin/io/github/opencircuit/app/ui/OpenCircuitTheme.kt").readText()

    @Test
    fun oneInfiniteAnimationOnTodayAndOnlyWhenAnimationIsOn() {
        assertEquals(1, Regex("""rememberInfiniteTransition\(""").findAll(screen).count(), "one never-ending animation")
        assertTrue(Regex("""if \(animate\) \{\s*val breath = rememberInfiniteTransition""").containsMatchIn(screen), "started only when animate is on")
        assertEquals(0, Regex("""\bspring\(|\btween\((?!900)""").findAll(screen).count(), "springs come from the shared tokens")
    }

    @Test
    fun theActivityFollowsTheSystemsReducedMotion() {
        assertTrue("animate = !reducedMotion()," in activity)
        assertTrue(Regex("""fun TodayScreen\(.*animate: Boolean = false\)""").containsMatchIn(screen), "off unless the caller turns it on (tests)")
    }

    @Test
    fun theThemeIsTheDesignedPaletteNotDynamicColour() {
        assertTrue("dynamic" !in theme.lowercase().replace("no dynamic colour", ""), "no dynamic colour scheme")
        assertTrue("Palette.DARK else Palette.LIGHT" in theme)
    }

    /**
     * The tier colours are checked as graphics (3:1, the arc), not as text (4.5:1): no text on
     * Today is drawn in a tier colour; the tier word and glyph use the ink.
     */
    @Test
    fun noTextIsDrawnInATierColour() {
        val textCalls = Regex("""Text\([^\n]*color = tierColour""")
        assertEquals(0, textCalls.findAll(screen).count(), "a tier colour on text")
    }
}
