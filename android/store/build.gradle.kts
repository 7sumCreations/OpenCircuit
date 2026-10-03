// :store — the on-device database. A Kotlin Multiplatform library with two targets:
//   jvm()     — where every DAO, ingest and migration test runs (`./gradlew test`), on the same
//               bundled SQLite build the phone runs;
//   android   — the library the app links.
// It depends on :ringkit (the domain types), never the reverse.
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.google.devtools.ksp")
    id("androidx.room3")
}

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "io.github.opencircuit.store"
        compileSdk = 36
        minSdk = 34
        withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }
    compilerOptions {
        // Room generates the `actual` of the database constructor; expect/actual classes are Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ringkit"))
            implementation("androidx.room3:room3-runtime:3.0.3")
            implementation("androidx.sqlite:sqlite-bundled:2.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("androidx.room3:room3-testing:3.0.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        getByName("androidDeviceTest").dependencies {
            implementation("junit:junit:4.13.2")
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
            implementation("androidx.test:core:1.7.0")
        }
    }
}

dependencies {
    // KSP is configured per target (the plain `ksp` configuration is deprecated for multiplatform).
    add("kspJvm", "androidx.room3:room3-compiler:3.0.3")
    add("kspAndroid", "androidx.room3:room3-compiler:3.0.3")
}

// The exported schema of every database version. Committed: the migration tests read it, and the
// schema-history guards check it. The Room plugin rewrites it on every compile.
room3 { schemaDirectory("$projectDir/schemas") }

// A multiplatform module has no `test` task, so `./gradlew test` would silently skip this module.
// This lifecycle task wires the JVM tests in. `--tests` filters work on `:store:jvmTest` only.
tasks.register("test") {
    group = "verification"
    description = "Runs the JVM tests of :store (alias of jvmTest)."
    dependsOn("jvmTest")
}

tasks.named<Test>("jvmTest") {
    systemProperty("opencircuit.androidRoot", rootProject.projectDir.absolutePath)
    systemProperty("opencircuit.storeDir", projectDir.absolutePath)
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // One verbatim summary line per module, in the same format as :ringkit's.
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
