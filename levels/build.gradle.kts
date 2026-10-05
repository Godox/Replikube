plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    // Level content: each level's target matrix and its reference solution, both inside the
    // level's own JSON. `:scripting` is a *test* dependency only — loading a level needs no
    // compiler, which is the entire point of storing the matrix. The tests use it to prove
    // every reference solution still reproduces its stored target.
    implementation(project(":core"))

    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxCoroutines)

    testImplementation(kotlin("test"))
    testImplementation(project(":core"))
    testImplementation(project(":scripting"))
}
