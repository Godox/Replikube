// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        // Compose Multiplatform re-publishes JetBrains' AndroidX forks under their own
        // group ids but keeps the AndroidX versions, and those POMs only exist on Google's
        // Maven. Without this, :app fails to resolve androidx.compose.runtime:runtime:1.9.4.
        google()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "replikube"

// `:app` sits on top; `:dsl` is a leaf that only player code depends on.
include(":app")
include(":core")
include(":dsl")
include(":levels")
include(":scripting")