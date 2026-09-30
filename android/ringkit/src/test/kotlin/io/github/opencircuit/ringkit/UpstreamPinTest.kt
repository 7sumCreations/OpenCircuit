package io.github.opencircuit.ringkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * android/UPSTREAM.md and RingKit.UPSTREAM_SHA must name the same upstream commit.
 * The pin line regex is shared with android/scripts/upstream-diff.sh — change both together.
 * The expected value is read from the live file, never a SHA literal of this test's own.
 */
class UpstreamPinTest {
    private val pinLine = Regex("""^Pinned SHA: ([0-9a-f]{40})\s*$""")

    private fun pinsIn(lines: List<String>): List<String> =
        lines.mapNotNull { pinLine.matchEntire(it)?.groupValues?.get(1) }

    private fun singlePin(lines: List<String>, where: String): String {
        val pins = pinsIn(lines)
        assertEquals(1, pins.size, "expected exactly one 'Pinned SHA: <40-hex>' line in $where, found ${pins.size}")
        return pins.first()
    }

    @Test
    fun upstreamMdPinMatchesRingKitConstant() {
        val path = assertNotNull(
            System.getProperty("opencircuit.upstreamMd"),
            "system property opencircuit.upstreamMd is not set — see ringkit/build.gradle.kts",
        )
        val file = File(path)
        assertTrue(file.isFile, "UPSTREAM.md not found at $path")
        val pin = singlePin(file.readLines(), path)
        assertEquals(
            RingKit.UPSTREAM_SHA,
            pin,
            "UPSTREAM.md pins $pin but RingKit.UPSTREAM_SHA is ${RingKit.UPSTREAM_SHA} — bump both together",
        )
    }

    @Test
    fun exactlyOnePinLineIsRequired() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(sha, singlePin(listOf("# Upstream", "Pinned SHA: $sha", "Bump: edit the Pinned SHA: line"), "doc"))
        assertFailsWith<AssertionError>("no pin line") { singlePin(listOf("# Upstream"), "doc") }
        assertFailsWith<AssertionError>("two pin lines") {
            singlePin(listOf("Pinned SHA: $sha", "Pinned SHA: ${sha.reversed()}"), "doc")
        }
    }

    @Test
    fun ringKitConstantIsAFullLowercaseSha() {
        assertEquals(
            listOf(RingKit.UPSTREAM_SHA),
            pinsIn(listOf("Pinned SHA: ${RingKit.UPSTREAM_SHA}")),
            "RingKit.UPSTREAM_SHA must itself satisfy the pin-line format",
        )
    }

    @Test
    fun pinLineAcceptsOnlyAFullLowercaseShaAtLineStart() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(listOf(sha), pinsIn(listOf("Pinned SHA: $sha")))
        assertEquals(listOf(sha), pinsIn(listOf("Pinned SHA: $sha  \r")), "trailing whitespace / CR is tolerated")
        assertEquals(2, pinsIn(listOf("Pinned SHA: $sha", "Pinned SHA: $sha")).size, "duplicates are all counted")
        assertEquals(emptyList(), pinsIn(listOf("Pinned SHA: ${sha.uppercase()}")), "uppercase hex rejected")
        assertEquals(emptyList(), pinsIn(listOf("Pinned SHA: ${sha.dropLast(1)}")), "39 chars rejected")
        assertEquals(emptyList(), pinsIn(listOf("  Pinned SHA: $sha")), "indented line rejected")
        assertEquals(emptyList(), pinsIn(listOf("Pinned SHA: $sha trailing")), "trailing text rejected")
    }
}
