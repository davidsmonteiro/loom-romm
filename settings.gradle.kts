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

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Loom"

// :core-romm and :core-storage are plain Kotlin/JVM modules: everything that can be tested without
// Android (API client, layout planner, transfer engine) lives there. :app holds Android glue + UI.
include(":app")
include(":core-romm")
include(":core-storage")
