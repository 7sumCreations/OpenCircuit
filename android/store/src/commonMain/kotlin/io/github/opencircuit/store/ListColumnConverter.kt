package io.github.opencircuit.store

import androidx.room3.ColumnTypeConverter
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.array
import io.github.opencircuit.store.codec.int
import io.github.opencircuit.store.codec.readStored
import io.github.opencircuit.store.codec.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Upstream's `[String]` and `[Int]` columns (tags, Health sample ids, movement levels) are kept as a
 * JSON array in a text column, read by the same strict reader as every stored form.
 *
 * Writing checks that the text reads back as the same list and throws otherwise, so the write fails
 * and nothing unreadable is stored: a Kotlin string can hold a lone surrogate, which no Swift string
 * can, and which no JSON reader takes back. Reading a list that is not a well-formed array (a
 * damaged row) throws too: an empty list would silently lose the ids of Health samples that must
 * still be deleted, so the read fails loudly instead.
 */
internal class ListColumnConverter {
    @ColumnTypeConverter
    fun stringsToText(list: List<String>): String = checkedText(JsonArray(list.map(::JsonPrimitive)).toString(), list, ::textToStrings)

    @ColumnTypeConverter
    fun textToStrings(text: String): List<String> = read(text) { it.array().map { e -> e.string() } }

    @ColumnTypeConverter
    fun intsToText(list: List<Int>): String = checkedText(JsonArray(list.map(::JsonPrimitive)).toString(), list, ::textToInts)

    @ColumnTypeConverter
    fun textToInts(text: String): List<Int> = read(text) { it.array().map { e -> e.int() } }

    private fun <T> checkedText(text: String, list: List<T>, back: (String) -> List<T>): String {
        val readBack = try {
            back(text)
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException("list cannot be stored: ${e.message}", e)
        }
        require(readBack == list) { "list cannot be stored: it does not read back as itself" }
        return text
    }

    private fun <T> read(text: String, block: (JsonElement) -> List<T>): List<T> =
        when (val d = readStored(text, block)) {
            is Decoded.Readable -> d.value
            is Decoded.Unreadable -> throw IllegalStateException("stored list unreadable: ${d.reason}")
        }
}
