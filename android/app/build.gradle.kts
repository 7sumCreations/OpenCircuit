// :app — the Android application: one activity, Compose screens, and the app-level controllers.
//
// The controllers (the frame dispatcher, the session controller, the device-status model) are
// plain Kotlin in src/main and are tested on the JVM in src/test against :ble's fake link, with
// virtual time. The Compose screens are tested on an emulator in src/androidTest, against the
// in-app demo link in src/debug (the JVM fake link cannot run on a device).
//
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the Kotlin Android plugin is NOT applied;
// the Compose compiler plugin's version must equal the Kotlin version (2.3.0, root build file).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.opencircuit.app"
    // Compose 1.12 and lifecycle 2.11 need compileSdk 37; runtime behaviour follows targetSdk.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.opencircuit.android"
        minSdk = 34
        // Explicit: AGP 9 otherwise sets targetSdk to compileSdk.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // runCurrent / advanceTimeBy are still marked experimental in kotlinx-coroutines-test.
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

// Host (JVM) unit tests run for the debug variant only. Left on, `./gradlew test` would run every
// test twice and print two summary lines for this module.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests.values.forEach { it.enable = false }
    }
}

dependencies {
    implementation(project(":ble"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Named explicitly: under built-in Kotlin, plain kotlin("test") does not pick the JUnit 5
    // variant for host tests, and `kotlin.test.Test` does not resolve.
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // The scripted fake link (FakeRingLink) from :ble's test fixtures. JVM only.
    testImplementation(testFixtures(project(":ble")))

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

tasks.withType<Test>().configureEach {
    // `--rerun` reaches only the tasks named on the command line: `./gradlew test --rerun` re-runs
    // the lifecycle `test` task but would replay an up-to-date `testDebugUnitTest` and run nothing.
    outputs.upToDateWhen { false }
    maxParallelForks = 1
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
