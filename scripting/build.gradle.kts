plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    // The sandbox boundary. Nothing here may be referenced by player code: player
    // code is compiled against `:dsl` only, and reaches this module's classes solely
    // through the `ScriptRunner` interface implemented below.
    implementation(project(":core"))

    // Drives the Kotlin compiler at runtime to build player solutions.
    implementation(libs.kotlinCompilerEmbeddable)

    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinxSerialization)

    testImplementation(kotlin("test"))
    testImplementation(project(":core"))
}
