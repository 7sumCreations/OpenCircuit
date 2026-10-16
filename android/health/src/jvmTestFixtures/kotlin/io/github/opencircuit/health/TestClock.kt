package io.github.opencircuit.health

import java.time.Duration
import java.time.Instant

/** A clock the test moves by hand. */
class TestClock(private var current: Instant) {
    @Synchronized
    fun now(): Instant = current

    @Synchronized
    fun advance(by: Duration) {
        require(!by.isNegative) { "a test clock only moves forward through advance(); use set() to step back" }
        current = current.plus(by)
    }

    /** Sets the time, backwards too (a phone clock can be set back). */
    @Synchronized
    fun set(to: Instant) {
        current = to
    }
}
