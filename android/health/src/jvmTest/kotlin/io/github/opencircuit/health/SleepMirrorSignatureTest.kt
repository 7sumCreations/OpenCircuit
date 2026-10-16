package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Port of upstream ios/OpenCircuitTests/SleepMirrorSignatureTests.swift (@ b1c2fdd), all six
 * (`:32`, `:38`, `:45`, `:56`, `:65`, `:77`): the night mirror rewrites Health Connect only when the
 * staging's signature changes, so it must be stable for identical staging and change for any
 * visible difference, including an interior reclassification that keeps the asleep total.
 */
class SleepMirrorSignatureTest {
    private val base = Instant.ofEpochSecond(1_769_000_000)

    private fun seg(startMin: Double, endMin: Double, stage: SleepStage) =
        SleepSegment(base.plusMillis((startMin * 60_000).toLong()), base.plusMillis((endMin * 60_000).toLong()), stage)

    /** A plausible night: in-bed span + latency-awake + a few sleep stages. */
    private fun night() = listOf(
        seg(0.0, 520.0, SleepStage.IN_BED),
        seg(0.0, 17.0, SleepStage.AWAKE), // latency
        seg(17.0, 200.0, SleepStage.ASLEEP_CORE),
        seg(200.0, 260.0, SleepStage.ASLEEP_DEEP),
        seg(260.0, 380.0, SleepStage.ASLEEP_REM),
        seg(380.0, 500.0, SleepStage.ASLEEP_CORE),
        seg(500.0, 520.0, SleepStage.AWAKE), // brief pre-wake
    )

    @Test
    fun identicalStagingHasIdenticalSignature() { // :32
        assertEquals(SleepSignature.of(night()), SleepSignature.of(night()), "Same staging must sign identically — else the mirror rewrites Health every flush.")
    }

    @Test
    fun signatureIsOrderIndependent() { // :38
        assertEquals(SleepSignature.of(night()), SleepSignature.of(night().reversed()), "Signature sorts by start, so input order must not matter.")
    }

    @Test
    fun interiorReclassificationChangesSignature() { // :45
        val restaged = night().toMutableList()
        restaged[restaged.size - 1] = seg(500.0, 520.0, SleepStage.ASLEEP_CORE)
        assertNotEquals(SleepSignature.of(night()), SleepSignature.of(restaged), "A reclassified block must change the signature so Health is corrected.")
    }

    @Test
    fun extendedWakeChangesSignature() { // :56
        val later = night().toMutableList()
        later[0] = seg(0.0, 560.0, SleepStage.IN_BED)
        later += seg(520.0, 560.0, SleepStage.ASLEEP_CORE)
        assertNotEquals(SleepSignature.of(night()), SleepSignature.of(later), "A longer night must change the signature.")
    }

    @Test
    fun subSecondJitterDoesNotChangeSignature() { // :65
        val jittered = night().map { SleepSegment(it.start.plusMillis(400), it.end.minusMillis(300), it.stage) }
        assertEquals(SleepSignature.of(night()), SleepSignature.of(jittered), "Sub-second jitter must not change the signature.")
    }

    @Test
    fun emptyIsStable() { // :77
        assertEquals(SleepSignature.of(emptyList()), SleepSignature.of(emptyList()))
    }

    // Kotlin-only

    @Test
    fun emptyIsTheFnv1aOffsetBasisInLowerHexAsUpstreamPrintsIt() {
        // FNV-1a 64's offset basis, printed by Swift's `String(hash, radix: 16)` (no padding).
        assertEquals("cbf29ce484222325", SleepSignature.of(emptyList()))
    }

    @Test
    fun aStageThatKeepsItsTimesButChangesKindChangesTheSignature() {
        val deepToRem = night().map { if (it.stage == SleepStage.ASLEEP_DEEP) it.copy(stage = SleepStage.ASLEEP_REM) else it }
        assertNotEquals(SleepSignature.of(night()), SleepSignature.of(deepToRem))
    }
}
