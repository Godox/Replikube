// Shared build logic for every replikube module. Multiplatform, two targets.
//
// `jvm()` and `wasmJs()` are both declared on every module, so a module that needs an
// `expect`/`actual` pair has both sides available from the start. The alternative --
// adding a target later, per module -- produces a build where `commonMain` was written
// under an assumption about available targets that quietly stopped holding.
//
// `commonTest` is where the platform-agnostic tests go: grid semantics, diffs, stars,
// geometry, the projection maths. The JVM-only suites (Skia rendering) live in `jvmTest`
// and are simply not compiled for wasm -- which is correct, not a loss: there is no
// headless pixel buffer in a browser to assert on.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    // JDK 17 stays the JVM floor. It is the version the packaged desktop app bundles and
    // the version `MIN_PLAYER_TARGET` in `:scripting` compiles player code to, so raising
    // it here without raising it there reintroduces the packaging mismatch documented in
    // scripting/AGENT.md: a solution compiled to a newer class file version than the app's
    // own runtime refuses to load, with a message naming neither the level nor the cause.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    wasmJs {
        // Browser is the target, not Node: the game is a window with a canvas. `browser()`
        // also pulls in the dev server, so `wasmJsBrowserDevelopmentRun` works out of the
        // box. Node output is not built because nothing consumes it.
        browser()
        binaries.executable()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
}