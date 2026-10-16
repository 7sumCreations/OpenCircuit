// :health — the Health Connect writer. A Kotlin Multiplatform library with two targets:
//   jvm()     — where the record rules, the ids and the writer are tested against FakeHealthConnect
//               (`./gradlew test`);
//   android   — the library the app links, with the one adapter over Jetpack Health Connect in
//               androidMain (the only code in the module that imports androidx.health).
// It depends on :ringkit, never the reverse, and not on :store: the app hands the writer its store
// through small interfaces.
//
// The test double (FakeHealthConnect, which enforces the platform's record rules) lives in
// src/jvmTestFixtures and is published as this module's test fixtures. Another module's tests
// use it with:   testImplementation(testFixtures(project(":health")))
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "io.github.opencircuit.health"
        compileSdk = 36
        minSdk = 34
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":ringkit"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        androidMain.dependencies {
            // 1.1.0 is the newest stable release; its AAR needs compileSdk 36. On Android 14 and
            // later it is a thin wrapper over the platform's Health Connect: no Play services, no
            // network library.
            implementation("androidx.health.connect:connect-client:1.1.0")
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
    val jvmCompilations = jvm().compilations
    jvmCompilations.named("test") {
        associateWith(jvmCompilations.getByName("testFixtures"))
    }
    jvmCompilations.named("testFixtures") {
        associateWith(jvmCompilations.getByName("main"))
    }
}

// A multiplatform module has no `test` task, so `./gradlew test` would silently skip this module.
// This lifecycle task wires the JVM tests in. `--tests` filters work on `:health:jvmTest` only.
tasks.register("test") {
    group = "verification"
    description = "Runs the JVM tests of :health (alias of jvmTest)."
    dependsOn("jvmTest")
}

tasks.named<Test>("jvmTest") {
    // `--rerun` reaches only the tasks named on the command line: `./gradlew test --rerun` re-runs
    // the lifecycle `test` above but would replay an up-to-date `jvmTest` and run no test at all.
    outputs.upToDateWhen { false }
    useJUnitPlatform()
    maxParallelForks = 1
    // The source-scan tests read the module and the app from the source tree.
    systemProperty("opencircuit.androidRoot", rootProject.projectDir.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // One verbatim summary line per module, in the same format as the other modules' lines.
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
