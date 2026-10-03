package io.github.opencircuit.store

/**
 * Kotlin source with its comments blanked out, for the source audits: prose may name what the code
 * must not do (or a test that does not exist), and only code counts. Line comments, block comments
 * (nested, as Kotlin allows) and KDoc become spaces; every line break stays, so line numbers still
 * match. A comment opener inside a string or character literal does not open a comment.
 *
 * Literals are kept by [withoutComments] (a `${…}` template inside one is code) and blanked inside
 * their quotes by [declarationsOnly], for checks that look for declarations, which never sit in a
 * literal.
 */
internal object KotlinSourceText {

    fun withoutComments(text: String): String = strip(text, blankLiterals = false)

    fun declarationsOnly(text: String): String = strip(text, blankLiterals = true)

    private fun strip(text: String, blankLiterals: Boolean): String {
        val out = StringBuilder(text.length)
        var i = 0
        fun blank(until: Int) {
            for (k in i until until) out.append(if (text[k] == '\n') '\n' else ' ')
            i = until
        }
        fun literal(open: Int, end: Int, close: Int) {
            if (!blankLiterals) {
                out.append(text, i, minOf(end + close, text.length))
                i = minOf(end + close, text.length)
                return
            }
            out.append(text, i, i + open)
            i += open
            blank(end)
            out.append(text, i, minOf(i + close, text.length))
            i = minOf(i + close, text.length)
        }
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> blank(text.indexOf('\n', i).let { if (it < 0) text.length else it })
                text.startsWith("/*", i) -> {
                    var depth = 0
                    var k = i
                    while (k < text.length) {
                        if (text.startsWith("/*", k)) {
                            depth++
                            k += 2
                        } else if (text.startsWith("*/", k)) {
                            depth--
                            k += 2
                            if (depth == 0) break
                        } else {
                            k++
                        }
                    }
                    blank(minOf(k, text.length))
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it }
                    literal(open = 3, end = end, close = 3)
                }
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i]
                    var k = i + 1
                    while (k < text.length && text[k] != quote && text[k] != '\n') k += if (text[k] == '\\') 2 else 1
                    val end = minOf(k, text.length)
                    literal(open = 1, end = end, close = if (end < text.length && text[end] == quote) 1 else 0)
                }
                else -> out.append(text[i++])
            }
        }
        return out.toString()
    }
}
