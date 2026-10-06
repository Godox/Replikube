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
 * Compiles `:levels`'s JSON files into a Kotlin source file.
 *
 * ### Why a task *class* and not a `doLast` in the build script
 *
 * Two reasons, and the first is the one that bites.
 *
 * A `doLast { }` defined in a build script captures the script object, and the configuration
 * cache cannot serialise that -- so the build fails with "cannot serialize Gradle script
 * object references" and offers no hint that the problem is the *location* of the code rather
 * than anything about it. Every value the action needs has to be a serialisable property, and
 * a plain function at the top of a build script is not one.
 *
 * The second reason is correctness rather than mechanics: with typed `@InputDirectory` and
 * `@OutputDirectory` properties, Gradle knows the inputs, so this task is up-to-date-checked
 * and cacheable. A `doLast` wired to `inputs` by hand gets that too, but the types are what
 * make the declaration checkable rather than conventional.
 *
 * ### Why the levels are embedded at all
 *
 * `:levels` used to find its files by asking the classpath: `ClassLoader.getResources` for the
 * directory, then a `file:`/`jar:` branch to list it. That works on a JVM and nowhere else --
 * a browser has no classpath, no directory listing, and no synchronous I/O, so the same code
 * would need a `fetch`-based reimplementation. That would make `readMetadata` suspend, and
 * with it every caller of `LevelCatalog.levels`, up through `GameModel` and the level picker:
 * a *reading* of the campaign would become an *awaiting* of it.
 *
 * 23 KB of JSON does not justify that. Generated source keeps every signature synchronous and
 * identical on both targets, and deletes the `file:`-versus-`jar:` branch -- the exact class of
 * "works from Gradle, fails when packaged" bug that branch existed to paper over.
 *
 * The `.json` files stay the single source of truth. `EmbeddedLevelsTest` asserts the embedded
 * copy still matches them, so a stale generated directory fails the build rather than shipping
 * a puzzle that cannot be solved.
 */
@CacheableTask
abstract class GenerateLevelData : DefaultTask() {

    /** The `.json` files. Their names are the level ids. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    /** Where `EmbeddedLevels.kt` is written, in the package directory layout. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        // Explicit UTF-8 on both read and write. The platform default charset would embed a
        // non-ASCII hint differently on different machines, which is precisely the per-platform
        // drift this task exists to remove.
        val sourceDir = sourceDirectory.get().asFile
        val files = sourceDir.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty()
        require(files.isNotEmpty()) { "no level files in $sourceDir" }

        val ids = files.map { it.name.removeSuffix(".json") }.sorted()
        // Built with an explicit StringBuilder rather than one interpolated `"""..."""` per
        // entry. An id like `checker-sphere` inside a raw string is harmless, but the literal
        // wrappers in a raw string are easy to get wrong and the failure is a Kotlin syntax
        // error reported at a line of generated source nobody wrote -- so the id and the JSON
        // each go through the same escaper, and the surrounding punctuation is plain code.
        val entries = buildString {
            ids.forEach { id ->
                val json = File(sourceDir, "$id.json").readText(Charsets.UTF_8)
                append("    ").append(kotlinStringLiteral(id))
                // `trimEnd()`, not `trimIndent()`. The level files are indented by two spaces, and
                // `trimIndent` strips that common indent from every line -- which is harmless
                // for JSON parsing but means the embedded text no longer matches the file,
                // which is exactly what `EmbeddedLevelsTest` exists to catch. Only the
                // trailing newline is dropped, because that is a difference between a file
                // and a string literal rather than a difference in the level.
                append(" to ").append(kotlinStringLiteral(json.trimEnd()))
                if (id != ids.last()) append(",\n")
            }
        }

        val packageDir = File(outputDirectory.get().asFile, "fr/godox/replikube/levels")
        check(packageDir.mkdirs() || packageDir.isDirectory) {
            "could not create $packageDir"
        }
        File(packageDir, "EmbeddedLevels.kt").writeText(
            """
            |// GENERATED FILE - DO NOT EDIT.
            |// Written by the `generateLevelData` task from
            |// levels/src/commonMain/resources/levels/*.json, which are the source of truth.
            |// Edit those and re-run the task; anything written here is overwritten.
            |package fr.godox.replikube.levels
            |
            |/** Every level file, by id, as the JSON text it has on disk. */
            |internal val EMBEDDED_LEVEL_JSON: Map<String, String> = mapOf(
            |$entries
            |)
            |
            """.trimMargin(),
            Charsets.UTF_8,
        )
        logger.lifecycle("generateLevelData: embedded ${ids.size} levels (${ids.first()}..${ids.last()})")
    }
}

/**
 * A Kotlin string literal for arbitrary text.
 *
 * Written by hand rather than with a library because the output is fed to the Kotlin compiler,
 * not to a parser with an escape policy of its own: `$` in particular has to be doubled even
 * though no JSON syntax requires it, and a library that did not know that would emit a file
 * that fails to compile with an error pointing at the wrong line.
 */
internal fun kotlinStringLiteral(text: String): String = buildString {
    append('"')
    for (ch in text) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            // Control characters are escaped numerically: a raw one in a Kotlin literal is
            // legal but unreadable, and a level file is data someone has to diff.
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}