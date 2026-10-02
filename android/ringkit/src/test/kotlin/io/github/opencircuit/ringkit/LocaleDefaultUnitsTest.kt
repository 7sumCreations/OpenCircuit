package io.github.opencircuit.ringkit

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only tests for the first-run default units (upstream has no test for them). Upstream reads
 * `Locale.current.measurementSystem == .us`; the port takes the locale as a required parameter and
 * answers from its region (and its own unit preference — see the hazard class). Measured on this
 * Mac with the pinned build's Foundation, across all 292 regions as `en_XX`: exactly US and LR use
 * the US measurement system — Myanmar uses the UK system, so it gets °C and kilometres like every
 * other region.
 */
class LocaleDefaultUnitsTest {

    private fun units(locale: Locale) = TemperatureUnit.localeDefault(locale) to DistanceUnit.localeDefault(locale)
    private val us = TemperatureUnit.FAHRENHEIT to DistanceUnit.IMPERIAL
    private val metric = TemperatureUnit.CELSIUS to DistanceUnit.METRIC

    @Test
    fun theUnitedStatesAndLiberiaGetFahrenheitAndMiles() {
        assertEquals(us, units(Locale.US))
        assertEquals(us, units(Locale.forLanguageTag("es-US")))
        assertEquals(us, units(Locale.forLanguageTag("en-LR")))
        assertEquals(us, units(Locale.forLanguageTag("vai-LR")))
    }

    @Test
    fun myanmarGetsCelsiusAndKilometres() {
        assertEquals(metric, units(Locale.forLanguageTag("my-MM")))
        assertEquals(metric, units(Locale.forLanguageTag("en-MM")))
    }

    @Test
    fun theUnitedKingdomCanadaAndTheRestGetCelsiusAndKilometres() {
        for (locale in listOf(Locale.UK, Locale.CANADA, Locale.GERMANY, Locale.FRANCE, Locale.JAPAN, Locale.forLanguageTag("en-IN"), Locale.forLanguageTag("en-AU"), Locale.forLanguageTag("en-PR"))) {
            assertEquals(metric, units(locale), locale.toLanguageTag())
        }
    }

    @Test
    fun everyRegionButTheUnitedStatesAndLiberiaIsMetric() {
        // The whole ISO region list, once: the two-region rule and nothing else.
        val regions = Locale.getISOCountries().toList()
        assertTrue(regions.size > 240, "${regions.size} regions")
        val imperial = regions.filter { units(Locale.Builder().setLanguage("en").setRegion(it).build()) == us }
        assertEquals(listOf("LR", "US"), imperial.sorted())
        for (r in regions) {
            val (t, d) = units(Locale.Builder().setLanguage("en").setRegion(r).build())
            assertEquals(t == TemperatureUnit.FAHRENHEIT, d == DistanceUnit.IMPERIAL, "temperature and distance agree for $r")
        }
    }
}
