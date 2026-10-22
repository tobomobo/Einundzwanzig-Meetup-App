pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "einundzwanzig-native"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// Core modules are added here as they are created. Keep alphabetical.
file("core").listFiles()?.filter { File(it, "build.gradle.kts").exists() }?.sortedBy { it.name }?.forEach {
    include(":core:${it.name}")
}
file("data").listFiles()?.filter { File(it, "build.gradle.kts").exists() }?.sortedBy { it.name }?.forEach {
    include(":data:${it.name}")
}
file("feature").listFiles()?.filter { File(it, "build.gradle.kts").exists() }?.sortedBy { it.name }?.forEach {
    include(":feature:${it.name}")
}
