import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("buildsrc.convention.kotlin-multiplatform")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    // The desktop entry point is a jvm `main` driven by the Compose plugin's own `run` and
    // `package*` tasks. `:app` has no `application` plugin because that would collide with
    // `run` at configuration time.
    // Declared by the convention plugin (`buildsrc.convention.kotlin-multiplatform`); repeated
    // here only because the Compose Desktop block below configures the `jvm()` target by name.
    jvm()

    // `by getting` because the multiplatform plugin arrives via the convention plugin's id,
    // so the generated `commonMain { }` accessors do not exist. See core/build.gradle.kts.
    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api` rather than `implementation`: a caller of `:app` needs the `Level` and
                // `Progress` types that appear in `GameModel`'s signatures, and both come
                // transitively through here.
                api(project(":core"))
                api(project(":levels"))

                // `:scripting` provides the *interface* both targets share. Its JVM
                // implementation -- the embedded compiler -- is jvmMain-only and is only ever
                // reached through that interface, which is the whole point of
                // `platformScriptRunner` in Platform.kt.
                implementation(project(":scripting"))

                // The UI module is the only one that touches Compose. Nothing below it does.
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)

                implementation(libs.bundles.kotlinxEcosystem)
            }
        }
        val jvmMain by getting {
            dependencies {
                // `compose.desktop.currentOs` is the Skiko-backed AWT/Swing runtime. It is a
                // jvmMain dependency and *only* a jvmMain one: the wasm target gets its canvas
                // from the Compose libraries already declared in commonMain above, and pulling
                // this in there would drag a native Skiko library into a browser build.
                implementation(compose.desktop.currentOs)
            }
        }
        val jvmTest by getting {
            dependencies {
                // The two headless render suites live here: Skia, AWT and ImageIO have no
                // wasm equivalent, so they are not compiled for the browser rather than being
                // stubbed out. There is no headless pixel buffer in a browser to assert on
                // anyway -- the only way to see pixels is a screenshot of a real tab.
                implementation(project(":core"))
                implementation(project(":levels"))

                // `ImageComposeScene`, the render tests' entry point, lives in the *desktop*
                // artifact and has no wasm counterpart. That, rather than any AWT import in
                // the test sources themselves, is the hard reason these two files cannot be
                // shared with the browser.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "fr.godox.replikube.app.MainKt"
        nativeDistributions {
            // jpackage rejects a name with spaces, which is the plugin's default.
            packageName = "replikube"
            packageVersion = "1.0.0"
            description = "Reproduce 3D shapes by writing Kotlin"
            vendor = "replikube"

            // The compiler needs reflection and instrumentation at runtime; without
            // these the app dies on startup with a confusing NoClassDefFoundError.
            modules("java.instrument", "java.management", "jdk.unsupported")

            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
        }
    }
}