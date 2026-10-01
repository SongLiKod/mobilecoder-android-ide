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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mobilecoder-android-ide"

include(":app")
include(":core_common")
include(":core_native")
include(":core_storage")
include(":feature_editor")
include(":feature_terminal")
include(":feature_history")
include(":feature_git")
include(":feature_ssh")
include(":feature_build")
include(":feature_ai")
