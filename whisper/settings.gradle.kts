// Standalone build of the :whisper module, so it can be built and tested on its own:
//   ./gradlew :assembleRelease
// When the app's settings.gradle.kts does include(":whisper"), this file (and gradle/, gradlew,
// gradle.properties next to it) are ignored and the app's version catalog is used instead.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "whisper"
