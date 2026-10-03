package io.github.opencircuit.store

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JUnit 5 discovers a `@Test` method only when it returns void. In Kotlin,
 * `@Test fun x() = runBlocking { …; someValue }` returns `someValue`'s type, so the test is silently
 * never run: no failure, no skip, a green suite (measured on this module: 5 of 7 tests in one class
 * ran). The migration-class count check catches it for one class; this guard catches it for every
 * compiled test class of the module, by reading the methods the compiler emitted.
 */
class TestDiscoveryGuardTest {

    @Test
    fun everyTestMethodInTheModuleReturnsVoidSoJUnitRunsIt() {
        val root = File(TestDiscoveryGuardTest::class.java.protectionDomain.codeSource.location.toURI())
        val classFiles = root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        assertTrue(classFiles.size > 40, "found only ${classFiles.size} compiled test classes under $root")

        val testAnnotation = org.junit.jupiter.api.Test::class.java
        var testMethods = 0
        val hidden = mutableListOf<String>()
        for (file in classFiles) {
            val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val type = Class.forName(name, false, javaClass.classLoader)
            for (method in type.declaredMethods) {
                if (!method.isAnnotationPresent(testAnnotation)) continue
                testMethods++
                if (method.returnType != Void.TYPE) hidden += "$name.${method.name} returns ${method.returnType.simpleName}"
            }
        }
        assertTrue(testMethods > 200, "found only $testMethods @Test methods")
        assertTrue(hidden.isEmpty(), "@Test methods JUnit will never run (use runBlocking<Unit>):\n" + hidden.joinToString("\n"))
    }

    /**
     * `./gradlew test --rerun` re-runs only the lifecycle `test` alias; without this line the store's
     * `jvmTest` replays as up to date on an unchanged tree and the suite prints no store summary at
     * all while the build still succeeds (measured: the second of two consecutive runs ran 0 store
     * tests). Pinned here because nothing else in the suite can see a test task that did not run.
     */
    @Test
    fun theStoreTestTaskIsNeverUpToDateSoARerunAlwaysRunsIt() {
        val storeDir = File(checkNotNull(System.getProperty("opencircuit.storeDir")) { "opencircuit.storeDir is not set — see store/build.gradle.kts" })
        val build = File(storeDir, "build.gradle.kts").readText()
        val jvmTestBlock = build.substringAfter("tasks.named<Test>(\"jvmTest\") {", missingDelimiterValue = "")
        assertTrue(jvmTestBlock.isNotEmpty(), "store/build.gradle.kts has no jvmTest task block")
        assertTrue(
            jvmTestBlock.substringBefore("\n}").contains("outputs.upToDateWhen { false }"),
            "the jvmTest task must declare outputs.upToDateWhen { false } so `test --rerun` always runs the store suite",
        )
    }
}
