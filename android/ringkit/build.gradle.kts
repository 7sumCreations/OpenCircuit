plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    // ADR E0 D1 A: UpstreamPinTest reads the pin file via an absolute path,
    // never a working-dir-relative one (IDE runners use a different cwd).
    // inputs.files (a collection) keeps the task runnable while the file is absent,
    // so a missing UPSTREAM.md fails inside the test with a readable message.
    val upstreamMd = rootProject.file("UPSTREAM.md")
    systemProperty("opencircuit.upstreamMd", upstreamMd.absolutePath)
    inputs.files(upstreamMd)
        .withPropertyName("upstreamMd")
        .withPathSensitivity(PathSensitivity.NONE)
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // One verbatim summary line per module: the Test-Suite gate quotes these.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println(
                    "TEST SUMMARY ${project.path}: ${result.resultType} — ${result.testCount} tests, " +
                        "${result.successfulTestCount} passed, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped"
                )
            }
        }
    })
}
