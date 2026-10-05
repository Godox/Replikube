plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // The player-facing API. `:core` owns the rules, so `:core` -> `:dsl`, never
    // the other way round: this is what lets us compile player code against a
    // classpath containing `:dsl` alone.
    api(project(":dsl"))

    implementation(libs.bundles.kotlinxEcosystem)

    testImplementation(kotlin("test"))
}
