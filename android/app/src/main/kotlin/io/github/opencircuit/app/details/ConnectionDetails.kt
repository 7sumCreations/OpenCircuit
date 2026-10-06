package io.github.opencircuit.app.details

import io.github.opencircuit.app.connect.PairingOutcome
import io.github.opencircuit.app.session.ConnectionTimings
import io.github.opencircuit.app.session.DispatchCounts
import io.github.opencircuit.app.session.SessionTeardowns
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.LinkDiagnostic
import io.github.opencircuit.ble.LinkInfo
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.ScanDiagnostic
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One line of the card: a label and its value, both plain text. */
data class DetailRow(val label: String, val value: String)

/** Everything the Connection details card shows, and the text Copy puts on the clipboard. */
data class ConnectionDetailsUi(
    /** The collapsed card: a few rows, the address and the ring's name masked. */
    val summary: List<DetailRow>,
    /** Every row with its full value: shown only when the card is expanded. */
    val rows: List<DetailRow>,
    /** The last advertisement decoded, full values: shown only when expanded. */
    val advertisement: List<DetailRow>,
    /** The link's diagnostics, oldest first, as the link wrote them; "not available" when it keeps none. */
    val diagnostics: List<String>,
    /** What Copy puts on the clipboard: every row and line, the address and the name masked. */
    val copyText: String,
)

/** What the card reads beyond the ring's session: the pairing sheet's outcome and the scanner's last match. */
data class DetailsSources(
    val pairingOutcome: StateFlow<PairingOutcome?> = MutableStateFlow(null),
    /** `(scanner as? ScanDiagnostics)?.lastMatch`, or null when the scanner keeps none. */
    val scanMatch: StateFlow<ScanDiagnostic?>? = null,
)

/** One moment's inputs to the card. Null means the surface is absent ("not available"). */
data class DetailsInput(
    val ring: RememberedRing? = null,
    val info: LinkInfo? = null,
    val timings: ConnectionTimings? = null,
    val counts: DispatchCounts? = null,
    val teardowns: SessionTeardowns? = null,
    /** The link's diagnostics; null when the link does not keep them. */
    val diagnostics: List<LinkDiagnostic>? = null,
    val pairing: PairingOutcome? = null,
    /** Whether a scanner keeps its last match at all. */
    val scanKept: Boolean = false,
    val scan: ScanDiagnostic? = null,
    val scanToSelectedMillis: Long? = null,
)

/**
 * The Connection details card: the phone is never on a cable, so this is where the owner reads how
 * the link came up. Every value is read from a field and worded here; nothing prints a `:ble`
 * value's `toString`. The link's diagnostics are shown as the link wrote them, never parsed: their
 * words may change.
 *
 * Privacy: the address, and the ring's name (which ends with the last two bytes of the address),
 * are masked in [ConnectionDetailsUi.summary] and in [ConnectionDetailsUi.copyText]; the Copy text
 * is also scrubbed of the address and the name's suffix wherever they appear, and carries only the
 * length of advertised payloads, not their bytes. The full values show only when the card is
 * expanded.
 */
object ConnectionDetailsPresenter {

    fun present(input: DetailsInput): ConnectionDetailsUi {
        val full = rows(input, masked = false)
        val masked = rows(input, masked = true)
        val diagnostics = diagnosticLines(input)
        val advertisementFull = advertisement(input, masked = false)
        val copy = buildString {
            appendLine("OpenCircuit connection details")
            masked.forEach { appendLine("${it.label}: ${it.value}") }
            appendLine()
            appendLine("Advertisement")
            advertisement(input, masked = true).forEach { appendLine("${it.label}: ${it.value}") }
            appendLine()
            appendLine("Link diagnostics")
            diagnostics.forEach { appendLine(it) }
        }
        val names = listOfNotNull(input.ring?.name, input.info?.firmware?.modelName, input.scan?.let { AdStructureParser.parse(it.rawScanRecord).localName })
        return ConnectionDetailsUi(
            summary = masked.filter { it.label in SUMMARY },
            rows = full,
            advertisement = advertisementFull,
            diagnostics = diagnostics,
            copyText = Privacy.scrub(copy, listOfNotNull(input.ring?.address, input.info?.mac), names),
        )
    }

    private fun rows(input: DetailsInput, masked: Boolean): List<DetailRow> {
        val ring = input.ring
        val info = input.info
        val timings = input.timings
        fun name(value: String?) = value?.takeIf(String::isNotEmpty)?.let { if (masked) Privacy.maskName(it) else it }
        return listOf(
            DetailRow("Ring", ring?.let { name(it.name) ?: "no name" } ?: NOT_AVAILABLE),
            DetailRow("Address", ring?.address?.let { if (masked) Privacy.maskAddress(it) else it } ?: NOT_AVAILABLE),
            DetailRow("Address type", ring?.addressType?.let(::addressType) ?: NOT_AVAILABLE),
            DetailRow("Firmware", info?.let(::firmware) ?: NOT_AVAILABLE),
            DetailRow("Model", info?.let { name(it.firmware.modelName) ?: NOT_READ } ?: NOT_AVAILABLE),
            DetailRow("MAC (auth)", info?.let { i -> i.mac?.let { if (masked) Privacy.maskAddress(it) else it } ?: NOT_READ } ?: NOT_AVAILABLE),
            DetailRow("ATT MTU", info?.attMtu?.toString() ?: NOT_AVAILABLE),
            DetailRow("History-safe", info?.historySafe?.let(::yesNo) ?: NOT_AVAILABLE),
            DetailRow("Bonded", info?.bonded?.let(::yesNo) ?: NOT_AVAILABLE),
            DetailRow("MAC mismatch", info?.macMismatch?.let(::yesNo) ?: NOT_AVAILABLE),
            DetailRow("Scan → selected", input.scanToSelectedMillis?.let(::duration) ?: NOT_MEASURED),
            DetailRow("Connect → authenticated", timings?.connectToAuthenticatedMillis?.let(::duration) ?: NOT_MEASURED),
            DetailRow("Reconnect wait (last)", timings?.reconnectWaitMillis?.let(::duration) ?: NOT_MEASURED),
            DetailRow("Pairing prompt", timings?.let(::pairingPrompt) ?: NOT_AVAILABLE),
            DetailRow("Pairing sheet", pairingSheet(input.pairing)),
            DetailRow("Frames not handled", input.counts?.let { opcodeCounts(it.unhandled) } ?: NOT_AVAILABLE),
            DetailRow("Handler failures", input.counts?.let { opcodeCounts(it.handlerFailures) } ?: NOT_AVAILABLE),
            DetailRow("Connections torn down", input.teardowns?.let(::teardowns) ?: NOT_AVAILABLE),
        )
    }

    private fun advertisement(input: DetailsInput, masked: Boolean): List<DetailRow> {
        val scan = input.scan
            ?: return listOf(DetailRow("Advertisement", if (input.scanKept) "none seen since the app started" else NOT_AVAILABLE))
        val ad = AdStructureParser.parse(scan.rawScanRecord)
        fun payload(hex: String) = if (masked) "${hex.length / 2} bytes" else hex
        return listOfNotNull(
            DetailRow("Signal", "${scan.rssi} dBm"),
            DetailRow("Address type (scan)", addressType(scan.addressType)),
            DetailRow("Flags", ad.flags?.let { "0x" + hexByte(it) } ?: "none"),
            DetailRow("Services", ad.serviceUuids.joinToString(" · ").ifEmpty { "none" }),
            DetailRow("Service data", ad.serviceData.joinToString(" · ") { "${it.uuid}: ${payload(it.dataHex)}" }.ifEmpty { "none" }),
            DetailRow(
                "Manufacturer data",
                ad.manufacturerData.joinToString(" · ") { "0x" + hexByte(it.companyId ushr 8) + hexByte(it.companyId and 0xFF) + ": " + payload(it.dataHex) }
                    .ifEmpty { "none" },
            ),
            DetailRow("Tx power", ad.txPowerDbm?.let { "$it dBm" } ?: "none"),
            DetailRow(
                "Name (advertised)",
                ad.localName?.let { (if (masked) Privacy.maskName(it) else it) + if (ad.localNameComplete) "" else " (shortened)" } ?: "none",
            ),
            ad.otherTypes.takeIf { it.isNotEmpty() }?.let { types -> DetailRow("Other elements", types.joinToString(" · ") { "0x" + hexByte(it) }) },
            ad.problems.takeIf { it.isNotEmpty() }?.let { DetailRow("Problems", it.joinToString("; ")) },
        )
    }

    private fun diagnosticLines(input: DetailsInput): List<String> {
        val diagnostics = input.diagnostics ?: return listOf(NOT_AVAILABLE)
        if (input.ring == null) return listOf(NOT_AVAILABLE)
        if (diagnostics.isEmpty()) return listOf("none yet")
        return diagnostics.map { d -> "+${d.sinceConnectMillis} ms · ${d.event}" + if (d.detail.isEmpty()) "" else " · ${d.detail}" }
    }

    private fun firmware(info: LinkInfo): String {
        val fw = info.firmware
        if (fw.version.isEmpty()) return NOT_READ
        return listOfNotNull(fw.version, fw.generation.rawValue, fw.manufacturer.takeIf(String::isNotEmpty), fw.hardwareRevision?.let { "hardware $it" })
            .joinToString(" · ")
    }

    private fun pairingPrompt(t: ConnectionTimings): String = when {
        t.pairingNeededNow -> "showing now"
        t.pairingNeededMillis != null -> "seen for ${duration(t.pairingNeededMillis)}"
        t.pairingNeededSeen -> "seen"
        else -> "not seen"
    }

    private fun pairingSheet(outcome: PairingOutcome?): String = when (outcome) {
        null -> "not used since the app started"
        PairingOutcome.Approved -> "allowed"
        is PairingOutcome.Declined -> (if (outcome.resultCode == 0) "closed" else "refused") + " (result ${outcome.resultCode})"
        is PairingOutcome.Fallback -> "connected without it: ${outcome.reason.words}" + (outcome.resultCode?.let { " (result $it)" } ?: "")
    }

    private fun teardowns(t: SessionTeardowns): String {
        if (t.count == 0) return "0"
        val last = t.last?.reason?.let(::teardownReason) ?: "unknown"
        return "${t.count} · last: $last · ${t.undeliveredFrames} frames undelivered in all"
    }

    private fun teardownReason(reason: TeardownReason): String = when (reason) {
        TeardownReason.LINK_DROPPED -> "link dropped"
        TeardownReason.SESSION_REPLACED -> "session replaced"
        TeardownReason.SWITCHING_RING -> "switching ring"
        TeardownReason.USER_DISCONNECTED -> "user disconnected"
        TeardownReason.BACKGROUND_READ_ENDED -> "background read ended"
    }

    private fun opcodeCounts(counts: Map<Int, Int>): String =
        counts.toSortedMap().entries.joinToString(" · ") { "0x${hexByte(it.key)} × ${it.value}" }.ifEmpty { "none" }

    private fun addressType(type: AddressType): String = when (type) {
        AddressType.PUBLIC -> "public"
        AddressType.RANDOM -> "random"
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** "2.5 s" below a minute, "1 min 5 s" above; ASCII digits whatever the locale. */
    private fun duration(millis: Long): String {
        if (millis < 60_000) return "${millis / 1_000}.${(millis % 1_000) / 100} s"
        return "${millis / 60_000} min ${(millis % 60_000) / 1_000} s"
    }

    private fun hexByte(value: Int): String = "${HEX[(value ushr 4) and 0xF]}${HEX[value and 0xF]}"

    private const val HEX = "0123456789abcdef"
    private const val NOT_AVAILABLE = "not available"
    private const val NOT_MEASURED = "not measured"
    private const val NOT_READ = "not read yet"

    /** The rows the collapsed card shows. */
    private val SUMMARY = setOf("Ring", "Address", "ATT MTU", "Bonded")
}

/**
 * Masking for anything that leaves the user's own screen. A ring's address is its MAC; its
 * advertised name ends with the last two bytes of that MAC (`RingConn Gen2-XXXX`).
 */
object Privacy {
    private const val DOT = '•'

    /** The first byte kept, the rest masked: "AA:••:••:••:••:••". Anything not shaped like an address is masked whole. */
    fun maskAddress(address: String): String {
        val parts = address.split(':')
        if (parts.size != 6) return DOT.toString().repeat(address.length.coerceAtMost(17))
        return (listOf(parts[0]) + List(5) { "$DOT$DOT" }).joinToString(":")
    }

    /**
     * The name's last word masked: everything after the last '-', ' ' or '_' (the model prefix
     * stays readable); a name with no such separator keeps all but its last four characters.
     */
    fun maskName(name: String): String {
        val cut = name.indexOfLast { it == '-' || it == ' ' || it == '_' }
        val keep = if (cut in 0 until name.length - 1) cut + 1 else (name.length - 4).coerceAtLeast(0)
        return name.substring(0, keep) + DOT.toString().repeat(name.length - keep)
    }

    /**
     * [text] with every [addresses] entry (any case, with or without colons) and every name's
     * masked-away suffix (four characters or more, any case) replaced by dots.
     */
    fun scrub(text: String, addresses: List<String>, names: List<String>): String {
        var out = text
        for (address in addresses) {
            out = out.replace(address, maskAddress(address), ignoreCase = true)
            val bare = address.replace(":", "")
            if (bare.length >= 4) out = out.replace(bare, DOT.toString().repeat(bare.length), ignoreCase = true)
        }
        for (name in names) {
            val suffix = name.substring(maskName(name).indexOf(DOT).takeIf { it >= 0 } ?: name.length)
            if (suffix.length >= 4) out = out.replace(suffix, DOT.toString().repeat(suffix.length), ignoreCase = true)
        }
        return out
    }
}
