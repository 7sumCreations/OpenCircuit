// :app — the Android application: one activity, Compose screens, and the app-level controllers.
//
// The controllers (the frame dispatcher, the session controller, the device-status model) are
// plain Kotlin in src/main and are tested on the JVM in src/test against :ble's fake link, with
// virtual time. The Compose screens are tested on an emulator in src/androidTest, against the
// in-app demo link in src/debug (the JVM fake link cannot run on a device).
//
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the Kotlin Android plugin is NOT applied;
// the Compose compiler plugin's version must equal the Kotlin version (2.3.0, root build file).
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. `android/keystore.properties` (git-ignored, never committed) names the
// keystore and the key: `storeFile=<absolute path outside the repository>` and
// `keyAlias=<alias>`. Both passwords come from the environment variable
// OPENCIRCUIT_KEYSTORE_PASSWORD, never from a file. Without the file there is no release signing
// config at all, and `checkReleaseSigning` (below) stops a release build before it packages, so an
// unsigned release APK is never produced. Debug builds never read any of this. See README.md,
// "Signing a release".
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}
val keystorePassword: Provider<String> = providers.environmentVariable("OPENCIRCUIT_KEYSTORE_PASSWORD")
val releaseStoreFile: File? = keystoreProperties.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
val releaseKeyAlias: String? = keystoreProperties.getProperty("keyAlias")?.trim()?.takeIf { it.isNotEmpty() }

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

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = releaseStoreFile
                keyAlias = releaseKeyAlias
                storePassword = keystorePassword.orNull
                keyPassword = keystorePassword.orNull
                // minSdk 34: v1 (JAR) signing is never read. v2 for every supported phone, v3 so the
                // key can be rotated later. v4 only adds an .idsig for incremental adb installs.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.isFile) signingConfig = signingConfigs.getByName("release")
            // No R8: the release runs the same bytecode the debug build was tested with.
            isMinifyEnabled = false
            isShrinkResources = false
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

// Fails a release build, at execution time, when signing is not set up: without it AGP quietly
// writes app-release-unsigned.apk. A configuration-time check would break debug builds and IDE
// sync, so this is a task that only the release packaging depends on. The message names what is
// missing and never prints a path or a password.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Fails unless keystore.properties and OPENCIRCUIT_KEYSTORE_PASSWORD are set up for a signed release."
    val propertiesFile = keystorePropertiesFile
    val storeFile = releaseStoreFile
    val alias = releaseKeyAlias
    val password = keystorePassword
    doLast {
        val missing = buildList {
            if (!propertiesFile.isFile) {
                add("the file android/keystore.properties is missing")
            } else {
                if (storeFile == null) {
                    add("keystore.properties has no storeFile")
                } else if (!storeFile.isFile) {
                    add("the keystore that storeFile in keystore.properties names does not exist")
                }
                if (alias == null) add("keystore.properties has no keyAlias")
            }
            if (password.orNull.isNullOrEmpty()) add("the environment variable OPENCIRCUIT_KEYSTORE_PASSWORD is not set")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not set up: ${missing.joinToString("; ")}. " +
                    "No unsigned release APK is built. See android/README.md, \"Signing a release\".",
            )
        }
    }
}

// The packaging task's name under AGP 9.2.1 is packageRelease (measured with `:app:tasks --all`).
// validateSigningRelease, when it exists, would otherwise report a missing password first, in its
// own words.
tasks.configureEach {
    if (name == "packageRelease" || name == "validateSigningRelease") dependsOn(checkReleaseSigning)
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
    // ui-test-junit4 alone brings Espresso 3.5.0, which fails on API 36 before any test runs
    // ("NoSuchMethodException: android.hardware.input.InputManager.getInstance").
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
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
