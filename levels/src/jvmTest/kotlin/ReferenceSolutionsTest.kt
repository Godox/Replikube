package fr.godox.replikube.levels

import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.scripting.InProcessScriptRunner
import fr.godox.replikube.scripting.RunResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guarantee that replaced compiling targets at load time.
 *
 * The target is stored data now, so it *can* disagree with the reference solution sitting
 * beside it in the same file. This compiles every one of the twenty and asserts an exact
 * match, which means a level can never be unsolvable by its own account, and a shape can
 * never drift from the solution that documents it.
 *
 * Slow, by nature: twenty real Kotlin compiles. Paid once per build rather than once per
 * launch — and no longer at all in the shipped game.
 *
 * ### Why this is a `jvmTest` and not a `commonTest`
 *
 * It needs a Kotlin compiler, and no compiler runs inside a browser. That is not a weakening
 * of the guarantee: this test is about the *data*, and the data is the same 23 KB of JSON on
 * every target. Compiling it once per build on the JVM checks the campaign that the browser
 * then serves. A second copy of the same assertion in wasm would cost twenty remote compiles
 * and could only ever reach the same verdict.
 *
 * Regenerate the matrices after editing a reference solution:
 * `REPLIKUBE_REGENERATE=true ./gradlew :levels:jvmTest --tests '*GenerateTargetsTest*'`
 */
class ReferenceSolutionsTest {

    private val catalog = LevelCatalog()
    private val ids = LevelCatalog.resourceIds()

    @Test
    fun `every reference solution still produces exactly its stored target`() = runTest {
        val runner = InProcessScriptRunner()
        val failures = mutableListOf<String>()
        for (id in ids) {
            val loaded = catalog.load(id).getOrThrow()
            val result = runner.run(loaded.reference, loaded.level.size)
            when (result) {
                is RunResult.Failure ->
                    failures += "$id: reference solution did not run: ${result.diagnostic}"

                is RunResult.Success -> {
                    if (result.grid != loaded.target) {
                        val (want, got) = describeMismatch(loaded.target, result.grid)
                        failures += "$id: reference solves to a different shape — $want, target is $got"
                    }
                }
            }
        }
        assertEquals(
            emptyList(),
            failures,
            "reference solutions disagree with their stored targets " +
                "(re-run GenerateTargetsTest with REPLIKUBE_REGENERATE=true):\n" +
                failures.joinToString("\n"),
        )
    }

    /**
     * A human-readable account of where two grids differ.
     *
     * "does not match" is useless when the fix is regenerating a matrix: the point is to see
     * *which* voxels, so the author can tell an intentional shape change from a transposed or
     * off-by-one matrix.
     */
    private fun describeMismatch(target: VoxelGrid, actual: VoxelGrid): Pair<String, String> {
        val diffs = target.allCoords().filter { target[it] != actual[it] }
        val shown = diffs.take(4).joinToString { "${it.x},${it.y},${it.z}" }
        val more = if (diffs.size > 4) " (+${diffs.size - 4} more)" else ""
        return "solution differs at ${shown}$more" to "the stored matrix"
    }
}
