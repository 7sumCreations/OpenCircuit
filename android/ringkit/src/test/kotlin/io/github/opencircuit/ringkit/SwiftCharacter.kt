package io.github.opencircuit.ringkit

// Swift's `Character.isNumber`, for the ported tests that assert a string carries no number.
//
// Swift's `isNumber` is true when the character's Unicode Numeric_Type is set, which is NOT Kotlin's
// `isDigit` (decimal digits only) and not Java's `getNumericValue` (which also answers for the letters
// A–Z and their fullwidth forms). Measured on the pinned toolchain (Swift 6.3.2) over every Unicode
// scalar: 2 023 scalars are numbers — every scalar of the three number categories (decimal digit,
// letter number, other number; 1 924) and 99 ideographs and cuneiform signs with a numeric value
// (八, 万, 零, …), listed below. Kotlin's `isDigit` knows 650 of the 2 023.
//
// On the JDK this suite runs on (17, Unicode 13) this predicate gives exactly Swift's answer for every
// scalar except 143 number scalars assigned after Unicode 13, which the JDK does not know yet (all in
// the supplementary planes). The check is per code point, so it flags at least every character
// upstream's per-character check flags.

/** The 99 scalars outside the number categories whose Numeric_Type Swift reads as set. */
private val NUMERIC_IDEOGRAPHS: Set<Int> = intArrayOf(
    0x3405, 0x3483, 0x382A, 0x3B4D, 0x4E00, 0x4E03, 0x4E07, 0x4E09, 0x4E24, 0x4E5D, 0x4E8C, 0x4E94, 0x4E96, 0x4EAC, 0x4EBF,
    0x4EC0, 0x4EDF, 0x4EE8, 0x4F0D, 0x4F70, 0x4FE9, 0x5006, 0x5104, 0x5146, 0x5169, 0x516B, 0x516D, 0x5341, 0x5343, 0x5344,
    0x5345, 0x534C, 0x53C1, 0x53C2, 0x53C3, 0x53C4, 0x56DB, 0x58F1, 0x58F9, 0x5E7A, 0x5EFE, 0x5EFF, 0x5F0C, 0x5F0D, 0x5F0E,
    0x5F10, 0x62D0, 0x62FE, 0x634C, 0x67D2, 0x6D1E, 0x6F06, 0x7396, 0x767E, 0x7695, 0x79ED, 0x8086, 0x842C, 0x8CAE, 0x8CB3,
    0x8D30, 0x920E, 0x94A9, 0x9621, 0x9646, 0x964C, 0x9678, 0x96F6, 0xF96B, 0xF973, 0xF978, 0xF9B2, 0xF9D1, 0xF9D3, 0xF9FD,
    0x12038, 0x12039, 0x12079, 0x12226, 0x1222B, 0x1230B, 0x1230D, 0x12399, 0x20001, 0x20064, 0x200E2, 0x20121, 0x2092A,
    0x20983, 0x2098C, 0x2099C, 0x20AEA, 0x20AFD, 0x20B19, 0x22390, 0x22998, 0x23B1B, 0x2626D, 0x2F890,
).toSet()

/** Swift's `Character.isNumber` for one code point: a number category, or a numeric ideograph. */
internal fun isSwiftNumber(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
    else -> codePoint in NUMERIC_IDEOGRAPHS
}

/** Swift's `string.contains { $0.isNumber }`, checked per code point. */
internal fun String.containsSwiftNumber(): Boolean = codePoints().anyMatch(::isSwiftNumber)
