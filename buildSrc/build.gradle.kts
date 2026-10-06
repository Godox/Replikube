plugins {
    // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
}

kotlin {
    // The toolchain says *which JDK compiles*, not what bytecode comes out. `buildSrc` is
    // the one place that had no JDK on this machine at all -- it was a bare Temurin JRE, so
    // Gradle failed with "No Java compiler found" before it ever reached a module.
    //
    // 21, matching `JAVA_HOME`. Nothing here ships, so its output's class file version is
    // irrelevant to players: this only builds the convention plugins, and Gradle loads them
    // in the launcher JVM. The bytecode level that actually matters is `jvmTarget` in
    // `kotlin-multiplatform.gradle.kts`, which stays at 17.
    jvmToolchain(21)
}

dependencies {
    // Only the base Kotlin plugin. The serialization and Compose compiler plugins are
    // deliberately NOT here even though the convention plugin could apply them: putting
    // them on buildSrc's own classpath makes them "already on the classpath with an
    // unknown version" to Gradle, and every module's `alias(...)` for them then fails
    // with InvalidPluginRequestException. A module asks for them by version instead.
    implementation(libs.kotlinGradlePlugin)
}
