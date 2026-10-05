plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    // NOTE: this module deliberately has NO dependencies.
    //
    // `:dsl` is the player-facing API: it is the only module on the classpath when we
    // compile player code. Keeping it dependency-free means game internals (`:core`,
    // `:app`, ...) are unreachable from a solution by construction, not by a check.
    // Any dependency added here becomes visible to player code — think twice.
    //
    // `testImplementation` is the exception that proves the rule: it does not appear on
    // the compile classpath, is not transitive, and so is invisible to `:core` and to
    // anything compiling player code. It exists only to test this module in place, rather
    // than to test it from `:core`.
    testImplementation(kotlin("test"))
}
