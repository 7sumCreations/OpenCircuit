plugins {
    kotlin("jvm") version "2.3.0" apply false
    // :store — a Kotlin Multiplatform library (JVM + Android) holding the Room database.
    kotlin("multiplatform") version "2.3.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room3") version "3.0.3" apply false
}
