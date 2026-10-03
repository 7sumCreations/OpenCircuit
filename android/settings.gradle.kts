pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "OpenCircuitAndroid"

// :ringkit — pure Kotlin/JVM port of ios/OpenCircuitKit (no Android imports).
// :store — the on-device Room database (Kotlin Multiplatform: JVM for tests, Android for the app);
//          depends on :ringkit, never the reverse.
// :ble, :health and :app are added as their phases start.
include(":ringkit")
include(":store")
