package io.github.opencircuit.app.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The palette of the mode on screen, for what Material's scheme has no slot for (tiers, heart, momentum, sparkline). */
val LocalTones = staticCompositionLocalOf { Palette.LIGHT }

/** The scheme Material 3 components read, from one mode's [Tones]: cards on [Tones.card], the hero on [Tones.hero], warnings amber and red. */
fun colorSchemeOf(t: Tones, dark: Boolean): ColorScheme {
    fun c(argb: Int) = Color(argb)
    return if (dark) {
        darkColorScheme(
            primary = c(t.primary), onPrimary = c(t.onPrimary), primaryContainer = c(t.hero), onPrimaryContainer = c(t.onHero),
            inversePrimary = c(Palette.LIGHT.primary), secondary = c(t.primary), onSecondary = c(t.onPrimary),
            secondaryContainer = c(t.tonal), onSecondaryContainer = c(t.ink), tertiary = c(t.onAmber), onTertiary = c(t.amber),
            tertiaryContainer = c(t.amber), onTertiaryContainer = c(t.onAmber), background = c(t.page), onBackground = c(t.ink),
            surface = c(t.page), onSurface = c(t.ink), surfaceVariant = c(t.tonal), onSurfaceVariant = c(t.ink2),
            surfaceTint = c(t.primary), inverseSurface = c(t.ink), inverseOnSurface = c(t.page), error = c(t.onRed), onError = c(t.red),
            errorContainer = c(t.red), onErrorContainer = c(t.onRed), outline = c(t.ink3), outlineVariant = c(t.tonal), scrim = Color.Black,
            surfaceBright = c(t.tonal), surfaceContainer = c(t.card), surfaceContainerHigh = c(t.tonal), surfaceContainerHighest = c(t.tonal),
            surfaceContainerLow = c(t.card), surfaceContainerLowest = c(t.card), surfaceDim = c(t.page),
        )
    } else {
        lightColorScheme(
            primary = c(t.primary), onPrimary = c(t.onPrimary), primaryContainer = c(t.hero), onPrimaryContainer = c(t.onHero),
            inversePrimary = c(Palette.DARK.primary), secondary = c(t.primary), onSecondary = c(t.onPrimary),
            secondaryContainer = c(t.tonal), onSecondaryContainer = c(t.ink), tertiary = c(t.onAmber), onTertiary = c(t.amber),
            tertiaryContainer = c(t.amber), onTertiaryContainer = c(t.onAmber), background = c(t.page), onBackground = c(t.ink),
            surface = c(t.page), onSurface = c(t.ink), surfaceVariant = c(t.tonal), onSurfaceVariant = c(t.ink2),
            surfaceTint = c(t.primary), inverseSurface = c(t.ink), inverseOnSurface = c(t.page), error = c(t.onRed), onError = c(t.red),
            errorContainer = c(t.red), onErrorContainer = c(t.onRed), outline = c(t.ink3), outlineVariant = c(t.tonal), scrim = Color.Black,
            surfaceBright = c(t.card), surfaceContainer = c(t.card), surfaceContainerHigh = c(t.tonal), surfaceContainerHighest = c(t.tonal),
            surfaceContainerLow = c(t.card), surfaceContainerLowest = c(t.card), surfaceDim = c(t.tonal),
        )
    }
}

/**
 * The app's own colours — the designed palette ([Palette], fixed light and dark, the same on every
 * phone; no dynamic colour) — for every screen. The springs are passed explicitly ([Motion]):
 * Material's motion-scheme parameter is internal in this Material 3 version.
 */
@Composable
fun OpenCircuitTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val tones = if (dark) Palette.DARK else Palette.LIGHT
    CompositionLocalProvider(LocalTones provides tones) {
        MaterialTheme(colorScheme = colorSchemeOf(tones, dark), content = content)
    }
}

/**
 * Material 3's shipped motion tokens, passed by hand. Standard springs everywhere; the expressive
 * spatial spring only for the readiness arc's first fill of a day (and the goal rings later).
 * With `animate` false (reduced motion, tests) every spec snaps.
 */
object Motion {
    /** Standard spatial default: damping 0.9, stiffness 700. */
    fun <T> spatial(animate: Boolean): FiniteAnimationSpec<T> = if (animate) spring(dampingRatio = 0.9f, stiffness = 700f) else snap()

    /** Standard effects default: damping 1, stiffness 1600 (colour and opacity never overshoot). */
    fun <T> effects(animate: Boolean): FiniteAnimationSpec<T> = if (animate) spring(dampingRatio = 1f, stiffness = 1600f) else snap()

    /** Expressive spatial default: damping 0.8, stiffness 380. */
    fun <T> expressive(animate: Boolean): FiniteAnimationSpec<T> = if (animate) spring(dampingRatio = 0.8f, stiffness = 380f) else snap()
}
