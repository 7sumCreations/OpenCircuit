package io.github.opencircuit.ringkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DoD1 (E0): android/UPSTREAM.md and RingKit.UPSTREAM_SHA must name the same upstream commit.
 * The pin line regex is shared with android/scripts/upstream-diff.sh (ADR E0 D2) — change both together.
 * A14: the expected value is read from the live file, never a SHA literal of this test's own.
 */
class UpstreamPinTest {
    private val pinLine = Regex("""^Pinned SHA: ([0-9a-f]{40})\s*$""")

    private fun pinsIn(lines: List<String>): List<String> =
        lines.mapNotNull { pinLine.matchEntire(it)?.groupValues?.get(1) }

    @Test
    fun upstreamMdPinMatchesRingKitConstant() {
        val path = assertNotNull(
            System.getProperty("opencircuit.upstreamMd"),
            "system property opencircuit.upstreamMd is not set — see ringkit/build.gradle.kts",
        )
        val file = File(path)
        assertTrue(file.isFile, "UPSTREAM.md not found at $path")
        val pins = pinsIn(file.readLines())
        assertEquals(1, pins.size, "expected exactly one 'Pinned SHA: <40-hex>' line in $path, found ${pins.size}")
        assertEquals(
            RingKit.UPSTREAM_SHA,
            pins.single(),
            "UPSTREAM.md pins ${pins.single()} but RingKit.UPSTREAM_SHA is ${RingKit.UPSTREAM_SHA} — bump both together",
        )
    }
}
