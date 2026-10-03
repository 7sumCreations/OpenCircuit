package io.github.opencircuit.ringkit

/**
 * How the export tests read an export's JSON back, as upstream's do with
 * `JSONSerialization.jsonObject(with:)`: through [ReplayJson], the replay harness's dependency-free
 * reader, which follows Foundation's reading rules and `as?` casts and keeps each number's literal.
 * One parser for the module's tests; this file adds only what the export suites need on top.
 */
internal object ExportJsonReader {

    /** [text] (an export's JSON) parsed; throws [ReplayJson.SyntaxError] on what Foundation would refuse. */
    fun parse(text: String): ReplayJson.Value = ReplayJson.parse(text.toByteArray(Charsets.UTF_8))

    /** The export's top level, which is always an object. */
    fun root(text: String): ReplayJson.Obj {
        val v = parse(text)
        require(v is ReplayJson.Obj) { "the export's top level is not an object" }
        return v
    }

    /**
     * Upstream's `isNumber` (`ExportSchemaV3Tests`): a JSON number — an `NSNumber` that is not a
     * `CFBoolean`. A boolean, null, string or absent value is not.
     */
    fun isNumber(v: ReplayJson.Value?): Boolean = v is ReplayJson.Number
}
