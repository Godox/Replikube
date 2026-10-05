plugins {
    // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Only the base Kotlin plugin. The serialization and Compose compiler plugins are
    // deliberately NOT here even though the convention plugin could apply them: putting
    // them on buildSrc's own classpath makes them "already on the classpath with an
    // unknown version" to Gradle, and every module's `alias(...)` for them then fails
    // with InvalidPluginRequestException. A module asks for them by version instead.
    implementation(libs.kotlinGradlePlugin)
}
