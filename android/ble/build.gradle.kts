// :ble — the Bluetooth link to the ring. A Kotlin Multiplatform library with two targets:
//   jvm()     — where the link logic is tested against a scripted fake ring (`./gradlew test`);
//   android   — the library the app links, with the Android GATT adapter in androidMain.
// It depends on :ringkit (the protocol core), never the reverse.
//
// Test doubles (a scripted fake ring, FakeGatt, and a fake link, FakeRingLink) live in
// src/jvmTestFixtures and are published as this module's test fixtures. Another module's tests
// use them with:   testImplementation(testFixtures(project(":ble")))
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "io.github.opencircuit.ble"
        compileSdk = 36
        minSdk = 34
    }
    sourceSets {
        commonMain.dependencies {
            // The public link types expose :ringkit values and coroutine flows.
            api(project(":ringkit"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        jvmTest {
            // runCurrent / advanceTimeBy / UnconfinedTestDispatcher are still marked experimental.
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
    // The link's own tests run against the test doubles in src/jvmTestFixtures.
    val jvmCompilations = jvm().compilations
    jvmCompilations.named("test") {
        associateWith(jvmCompilations.getByName("testFixtures"))
    }
}

// A multiplatform module has no `test` task, so `./gradlew test` would silently skip this module.
// This lifecycle task wires the JVM tests in. `--tests` filters work on `:ble:jvmTest` only.
tasks.register("test") {
    group = "verification"
    description = "Runs the JVM tests of :ble (alias of jvmTest)."
    dependsOn("jvmTest")
}

tasks.named<Test>("jvmTest") {
    // `--rerun` reaches only the tasks named on the command line: `./gradlew test --rerun` re-runs
    // the lifecycle `test` above but would replay an up-to-date `jvmTest` and run no test at all.
    outputs.upToDateWhen { false }
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // One verbatim summary line per module, in the same format as :ringkit's and :store's.
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
