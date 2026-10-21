package io.github.opencircuit.ringkit

/**
 * Swift's number text and rounding, for callers outside this module (the app's Today tiles print
 * upstream's `String(format: "%.1f", v)` and round with `rounded()`). Java's `String.format` rounds
 * half up from the decimal text and follows the default locale's digits and separator; Swift rounds
 * the exact binary value with ties to even and always prints ASCII with a point — so `0.25` is
 * "0.2" here, never Java's "0.3".
 */
object SwiftFormat {

    /** Swift's `String(format: "%.<n>f", v)` (`"%+.<n>f"` when [forceSign]): ASCII digits, a decimal point. */
    fun fixed(v: Double, fractionDigits: Int, forceSign: Boolean = false): String = swiftFixed(v, fractionDigits, forceSign)

    /** Swift's `rounded()`: to the nearest whole number, ties away from zero (`2.5` → 3, `-2.5` → −3). */
    fun rounded(v: Double): Double = roundHalfAwayFromZero(v)
}
