package io.github.opencircuit.app.data

import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.RememberedRing
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale

/**
 * The ring the app reconnects to (replaces upstream's remembered peripheral ids,
 * `ios/OpenCircuit/BLE/RingScanner.swift:96-164` @ b1c2fdd; Android reconnects by address).
 */
interface RememberedRingStore {
    /** The saved ring, or null when there is none or the saved value is damaged. */
    fun load(): RememberedRing?

    /** Saves [ring], replacing any other; false when its address is not valid or the file could not be written. */
    fun save(ring: RememberedRing): Boolean

    /** Forgets the saved ring; false when the file could not be written. */
    fun clear(): Boolean
}

/**
 * [RememberedRingStore] over [KeyValues]: one string under `ring.remembered.v1`, so the three
 * fields are written together or not at all. The form is `ADDRESS|TYPE|NAME`: the address
 * upper-case, the [AddressType] name, and the name as `-` (none) or `b64:` and the Base64 of its
 * UTF-8 bytes (a name can hold any character, the separator included). A value that does not
 * read back as all three, exactly, reads as no ring: a damaged file must never become a
 * connection to an address nobody chose.
 */
class PrefsRememberedRingStore(private val values: KeyValues) : RememberedRingStore {

    override fun load(): RememberedRing? = values.string(KEY)?.let(::decode)

    override fun save(ring: RememberedRing): Boolean {
        val address = RingAddress.normalized(ring.address) ?: return false
        return values.putString(KEY, encode(address, ring.addressType, ring.name))
    }

    override fun clear(): Boolean = values.remove(KEY)

    private fun encode(address: String, type: AddressType, name: String?): String {
        val namePart = if (name == null) NO_NAME else NAME_PREFIX + Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
        return listOf(address, type.name, namePart).joinToString(SEPARATOR)
    }

    private fun decode(raw: String): RememberedRing? {
        val parts = raw.split(SEPARATOR)
        if (parts.size != 3) return null
        val address = RingAddress.normalized(parts[0]) ?: return null
        val type = AddressType.entries.firstOrNull { it.name == parts[1] } ?: return null
        val name = when {
            parts[2] == NO_NAME -> null
            parts[2].startsWith(NAME_PREFIX) -> decodeName(parts[2].removePrefix(NAME_PREFIX)) ?: return null
            else -> return null
        }
        return RememberedRing(address, type, name)
    }

    /** The name from its Base64, or null when it is not Base64 of valid UTF-8. */
    private fun decodeName(base64: String): String? {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private companion object {
        const val KEY = "ring.remembered.v1"
        const val SEPARATOR = "|"
        const val NO_NAME = "-"
        const val NAME_PREFIX = "b64:"
    }
}

/**
 * A ring's Bluetooth address: six pairs of hex digits joined by colons, the form
 * `BluetoothDevice.getAddress()` gives. Kept upper-case; compared without regard to case.
 */
object RingAddress {
    /**
     * [address] upper-case, or null unless it is exactly six pairs of ASCII hex digits joined by
     * colons. Case is folded with [Locale.ROOT], and only ASCII digits count (`Char.isDigit`
     * would also take fullwidth and other scripts' digits).
     */
    fun normalized(address: String): String? {
        val upper = address.uppercase(Locale.ROOT)
        if (upper.length != LENGTH) return null
        upper.forEachIndexed { index, c ->
            val ok = if (index % 3 == 2) c == ':' else c in '0'..'9' || c in 'A'..'F'
            if (!ok) return null
        }
        return upper
    }

    /** Whether [a] and [b] name the same device, whatever their case. */
    fun same(a: String, b: String): Boolean = a.uppercase(Locale.ROOT) == b.uppercase(Locale.ROOT)

    /** "AA:BB:CC:DD:EE:FF". */
    private const val LENGTH = 17
}
