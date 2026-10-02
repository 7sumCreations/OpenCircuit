package io.github.opencircuit.ringkit

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Test-side stand-ins for Foundation's `Date`, which holds one `Double` of seconds since 2001-01-01 UTC
 * (its reference date). Written with exact decimal arithmetic, independently of the port's own
 * conversion, so a test can state an expected instant as the very `Double` upstream printed.
 */
internal object FoundationDate {

    /** 2001-01-01 00:00:00 UTC in seconds since 1970 — Foundation's `timeIntervalBetween1970AndReferenceDate`. */
    const val REFERENCE_UNIX_SECONDS: Long = 978_307_200L

    /** The instant a Swift `Date(timeIntervalSince1970: x)` holds, to the nearest nanosecond. */
    fun unix(x: Double): Instant = exact(BigDecimal(x))

    /** The instant a Swift `Date(timeIntervalSinceReferenceDate: x)` holds, to the nearest nanosecond. */
    fun reference(x: Double): Instant = exact(BigDecimal(x).add(BigDecimal.valueOf(REFERENCE_UNIX_SECONDS)))

    /** The instant whose seconds since 2001 are the double with these IEEE-754 bits (a golden's token). */
    fun referenceBits(bits: Long): Instant = reference(java.lang.Double.longBitsToDouble(bits))

    /** [t]'s seconds since 2001 as the nearest `Double` — what `timeIntervalSinceReferenceDate` prints. */
    fun referenceSeconds(t: Instant): Double =
        BigDecimal.valueOf(t.epochSecond - REFERENCE_UNIX_SECONDS).add(BigDecimal.valueOf(t.nano.toLong(), 9)).toDouble()

    private fun exact(seconds: BigDecimal): Instant {
        val ns = seconds.setScale(9, RoundingMode.HALF_EVEN)
        val whole = ns.setScale(0, RoundingMode.FLOOR)
        return Instant.ofEpochSecond(whole.longValueExact(), ns.subtract(whole).movePointRight(9).longValueExact())
    }
}
