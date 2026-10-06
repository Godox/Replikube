package buildsrc.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Compiles `:dsl`'s Kotlin sources into a Kotlin source file, as text.
 *
 * ### Why the sources and not the compiled classes
 *
 * The JVM runner hands `:dsl`'s **jar** to `K2JVMCompiler` as the compile classpath, which is
 * what enforces "player code can see `:dsl` and the stdlib, nothing else" — `:core`, `:app` and
 * `:scripting` are simply not on that classpath.
 *
 * The remote runner cannot do that. It posts sources to a compiler service that has no
 * classpath of ours to add to and no repository of our jars, so the only way to give it `:dsl`
 * is to send the source text alongside the player's. That weakens the sandbox by exactly the
 * amount `:dsl`'s dependency list grows: anything `:dsl` can see, so can the player.
 * `dsl/build.gradle.kts` says so next to its (empty) dependency block.
 *
 * ### Why this is not a hand-written copy in `:scripting`
 *
 * The obvious alternative -- pasting the palette constants into the runner -- compiles, works,
 * and then silently rots the first time someone adds `Palette.INDIGO`. Here the `.kt` files in
 * `:dsl` stay the single source of truth and the generated copy is rebuilt from them.
 * `DslSourcesTest` asserts the embedded map is non-empty and that every file it names exists,
 * so a stale generated directory fails the build rather than shipping a player a palette that
 * is missing a colour the game renders.
 *
 * ### Why the comments are kept
 *
 * All three files are 8.4 KB together, comments included, so stripping them would save about
 * nothing and introduce a second thing that can disagree with `:dsl`. They are also what makes
 * a compiler error in the harness readable: when the remote build fails on a DSL file, the
 * message names a line the player can look up.
 */
@CacheableTask
abstract class GenerateDslSources : DefaultTask() {

    /** `:dsl`'s `commonMain` Kotlin sources. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    /** Where `EmbeddedDslSources.kt` is written, in the package directory layout. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        // Explicit UTF-8 both ways; see GenerateLevelData for why the platform default is wrong.
        val sourceDir = sourceDirectory.get().asFile
        val files = sourceDir.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty()
        require(files.isNotEmpty()) { "no .kt sources in $sourceDir" }

        // Sorted so the generated file is byte-identical across machines and across checkouts
        // where the filesystem enumerates in a different order.
        val names = files.map { it.name }.sorted()
        val entries = buildString {
            names.forEach { name ->
                val text = File(sourceDir, name).readText(Charsets.UTF_8)
                append("    ").append(kotlinStringLiteral(name))
                append(" to ").append(kotlinStringLiteral(text.trimEnd()))
                if (name != names.last()) append(",\n")
            }
        }

        val packageDir = File(outputDirectory.get().asFile, "fr/godox/replikube/scripting")
        check(packageDir.mkdirs() || packageDir.isDirectory) { "could not create $packageDir" }
        File(packageDir, "EmbeddedDslSources.kt").writeText(
            """
            |// GENERATED FILE - DO NOT EDIT.
            |// Written by the `generateDslSources` task from
            |// dsl/src/commonMain/kotlin/*.kt, which are the source of truth.
            |// Edit those and re-run the task; anything written here is overwritten.
            |package fr.godox.replikube.scripting
            |
            |/**
            | * `:dsl`'s player-facing sources, by file name, exactly as they are on disk.
            | *
            | * Posted verbatim to the compiler service so a remote solution compiles against the
            | * same vocabulary a local one does. See the task's own doc for why the source text
            | * travels instead of the jar.
            | */
            |internal val DSL_SOURCE: Map<String, String> = mapOf(
            |$entries
            |)
            |
            """.trimMargin(),
            Charsets.UTF_8,
        )
        logger.lifecycle(
            "generateDslSources: embedded ${names.size} files (${names.first()}..${names.last()})",
        )
    }
}