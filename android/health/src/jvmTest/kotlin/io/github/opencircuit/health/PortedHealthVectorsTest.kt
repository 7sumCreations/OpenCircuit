package io.github.opencircuit.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Health Connect writer's ported upstream tests, checked against the upstream files (@ b1c2fdd)
 * instead of against their author's memory: every claimed port names an upstream test that is at
 * the cited line, and its Kotlin test exists and cites that line. Upstream
 * `HealthWatermarkTests.swift` is ported whole, so its test list must equal the claimed one.
 */
class PortedHealthVectorsTest {

    private val androidRoot = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "opencircuit.androidRoot is not set"))
    private val upstreamTests = File(androidRoot, "../ios/OpenCircuitTests")

    /** One claimed port: upstream file, line, test name → the Kotlin test file and test. */
    data class Port(val upstreamFile: String, val line: Int, val swiftName: String, val kotlinFile: String, val kotlinName: String)

    private val ports = listOf(
        Port("HealthWatermarkTests.swift", 29, "testAutoPersistDoesNotStarveHealthWrite", "store/src/jvmTest/kotlin/io/github/opencircuit/store/HealthWatermarkTest.kt", "autoPersistDoesNotStarveTheHealthWrite"),
        Port("HealthWatermarkTests.swift", 39, "testMarkWrittenAdvancesWatermark", "store/src/jvmTest/kotlin/io/github/opencircuit/store/HealthWatermarkTest.kt", "markWrittenAdvancesTheWatermark"),
        Port("HealthWatermarkTests.swift", 53, "testUnwrittenSamplesBackfill", "store/src/jvmTest/kotlin/io/github/opencircuit/store/HealthWatermarkTest.kt", "unwrittenSamplesBackfill"),
        Port("HealthWatermarkTests.swift", 62, "testZeroValuesExcluded", "store/src/jvmTest/kotlin/io/github/opencircuit/store/HealthWatermarkTest.kt", "zeroValuesAreExcluded"),
        Port("HealthKitShareTypesTests.swift", 210, "testShareStatePartialListsDeniedTypes", "health/src/jvmTest/kotlin/io/github/opencircuit/health/ShareStateTest.kt", "shareStatePartialListsDeniedTypes"),
        Port("HealthKitShareTypesTests.swift", 223, "testShareStateAuthorizedWhenAllGranted", "health/src/jvmTest/kotlin/io/github/opencircuit/health/ShareStateTest.kt", "shareStateAuthorizedWhenAllGranted"),
        Port("HealthKitShareTypesTests.swift", 230, "testShareStateUnauthorizedWhenHeartRateNotGranted", "health/src/jvmTest/kotlin/io/github/opencircuit/health/ShareStateTest.kt", "shareStateUnauthorizedWhenNothingGranted"),
        Port("HealthKitShareTypesTests.swift", 239, "testFriendlyNamesMapAndDedupe", "health/src/jvmTest/kotlin/io/github/opencircuit/health/ShareStateTest.kt", "friendlyNamesMapAndDedupe"),
        Port("HealthKitShareTypesTests.swift", 254, "testWriteFailureMapPersistsAndClearsPerMetric", "health/src/jvmTest/kotlin/io/github/opencircuit/health/FailureMapTest.kt", "writeFailureMapPersistsAndClearsPerType"),
        Port("HealthKitShareTypesTests.swift", 279, "testFlushResultFailuresDefaultEmptyAndDoNotCountAsWrite", "health/src/jvmTest/kotlin/io/github/opencircuit/health/FailureMapTest.kt", "flushResultFailuresDefaultEmptyAndDoNotCountAsWrite"),
    )

    private fun upstreamTestsOf(file: String): Map<Int, String> =
        File(upstreamTests, file).readLines().withIndex()
            .mapNotNull { (i, line) -> Regex("""^\s*func (test\w+)\(""").find(line)?.let { i + 1 to it.groupValues[1] } }.toMap()

    /** What is wrong with [claimed], by name; empty when every claim holds. */
    private fun problems(claimed: List<Port>): List<String> = claimed.flatMap { p ->
        val upstream = upstreamTestsOf(p.upstreamFile)
        val kotlin = File(androidRoot, p.kotlinFile).readText()
        listOfNotNull(
            "${p.upstreamFile}:${p.line} is not ${p.swiftName}".takeIf { upstream[p.line] != p.swiftName },
            "${p.kotlinName} missing".takeIf { "fun ${p.kotlinName}(" !in kotlin },
            "${p.kotlinFile} does not cite :${p.line}".takeIf { ":${p.line}" !in kotlin },
        )
    }

    @Test
    fun everyClaimedPortIsAtItsUpstreamLineAndHasItsKotlinTest() {
        assertEquals(10, ports.size)
        assertEquals(emptyList(), problems(ports))
    }

    @Test
    fun theWatermarkTestsArePortedWhole() {
        assertEquals(upstreamTestsOf("HealthWatermarkTests.swift").values.toSet(), ports.filter { it.upstreamFile == "HealthWatermarkTests.swift" }.map { it.swiftName }.toSet())
    }

    @Test
    fun theCheckFailsOnAWrongLineOrAMissingTest() {
        val wrongLine = ports[4].copy(line = 211)
        assertEquals(listOf("HealthKitShareTypesTests.swift:211 is not testShareStatePartialListsDeniedTypes", "health/src/jvmTest/kotlin/io/github/opencircuit/health/ShareStateTest.kt does not cite :211"), problems(listOf(wrongLine)))
        assertTrue(problems(listOf(ports[0].copy(kotlinName = "noSuchTest"))).single().endsWith("missing"))
        val oneDropped = ports.filter { it.upstreamFile == "HealthWatermarkTests.swift" }.drop(1).map { it.swiftName }.toSet()
        assertTrue(upstreamTestsOf("HealthWatermarkTests.swift").values.toSet() != oneDropped)
    }
}
