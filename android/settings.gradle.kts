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
// :ble, :store, :health and :app are added as their phases start.
include(":ringkit")
