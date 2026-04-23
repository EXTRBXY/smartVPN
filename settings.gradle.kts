pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Локальный libbox.aar: flatDir даёт нормальный AAR-metadata/extract; files() даёт extractedFolder=null на AGP 8+.
        flatDir {
            dirs(rootDir.resolve("androidApp/libs"))
        }
    }
}

rootProject.name = "NothingMobileVpn"
include(":androidApp")
include(":shared")
