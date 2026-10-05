plugins {
    id("buildsrc.convention.kotlin-multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    // `by getting` because the multiplatform plugin arrives via the convention plugin's id,
    // so the generated `commonMain { }` accessors do not exist. See core/build.gradle.kts.
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The sandbox boundary. Nothing here may be referenced by player code:
                // player code is compiled against `:dsl` only, and reaches this module's
                // classes solely through the `ScriptRunner` interface.
                api(project(":core"))

                implementation(libs.kotlinxCoroutines)

                // The remote runner's request and response bodies are decoded with
                // kotlinx.serialization, so its DTOs are `@Serializable` rather than
                // hand-parsed. A hand-rolled parser for a nested JSON shape with optional
                // fields is exactly the kind of thing that silently mis-reads a field.
                implementation(libs.kotlinxSerialization)
            }
        }
        val jvmMain by getting {
            dependencies {
                // Drives the Kotlin compiler at runtime to build player solutions. JVM
                // only: `kotlin-compiler-embeddable` is a JVM application and no compiler
                // runs inside a browser. The wasm build gets `RemoteScriptRunner` instead,
                // which sends the same generated source to a compiler service -- see its
                // own doc for why that is a change of venue rather than a loss.
                implementation(libs.kotlinCompilerEmbeddable)
            }
        }
        val jvmTest by getting {
            dependencies {
                // The two suites here really compile and run Kotlin on a JVM, which is the
                // only place that can be asserted at all.
                implementation(project(":core"))
                implementation(project(":dsl"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(project(":dsl"))
            }
        }
    }
}