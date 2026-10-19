package io.github.opencircuit.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The writer's store interface, read from source: the write-race guard is required of every store,
 * never something an implementer can leave out.
 *
 * A flush reads the store's row watermark before it reads what is pending and hands it back with
 * every mark, so a mark never passes a sample the store took in while the flush was sending. An
 * interface that declares either member with a default body lets a store override only the plain
 * mark and get the guard silently switched off — the shape this build's own instrumented-test
 * stores had, where the writer runs against the real platform and the guard was absent.
 */
class HealthStoreContractTest {

    private val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "opencircuit.androidRoot is not set"))
    private val writer = File(root, "health/src/commonMain/kotlin/io/github/opencircuit/health/HealthWriter.kt").readText()

    @Test
    fun theWriteRaceGuardIsRequiredOfEveryStore() {
        assertTrue(writer.contains("interface HealthSampleStore"), "the scan found no HealthSampleStore to read")
        assertEquals(emptyList(), StoreContractScan.optOuts(writer))
    }

    // --- the scan itself can fail ----------------------------------------------------------------

    @Test
    fun theScanNamesEachWayTheGuardCanBeOptedOut() {
        val optionalWatermark = writer.replace("suspend fun rowWatermark(): Long?\n", "suspend fun rowWatermark(): Long? = null\n")
        assertEquals(listOf("row watermark required"), StoreContractScan.optOuts(optionalWatermark))

        val optionalMark = writer.replace(
            "suspend fun markWritten(samples: List<QuantitySample>, seenWatermark: Long?)\n",
            "suspend fun markWritten(samples: List<QuantitySample>, seenWatermark: Long?) = Unit\n",
        )
        assertEquals(listOf("mark by watermark required"), StoreContractScan.optOuts(optionalMark))

        val unguarded = writer.replace(
            "suspend fun markWritten(samples: List<QuantitySample>, seenWatermark: Long?)\n",
            "suspend fun markWritten(samples: List<QuantitySample>)\n",
        )
        assertEquals(listOf("mark by watermark required", "no unguarded mark overload"), StoreContractScan.optOuts(unguarded))
    }
}

/** What [HealthStoreContractTest] requires of [HealthSampleStore], each named. */
object StoreContractScan {
    private val required = linkedMapOf(
        "row watermark required" to Regex("""suspend fun rowWatermark\(\): Long\?\n"""),
        "mark by watermark required" to Regex("""suspend fun markWritten\(samples: List<QuantitySample>, seenWatermark: Long\?\)\n"""),
    )

    /** A mark a store can implement instead of the guarded one: the watermark never reaches it. */
    private val unguardedMark = Regex("""suspend fun markWritten\(samples: List<QuantitySample>\)""")

    /** The shapes [source] leaves optional. */
    fun optOuts(source: String): List<String> {
        val missing = required.filterValues { !it.containsMatchIn(source) }.keys.toMutableList()
        if (unguardedMark.containsMatchIn(source)) missing += "no unguarded mark overload"
        return missing
    }
}
