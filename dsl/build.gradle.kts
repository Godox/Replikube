plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-multiplatform.gradle.kts`.
    id("buildsrc.convention.kotlin-multiplatform")
}

dependencies {
    // NOTE: this module deliberately has NO dependencies.
    //
    // `:dsl` is the player-facing API: it is the only module on the classpath when we
    // compile player code. Keeping it dependency-free means game internals (`:core`,
    // `:app`, ...) are unreachable from a solution by construction, not by a check.
    // Any dependency added here becomes visible to player code - think twice.
    //
    // This now has to hold on *both* targets. On the JVM the rule is enforced by
    // `playerClasspath()` in `:scripting`, which names `:dsl` and the stdlib and nothing
    // else. The wasm runner cannot use a jar at all -- the remote compiler has no classpath
    // of ours to add to -- so it ships `:dsl` as *source text* inside the request. That
    // means the sandbox there is only as strong as this module's dependency list, with
    // nothing enforcing it. Zero dependencies is now load-bearing for a second reason.
    //
    // `commonTest`'s kotlin("test") is the exception that proves the rule: it is not on the
    // compile classpath, is not transitive, and so is invisible to `:core` and to anything
    // compiling player code. It exists only to test this module in place.
}