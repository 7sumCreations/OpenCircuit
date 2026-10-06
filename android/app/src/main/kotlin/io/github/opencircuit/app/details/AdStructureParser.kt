package io.github.opencircuit.app.details

/** Service data: the service's UUID and the bytes that follow it, as lower-case hex. */
data class AdServiceData(val uuid: String, val dataHex: String)

/** Manufacturer-specific data: the company identifier and the bytes after it, as lower-case hex. */
data class AdManufacturerData(val companyId: Int, val dataHex: String)

/**
 * One advertisement, decoded. UUIDs are lower-case hex: 16- and 32-bit ones as their 4 or 8
 * digits, 128-bit ones in the usual 8-4-4-4-12 form. [problems] says, in words, what could not be
 * read; empty when every element was well formed.
 */
data class AdRecord(
    /** The Flags element's value, or null when there was none. */
    val flags: Int? = null,
    /** Every service UUID listed, complete and incomplete lists alike, in order. */
    val serviceUuids: List<String> = emptyList(),
    val serviceData: List<AdServiceData> = emptyList(),
    val manufacturerData: List<AdManufacturerData> = emptyList(),
    /** The advertised transmit power in dBm, or null. */
    val txPowerDbm: Int? = null,
    /** The advertised name (UTF-8; bytes that are not valid UTF-8 show as U+FFFD), or null. */
    val localName: String? = null,
    /** The name came from a Complete Local Name element, not a Shortened one. */
    val localNameComplete: Boolean = false,
    /** The types of elements this parser does not decode, in order. */
    val otherTypes: List<Int> = emptyList(),
    val problems: List<String> = emptyList(),
)

/**
 * Decodes the raw bytes of an advertisement (the scan record Android reports): a run of AD
 * structures, each `[length][type][length − 1 bytes of data]` (Bluetooth Core Specification
 * Supplement, Part A §1). Decodes Flags, 16/32/128-bit service UUID lists, service data, the
 * shortened and complete local name, tx power and manufacturer-specific data; other types are
 * listed by number.
 *
 * Never throws, whatever the bytes: a zero length ends the record (the zero padding Android adds
 * is not a problem, more data after it is); an element that claims more bytes than remain ends
 * the parse with a problem, keeping what came before; an element too short for its type is
 * skipped with a problem. Nothing is allocated from a length before it is checked against the
 * bytes that remain, and the caller's array is read, never kept.
 */
object AdStructureParser {

    fun parse(record: ByteArray): AdRecord {
        val bytes = record.copyOf()
        val out = Builder()
        var at = 0
        while (at < bytes.size) {
            val length = bytes[at].toInt() and 0xFF
            if (length == 0) {
                if (bytes.drop(at + 1).any { it != 0.toByte() }) out.problems += "data after a zero-length element at byte $at was not read"
                break
            }
            val remaining = bytes.size - at - 1
            if (length > remaining) {
                out.problems += "the element at byte $at says it has $length bytes; only $remaining remain"
                break
            }
            val type = bytes[at + 1].toInt() and 0xFF
            val data = bytes.copyOfRange(at + 2, at + 1 + length)
            out.element(type, data, at)
            at += 1 + length
        }
        return out.build()
    }

    private class Builder {
        var flags: Int? = null
        val uuids = mutableListOf<String>()
        val serviceData = mutableListOf<AdServiceData>()
        val manufacturer = mutableListOf<AdManufacturerData>()
        var txPower: Int? = null
        var name: String? = null
        var nameComplete = false
        val other = mutableListOf<Int>()
        val problems = mutableListOf<String>()

        fun element(type: Int, data: ByteArray, at: Int) {
            when (type) {
                FLAGS -> if (data.isEmpty()) tooShort(at, "flags") else flags = data[0].toInt() and 0xFF
                UUID16_INCOMPLETE, UUID16_COMPLETE -> uuidList(data, 2, at)
                UUID32_INCOMPLETE, UUID32_COMPLETE -> uuidList(data, 4, at)
                UUID128_INCOMPLETE, UUID128_COMPLETE -> uuidList(data, 16, at)
                NAME_SHORTENED, NAME_COMPLETE -> {
                    name = String(data, Charsets.UTF_8)
                    nameComplete = type == NAME_COMPLETE
                }
                TX_POWER -> if (data.isEmpty()) tooShort(at, "tx power") else txPower = data[0].toInt() // signed
                SERVICE_DATA_16 -> serviceData(data, 2, at)
                SERVICE_DATA_32 -> serviceData(data, 4, at)
                SERVICE_DATA_128 -> serviceData(data, 16, at)
                MANUFACTURER -> if (data.size < 2) {
                    tooShort(at, "manufacturer data")
                } else {
                    val company = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    manufacturer += AdManufacturerData(company, hex(data, 2, data.size))
                }
                else -> other += type
            }
        }

        private fun uuidList(data: ByteArray, width: Int, at: Int) {
            val whole = data.size / width
            for (i in 0 until whole) uuids += uuid(data, i * width, width)
            if (data.size % width != 0) problems += "the UUID list at byte $at ends with ${data.size % width} stray bytes"
        }

        private fun serviceData(data: ByteArray, width: Int, at: Int) {
            if (data.size < width) {
                tooShort(at, "service data")
                return
            }
            serviceData += AdServiceData(uuid(data, 0, width), hex(data, width, data.size))
        }

        private fun tooShort(at: Int, what: String) {
            problems += "the $what element at byte $at is too short"
        }

        fun build() = AdRecord(flags, uuids.toList(), serviceData.toList(), manufacturer.toList(), txPower, name, nameComplete, other.toList(), problems.toList())
    }

    /** A little-endian UUID of [width] bytes at [from], most significant byte first. */
    private fun uuid(data: ByteArray, from: Int, width: Int): String {
        val digits = StringBuilder(width * 2)
        for (i in from + width - 1 downTo from) appendHex(digits, data[i])
        if (width != 16) return digits.toString()
        return digits.toString().let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
    }

    /** [data] from [from] until [until] as lower-case hex, ASCII whatever the locale. */
    private fun hex(data: ByteArray, from: Int, until: Int): String {
        val out = StringBuilder((until - from) * 2)
        for (i in from until until) appendHex(out, data[i])
        return out.toString()
    }

    private fun appendHex(out: StringBuilder, byte: Byte) {
        val value = byte.toInt() and 0xFF
        out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }

    private const val HEX = "0123456789abcdef"

    private const val FLAGS = 0x01
    private const val UUID16_INCOMPLETE = 0x02
    private const val UUID16_COMPLETE = 0x03
    private const val UUID32_INCOMPLETE = 0x04
    private const val UUID32_COMPLETE = 0x05
    private const val UUID128_INCOMPLETE = 0x06
    private const val UUID128_COMPLETE = 0x07
    private const val NAME_SHORTENED = 0x08
    private const val NAME_COMPLETE = 0x09
    private const val TX_POWER = 0x0A
    private const val SERVICE_DATA_16 = 0x16
    private const val SERVICE_DATA_32 = 0x20
    private const val SERVICE_DATA_128 = 0x21
    private const val MANUFACTURER = 0xFF
}
