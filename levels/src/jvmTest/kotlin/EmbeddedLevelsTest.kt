package fr.godox.replikube.levels

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The embedded data is the `.json` files, byte for byte.
 *
 * `generateLevelData` copies those files at build time, so in principle the embedded copy
 * cannot drift from its source. In practice a stale generated directory, a hand-edited
 * `EmbeddedLevels.kt`, or a task that silently skips a file would all produce a build that is
 * internally consistent and quietly wrong — and the symptom would be a puzzle that cannot be
 * solved, discovered by a player rather than by a build.
 *
 * Comparing the embedded copy against the files needs a filesystem, so this is a `jvmTest`
 * rather than a `commonTest`. That is not a gap in the guarantee: the generator runs on the
 * JVM for every target, so a check on the JVM is a check on the browser's data too. Running
 * it twice would only assert the same thing at twice the cost.
 */
class EmbeddedLevelsTest {

    private val sourceDir = File("src/commonMain/resources/levels")

    @Test
    fun `embedded levels match the json files they were generated from`() {
        val files = sourceDir.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty()
        assertTrue(files.isNotEmpty(), "no level files in ${sourceDir.absolutePath}")

        val failures = mutableListOf<String>()
        val embeddedIds = LevelCatalog.resourceIds()
        val sourceIds = files.map { it.name.removeSuffix(".json") }.sorted()
        if (embeddedIds != sourceIds) {
            failures += "embedded ids $embeddedIds but the files are $sourceIds"
        }

        for (file in files) {
            val id = file.name.removeSuffix(".json")
            val onDisk = file.readText(Charsets.UTF_8)
            val got = EMBEDDED_LEVEL_JSON[id]
            when {
                got == null ->
                    failures += "$id is a file but is not embedded"

                // Compared trimmed: the generated literal drops the trailing newline the file
                // ends with, and trailing whitespace is not a difference in the level.
                got.trim() != onDisk.trim() ->
                    failures += "$id differs from its file: ${got.length} embedded chars vs ${onDisk.length} on disk"
            }
        }

        assertEquals(emptyList(), failures, "generated level data is stale:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `every embedded level is parseable json that decodes`() {
        // Belt and braces against the comparison above: that test passes if the two texts
        // agree, and would happily agree on *escaped* text that no longer parses. This
        // asserts the property actually relied on at runtime -- the embedded text is valid
        // JSON, and it decodes to a level whose id matches the key it is filed under.
        for ((id, json) in EMBEDDED_LEVEL_JSON) {
            runCatching { Json.parseToJsonElement(json) }
                .onFailure { throw AssertionError("$id is not valid JSON once unescaped: ${it.message}", it) }
            val level = LevelCatalog.readMetadata(id)
            assertEquals(id, level.id, "$id decodes to a level with a different id")
        }
    }
}