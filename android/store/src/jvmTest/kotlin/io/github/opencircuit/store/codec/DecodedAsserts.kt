package io.github.opencircuit.store.codec

import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The decoded value; fails the test when the stored text was unreadable. */
internal fun <T> readable(d: Decoded<T>): T = assertIs<Decoded.Readable<T>>(d, "expected readable, got $d").value

/** Fails the test unless the stored text was unreadable, and checks it kept the text as stored. */
internal fun assertUnreadable(d: Decoded<*>, raw: String, message: String = raw) {
    val u = assertIs<Decoded.Unreadable>(d, "expected unreadable: $message")
    assertEquals(raw, u.raw, "an unreadable value keeps its stored text")
}
