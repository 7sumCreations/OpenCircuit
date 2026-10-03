package io.github.opencircuit.ringkit

import java.util.Collections

/**
 * The export's JSON value tree and its writer, standing in for upstream's `[String: Any]` and
 * `JSONSerialization.data(withJSONObject:options: [.prettyPrinted, .sortedKeys])` byte for byte
 * (measured on the pinned toolchain; `ExportJsonTest`, and the export differential for whole files).
 *
 * Integers ([JInt]) and doubles ([JDouble]) are separate, as Swift's `Int` and `Double` are in the
 * dictionary: `5` and `5.0` both print `5`, but only a double can be non-finite. Containers are
 * snapshots of what they were built from.
 */
internal sealed class ExportJson {

    /** A JSON object; member order does not matter — the writer sorts keys as Foundation does. */
    class JObject(members: Map<String, ExportJson>) : ExportJson() {
        val members: Map<String, ExportJson> = Collections.unmodifiableMap(LinkedHashMap(members))
    }

    class JArray(items: List<ExportJson>) : ExportJson() {
        val items: List<ExportJson> = Collections.unmodifiableList(ArrayList(items))
    }

    class JString(val value: String) : ExportJson()

    /** A Swift `Int`. */
    class JInt(val value: Long) : ExportJson()

    /** A Swift `Double`. */
    class JDouble(val value: Double) : ExportJson()

    class JBool(val value: Boolean) : ExportJson()

    /** `NSNull()`. */
    object JNull : ExportJson()

    companion object {

        /** An object from distinct keys (a repeated key is a programming error, as in a Swift dictionary literal). */
        fun obj(vararg members: Pair<String, ExportJson>): JObject {
            val map = LinkedHashMap<String, ExportJson>(members.size * 2)
            for ((k, v) in members) require(map.put(k, v) == null) { "duplicate JSON key $k" }
            return JObject(map)
        }

        fun arr(items: List<ExportJson>): JArray = JArray(items)

        /**
         * [root] as `.prettyPrinted, .sortedKeys` text: two spaces per level, `"key" : value`, keys in
         * [FoundationText.JSON_KEY_ORDER], an empty object or array as its brackets around one empty
         * line, numbers by [FoundationText.jsonNumber], strings by [FoundationText.appendJsonQuoted],
         * no trailing newline.
         *
         * Returns null when any double in the tree is NaN or infinite. Foundation raises an uncaught
         * exception there and the app dies (measured), although upstream's `toJSON` documents nil for
         * a failed serialization; null is that documented contract (PORTING.md).
         */
        fun pretty(root: ExportJson): String? {
            val sb = StringBuilder()
            return if (write(root, 0, sb)) sb.toString() else null
        }

        /** Appends [v] at [indent]; false (stop) on a non-finite double. */
        private fun write(v: ExportJson, indent: Int, sb: StringBuilder): Boolean {
            when (v) {
                is JObject -> {
                    if (v.members.isEmpty()) {
                        spaces(sb.append("{\n\n"), indent).append('}')
                        return true
                    }
                    sb.append("{\n")
                    var first = true
                    for (key in v.members.keys.sortedWith(FoundationText.JSON_KEY_ORDER)) {
                        if (!first) sb.append(",\n")
                        first = false
                        FoundationText.appendJsonQuoted(spaces(sb, indent + 2), key).append(" : ")
                        if (!write(v.members.getValue(key), indent + 2, sb)) return false
                    }
                    spaces(sb.append('\n'), indent).append('}')
                }
                is JArray -> {
                    if (v.items.isEmpty()) {
                        spaces(sb.append("[\n\n"), indent).append(']')
                        return true
                    }
                    sb.append("[\n")
                    for ((k, item) in v.items.withIndex()) {
                        if (k > 0) sb.append(",\n")
                        if (!write(item, indent + 2, spaces(sb, indent + 2))) return false
                    }
                    spaces(sb.append('\n'), indent).append(']')
                }
                is JString -> FoundationText.appendJsonQuoted(sb, v.value)
                is JInt -> sb.append(v.value)
                is JDouble -> {
                    if (!v.value.isFinite()) return false
                    sb.append(FoundationText.jsonNumber(v.value))
                }
                is JBool -> sb.append(if (v.value) "true" else "false")
                JNull -> sb.append("null")
            }
            return true
        }

        private fun spaces(sb: StringBuilder, n: Int): StringBuilder {
            for (i in 0 until n) sb.append(' ')
            return sb
        }
    }
}
