import buildsrc.tasks.GenerateLevelData

plugins {
    // Apply the shared build logic from a convention plugin.
    id("buildsrc.convention.kotlin-multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

/**
 * Embeds the level JSON into Kotlin source, so both targets read the same bytes.
 *
 * The reasoning, and the class that does the work, are in `buildSrc/.../GenerateLevelData.kt`.
 * This block only wires it up. `:levels` stays free of any runtime resource lookup, which is
 * what its own AGENT.md means by "no compiler dependency, and that is the whole design" --
 * extended here to "no classpath dependency either".
 */
val generateLevelData by tasks.registering(GenerateLevelData::class) {
    sourceDirectory.set(layout.projectDirectory.dir("src/commonMain/resources/levels"))
    outputDirectory.set(layout.buildDirectory.dir("generated/levelData/kotlin"))
}

kotlin {
    // `by getting` because the multiplatform plugin arrives via the convention plugin's id,
    // so the generated `commonMain { }` accessors do not exist. See core/build.gradle.kts.
    sourceSets {
        val commonMain by getting {
            // The generated level data, compiled into both targets. Wired here rather than
            // into `commonMain.resources` because it is *source*, not a resource: nothing at
            // runtime looks it up, so there is no loading step to get wrong per platform.
            //
            // The `map` is what carries the task dependency -- `srcDir` accepts a provider, so
            // Gradle knows this source set needs the task's output. That is why there is no
            // `dependsOn` below: without it, the wiring would be correct in the same way a
            // correct comment is correct, and a stale `build/generated` would produce a
            // compile error naming `EMBEDDED_LEVEL_JSON` rather than naming the task.
            kotlin.srcDir(generateLevelData.map { it.outputDirectory })

            dependencies {
                // Level content: each level's target matrix and its reference solution,
                // both inside the level's own JSON. Loading a level needs no compiler, which
                // is the entire point of storing the matrix.
                api(project(":core"))

                implementation(libs.kotlinxSerialization)
                implementation(libs.kotlinxCoroutines)
            }
        }
        val commonTest by getting {
            dependencies {
                // `runTest`, not `runBlocking`. `runBlocking` blocks a real thread, which a
                // browser cannot spare -- it has one, and the game is on it. So the suite that
                // exercises `LevelCatalog.load` cannot call it in common code at all.
                // `runTest` is the multiplatform spelling: on the JVM it runs the body on a
                // virtual-time test dispatcher, and in a browser it runs it on the single
                // thread. The same assertion, on both targets.
                implementation(libs.kotlinxCoroutinesTest)
            }
        }
        val jvmTest by getting {
            dependencies {
                // The drift guard: compiles all twenty reference solutions and checks them
                // against their stored matrices. This is the edge that must never become a
                // main dependency -- see the note below.
                //
                // The `:scripting` *test* dependency here is deliberate and load-bearing. It
                // is what makes the guarantee "every reference solution still reproduces its
                // stored target" a test rather than a property of the data. Promoting it to
                // `commonMain` would put the Kotlin compiler back on the level-loading
                // path, which is precisely the bug the stored targets exist to remove: a
                // packaged build without a working compiler would then be unable to open
                // any level at all.
                //
                // `jvmTest`, not `commonTest`, because no compiler runs in a browser. The
                // data it checks is the same 23 KB either way, so the guarantee holds for the
                // campaign the browser serves without compiling it twice.
                implementation(project(":scripting"))
            }
        }
    }
}

// Belt and braces on top of the provider wiring in `commonMain` above. `tasks.matching`
// rather than naming tasks, because the Kotlin plugin's compile task names differ between
// targets (`compileKotlinJvm`, `compileKotlinWasmJs`) and will differ again for any target
// added later. Matching on the name is the one rule that survives that.
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateLevelData)
}
