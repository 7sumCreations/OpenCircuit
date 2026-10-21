package io.github.opencircuit.app.ui

/**
 * One mode's colours, ARGB: the surfaces ([page] behind everything, [card], [tonal] chips and
 * tracks), three inks (text, secondary text, the neutral sparkline line), the petrol [primary],
 * the lagoon [hero] surface, the [heart], the amber and red warning pairs, the readiness tier
 * colours (always shown with the tier word and glyph, never alone) and the live chart's momentum
 * line ([rising], [falling]).
 */
data class Tones(
    val page: Int,
    val card: Int,
    val tonal: Int,
    val ink: Int,
    val ink2: Int,
    val ink3: Int,
    val primary: Int,
    val onPrimary: Int,
    val hero: Int,
    val onHero: Int,
    val heart: Int,
    val amber: Int,
    val onAmber: Int,
    val red: Int,
    val onRed: Int,
    val tierExcellent: Int,
    val tierGood: Int,
    val tierNeeds: Int,
    val rising: Int,
    val falling: Int,
)

/**
 * The app's designed palette, light and dark — the dashboard mockup's (petrol primary, lagoon hero,
 * no indigo, no gold), the one `OpenCircuitTheme` builds its fixed colour schemes from. Pure
 * values, so their contrast is checked on the JVM (`PaletteContrastTest`).
 */
object Palette {

    val LIGHT: Tones = Tones(
        page = 0xFFF4F6F5.toInt(),
        card = 0xFFFFFFFF.toInt(),
        tonal = 0xFFE8EEEC.toInt(),
        ink = 0xFF16201E.toInt(),
        ink2 = 0xFF4A5856.toInt(),
        ink3 = 0xFF6B7A77.toInt(),
        primary = 0xFF1F5F6B.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        hero = 0xFFCDE6E9.toInt(),
        onHero = 0xFF0E2F35.toInt(),
        heart = 0xFFB4415A.toInt(),
        amber = 0xFFFFE7C2.toInt(),
        onAmber = 0xFF7A4A00.toInt(),
        red = 0xFFFBD9D4.toInt(),
        onRed = 0xFF9E241C.toInt(),
        tierExcellent = 0xFF2F6DB5.toInt(),
        tierGood = 0xFF2A9D8F.toInt(),
        tierNeeds = 0xFFC2711F.toInt(),
        // The live chart's darker pair keeps the line at 3:1 or more on a light surface.
        rising = 0xFF1B6D36.toInt(),
        falling = 0xFFB3261E.toInt(),
    )

    val DARK: Tones = Tones(
        page = 0xFF0F1514.toInt(),
        card = 0xFF172020.toInt(),
        tonal = 0xFF1F2A29.toInt(),
        ink = 0xFFE2EAE8.toInt(),
        ink2 = 0xFFB4C2BF.toInt(),
        ink3 = 0xFF8C9C99.toInt(),
        primary = 0xFF86CDD6.toInt(),
        onPrimary = 0xFF00363D.toInt(),
        hero = 0xFF1B4A52.toInt(),
        onHero = 0xFFCDE6E9.toInt(),
        heart = 0xFFF28BA0.toInt(),
        amber = 0xFF3A2A0D.toInt(),
        onAmber = 0xFFF6C26E.toInt(),
        red = 0xFF4A1915.toInt(),
        onRed = 0xFFF5B9B1.toInt(),
        tierExcellent = 0xFF4F86D9.toInt(),
        tierGood = 0xFF23998A.toInt(),
        tierNeeds = 0xFFD07A2A.toInt(),
        // Upstream's pastel pair, made for a dark surface.
        rising = 0xFF6DD58C.toInt(),
        falling = 0xFFFFB4AB.toInt(),
    )
}
