plugins {
    // Apply the shared build logic from a convention plugin.
    id("buildsrc.convention.kotlin-multiplatform")
    // Serialization works the same on both targets: the `@Serializable` types in
    // `commonMain` generate JVM serializers and Kotlin/Wasm serializers from the same
    // declarations, so level files and progress saves decode identically.
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    // `by getting` rather than the bare `commonMain.dependencies { }` shorthand. That
    // shorthand is a generated accessor, and the multiplatform plugin is applied here
    // through the convention plugin's id rather than in this file's own `plugins` block --
    // so Gradle never generates it, and the short form does not resolve. The explicit
    // `val x by getting` form works either way.
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The player-facing API. `:core` owns the rules, so `:core` -> `:dsl`,
                // never the other way round: this is what lets us compile player code
                // against a classpath containing `:dsl` alone. `api`, not
                // `implementation`, because the palette is part of the vocabulary every
                // level's target is written in.
                api(project(":dsl"))

                implementation(libs.bundles.kotlinxEcosystem)
            }
        }
    }
}