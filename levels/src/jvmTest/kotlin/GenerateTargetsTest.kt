package fr.godox.replikube.levels

import fr.godox.replikube.core.TargetMatrix
import fr.godox.replikube.scripting.InProcessScriptRunner
import fr.godox.replikube.scripting.RunResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regenerates every level's `target` matrix from the reference solution stored in its metadata.
 *
 * The one-off migration this project needed: targets used to be compiled at level-load time,
 * and the matrix for each has to be written down before the `.kt` files can go. Run it with
 *
 * ```
 * ./gradlew :levels:test --tests '*GenerateTargetsTest*' -i
 * ```
 *
 * and every `levels/src/main/resources/levels` JSON file is rewritten in place.
 *
 * ### Kept, deliberately
 *
 * This test is not deleted after the migration, because a shape can still be edited and the
 * matrix has to follow. It is the missing half of the guarantee that replaced compiling at
 * load time: nothing stops a level author from changing the reference solution and forgetting
 * the matrix, and this is what catches it.
 *
 * It is skipped by default and opted into with `-Dreplikube.regenerate=true`, so `./gradlew build`
 * does not rewrite tracked source files as a side effect of running tests.
 */
class GenerateTargetsTest {

    @Test
    fun `regenerate every level's target matrix from its reference solution`() {
        // An environment variable rather than a system property: Gradle does not forward
        // `-D` flags from the command line into the test JVM, so `System.getProperty` would
        // silently read null and the generator would appear to run while doing nothing. An
        // environment variable does reach the forked test process, so a mistyped flag fails
        // loudly instead of quietly skipping the rewrite.
        if (System.getenv(REGENERATE_ENV) != "true") {
            println("skipped: set $REGENERATE_ENV=true to rewrite the level files")
            return
        }

        val runner = InProcessScriptRunner()
        val dir = File("src/main/resources/levels")
        val files = dir.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty()
        require(files.isNotEmpty()) { "no level files in ${dir.absolutePath}" }

        for (file in files.sortedBy { it.name }) {
            val id = file.name.removeSuffix(".json")
            val level = LevelCatalog.readMetadata(id)
            // Read via `getOrNull`/`getOrElse` rather than `assertIs` with an interpolated
            // failure message: the message needs the *other* branch of the result, and casting
            // inside the message argument throws ClassCastException the moment the happy path
            // is taken — which is the only path this generator ever expects.
            val grid = when (val result = runBlocking { runner.run(level.reference, level.size) }) {
                is RunResult.Success -> result.grid
                is RunResult.Failure -> error("$id did not run: ${result.diagnostic}")
            }

            assertEquals(level.size, grid.size, "$id: solution produced a different size than declared")
            val text = TargetMatrix.encode(grid)
            val existing = file.readText()

            // The matrix is replaced by regex rather than by re-serialising the whole level:
            // re-serialising would reflow the hand-written hint and reference, making the diff
            // for a 20-file migration unreadable. Only the numbers change.
            val updated = MATRIX_REGEX.replace(existing, "\"target\": $text,")
            check(updated != existing || existing.contains("\"target\": $text,")) {
                "$id: could not find a \"target\" field to replace in ${file.name}"
            }
            file.writeText(updated)
            println("regenerated $id: ${grid.solidCount} solid, ${text.length} chars")
        }
    }

    private companion object {
        /** Opt-in flag for rewriting the level files. */
        const val REGENERATE_ENV = "REPLIKUBE_REGENERATE"

        /**
         * Matches the `"target": [...]` field plus its trailing comma.
         *
         * A character class rather than `.*` so it cannot run past the closing bracket if a
         * matrix were ever nested, and no `DOT_MATCHES_ALL` so a stray newline cannot make it
         * swallow the rest of the file.
         */
        val MATRIX_REGEX = Regex("\"target\"\\s*:\\s*\\[[^]\\n]*],?")
    }
}
